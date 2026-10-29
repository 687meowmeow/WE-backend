package com.wepwawet.pairing

import android.content.Context
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_GetUserFiles_Response
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.CPublishedFile_QueryFiles_Response
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient.PublishedFileDetails
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.callback.ServiceMethodResponse
import `in`.dragonbra.javasteam.steam.handlers.steamworkshop.GetUserFilesDetails
import `in`.dragonbra.javasteam.steam.handlers.steamworkshop.QueryFilesDetails
import `in`.dragonbra.javasteam.steam.handlers.steamworkshop.SteamWorkshop
import com.wepwawet.data.model.WallpaperType
import com.wepwawet.data.model.WorkshopItem
import com.wepwawet.data.model.WorkshopItemTyped
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeUnit

/**
 * Singleton long-lived JavaSteam session used for workshop BROWSE queries.
 *
 * This is the SAME call that `steam_api64.dll` (the native Steamworks SDK) makes when WE PC
 * browses the workshop. WE-backend Android can't ship `steam_api64.dll`, but JavaSteam speaks
 * the Steam CM protocol directly — so we make byte-identical service calls to what WE PC makes.
 *
 * Bypasses Steam's IPublishedFileService HTTP API `[1,7]` days limit for RankedByTrend:
 *   - days = 1   → "Most Popular (Today)"
 *   - days = 7   → "Most Popular (Week)"
 *   - days = 30  → "Most Popular (Month)"
 *   - days = 365 → "Most Popular (Year)"
 *
 * Architecture:
 *  - On first [queryFiles] call, lazily creates a [WorkshopDownloader] and authenticates.
 *  - The session stays alive between browse queries (no `cleanup()`).
 *  - Subsequent queries reuse the same authenticated [SteamWorkshop] handler.
 *  - If the session disconnects (e.g., network drop, Steam rate limit), the next query
 *    auto-reconnects.
 *
 * Thread-safety: all state mutations are guarded by `synchronized(this)`. The JavaSteam
 * callback manager thread runs [org.jobCallbackLoop] in a daemon thread.
 *
 * Limitation: only ONE session is allowed at a time. If the user is currently running a
 * download via [WorkshopDownloadJob], that download uses its OWN [WorkshopDownloader] —
 * they don't share state. This is fine because both downloaders can authenticate with
 * the same Steam account simultaneously (Steam allows multiple concurrent sessions).
 *
 * Usage from [com.wepwawet.data.SteamApiService]:
 * ```kotlin
 * val session = WorkshopBrowseSession
 * val result = session.queryFiles(context, QueryFilesDetails(
 *     appId = 431960,
 *     queryType = EPublishedFileQueryType.k_PublishedFileQueryType_RankedByTrend,
 *     days = 365u,  // MOST_POPULAR_YEAR — bypasses HTTP API [1,7] limit
 *     page = 1,
 *     requiredKvTags = listOf("app_workshop_eula_version" to "3"),  // mobile-compat
 *     excludedTags = listOf("Application", "Web"),
 *     returnTags = true,
 *     returnPreviews = true,
 *     returnShortDescription = true,
 * ))
 * ```
 */
object WorkshopBrowseSession {

    private const val TAG = "WorkshopBrowseSession"

    /** Maximum time to wait for authentication to complete (in milliseconds). */
    private const val AUTH_TIMEOUT_MS = 60_000L

    // v0.5.32 (audit H4): Separate the query timeout from the auth timeout.
    // Previously AUTH_TIMEOUT_MS (60s) was reused for withTimeoutOrNull
    // around the actual Steam CM RPC. On timeout, withTimeoutOrNull
    // returned null, the elvis operator returned Result.failure(...),
    // and the function exited BEFORE reaching the catch (e: Exception)
    // block — so isAuthenticated was NOT reset on timeout. The session
    // stayed marked "authenticated" even though the underlying Steam CM
    // was unresponsive. The user got stuck in a "this session is fine
    // but every query takes 60s" loop. Restarting the app was the only
    // recovery.
    //
    // Per the audit, Steam CM RPCs under load (especially RankedByTrend
    // over a 365-day window for WE's 3M+ items) can take 30-60+ seconds
    // for the first query. We use 60s for queries (same as auth) so
    // transient slow-but-working queries don't get falsely flagged as
    // timeouts; the key fix is that we now RESET isAuthenticated on the
    // timeout path so the next call forces a reconnect.
    private const val QUERY_TIMEOUT_MS = 60_000L

    /** Lock that serializes concurrent ensureConnected calls. Uses tryLock with
     *  a 30-second timeout so queued browse queries don't block indefinitely when
     *  auth is in progress (which can take up to 120s). Replaces the original
     *  synchronized(authLock) which had no timeout. */
    private val authLock = ReentrantLock()

    /** Singleton downloader instance — null until first [queryFiles] call. */
    @Volatile
    private var downloader: WorkshopDownloader? = null

    /** True after a successful authentication; false after a disconnect. */
    @Volatile
    private var isAuthenticated = false

    /**
     * Ensure the [downloader] is created and authenticated. Subsequent calls return immediately.
     *
     * @param context Android context (used to read Steam credentials from [SteamCredentialsStore])
     * @return `true` if authenticated and ready for browse queries; `false` on auth failure or timeout.
     */
    private fun ensureConnected(context: Context): Boolean {
        // Fast path: session is authenticated AND the SteamClient is actually
        // connected (not just that auth was previously successful — catches
        // WebSocket watchdog disconnects after 30s of inactivity) AND the
        // SteamWorkshop handler is available.
        if (isAuthenticated && downloader?.isSteamConnected() == true && downloader?.getSteamWorkshopHandler() != null) {
            return true
        }
        // Use tryLock with a 30-second timeout — prevents indefinite blocking
        // when multiple browse queries queue up while auth is in progress
        // (which can take up to 120s). Replaces the original synchronized(authLock)
        // which had no timeout and blocked queued callers indefinitely.
        if (!authLock.tryLock(30, TimeUnit.SECONDS)) {
            WepWawetLogger.workshopWarn("$TAG: auth lock timeout (30s) — another auth in progress")
            return false
        }
        try {
            // Double-check after acquiring lock
            if (isAuthenticated && downloader?.isSteamConnected() == true && downloader?.getSteamWorkshopHandler() != null) {
                return true
            }

            // Get credentials
            val username = SteamCredentialsStore.getUsername(context)
            val password = SteamCredentialsStore.getPassword(context)
            if (username == null || password == null) {
                WepWawetLogger.workshopWarn("$TAG: no Steam credentials saved")
                return false
            }

            // Create downloader if not already created (reuse across reconnects)
            val downloader = this.downloader ?: WorkshopDownloader(username, password).also {
                this.downloader = it
            }

            // Try to connect
            WepWawetLogger.workshop("$TAG: connecting to Steam for browse session…",
                "username" to username,
            )
            val connected = downloader.connectAndAuthenticate(context)
            if (!connected) {
                WepWawetLogger.workshopWarn("$TAG: connection/auth failed — browse session unavailable")
                // v0.5.32 (audit H3): clean up the partially-initialized
                // downloader and clear the cached reference so the next
                // ensureConnected() call creates a FRESH downloader.
                //
                // Previously the (partially-initialized) downloader was
                // left in this.downloader forever. Next browse query
                // re-entered the lock and called connectAndAuthenticate
                // on the SAME downloader — leaking resources on every
                // call (per audit H2: SteamClient TCP socket + OkHttp
                // thread pool). Worse: if the original failure was
                // RateLimitExceeded, the cached downloader's callback
                // thread was still running and could dispatch a late
                // onLoggedOn callback that mutated `authenticated`
                // mid-query.
                runCatching { downloader.cleanup() }
                this.downloader = null
                isAuthenticated = false
                return false
            }

            // Verify the SteamWorkshop handler is available
            val workshop = downloader.getSteamWorkshopHandler()
            if (workshop == null) {
                WepWawetLogger.workshopWarn("$TAG: connected but SteamWorkshop handler is null")
                // v0.5.32 (audit H3): same cleanup as above — the
                // downloader connected but is unusable (no workshop
                // handler). Don't leave it half-initialized.
                runCatching { downloader.cleanup() }
                this.downloader = null
                isAuthenticated = false
                return false
            }

            isAuthenticated = true
            WepWawetLogger.workshop("$TAG: browse session ready — ISteamPublishedFile.QueryFiles available")
            return true
        } finally {
            authLock.unlock()
        }
    }

    /**
     * Issue a `ISteamPublishedFile.QueryFiles` service RPC and return the parsed response.
     *
     * This is the SAME call WE PC's `wallpaperui.exe` makes via `steam_api64.dll`'s
     * `ISteamUGC::SendQueryUGCRequest`. The native SDK does NOT have the HTTP API's
     * [1,7] days limit for RankedByTrend — neither does this service path (it speaks
     * the same Steam CM protocol that `steam_api64.dll` speaks).
     *
     * If the session is not yet connected, [ensureConnected] is called first. If
     * authentication fails (no credentials, wrong password, Steam down, etc.), this
     * returns `Result.failure` and the caller should fall back to HTTP/HTML.
     *
     * @param context Android context for reading Steam credentials.
     * @param details The query parameters (see [QueryFilesDetails]).
     * @return Success with a list of [WorkshopItemTyped]; failure with the cause.
     */
    suspend fun queryFiles(
        context: Context,
        details: QueryFilesDetails,
    ): Result<List<WorkshopItemTyped>> = withContext(Dispatchers.IO) {
        try {
            // Ensure session is connected
            if (!ensureConnected(context)) {
                return@withContext Result.failure(
                    IllegalStateException("JavaSteam browse session not available — see logs")
                )
            }

            val workshop: SteamWorkshop = downloader?.getSteamWorkshopHandler()
                ?: return@withContext Result.failure(
                    IllegalStateException("SteamWorkshop handler is null after auth")
                )

            // Dispatch the RPC — returns AsyncJobSingle<ServiceMethodResponse<CPublishedFile_QueryFiles_Response.Builder>>
            val job = workshop.queryFiles(details)

            // Await the response with a timeout. AsyncJobSingle.await() returns the
            // ServiceMethodResponse when the callback manager dispatches it.
            //
            // v0.5.32 (audit H4): use the dedicated QUERY_TIMEOUT_MS (was
            // AUTH_TIMEOUT_MS) and RESET isAuthenticated on timeout so the
            // next call forces a reconnect — previously the session stayed
            // marked "authenticated" after a query timeout and the user
            // got stuck in a "session fine, every query takes 60s" loop.
            val response: ServiceMethodResponse<CPublishedFile_QueryFiles_Response.Builder> =
                withTimeoutOrNull(QUERY_TIMEOUT_MS) {
                    job.await()  // AsyncJobSingle.await() is suspend
                } ?: run {
                    WepWawetLogger.workshopWarn(
                        "$TAG: queryFiles timed out — marking session for reconnect",
                        "timeout_ms" to QUERY_TIMEOUT_MS,
                    )
                    isAuthenticated = false  // force reconnect on next call
                    return@withContext Result.failure(
                        IllegalStateException("ISteamPublishedFile.QueryFiles timed out after $QUERY_TIMEOUT_MS ms")
                    )
                }

            // Parse the response
            val body = response.body.build()
            val items = body.publishedfiledetailsList.map { mapPublishedFileDetails(it) }

            WepWawetLogger.workshop(
                "$TAG: queryFiles OK",
                "total" to body.total,
                "returned" to items.size,
                "has_next_cursor" to (body.nextCursor?.isNotBlank() == true),
                "query_type" to details.queryType.number,
                "days" to (details.days?.toInt() ?: 0),
                "page" to details.page,
                "cursor" to (details.cursor ?: "page-mode"),
            )
            Result.success(items)
        } catch (e: CancellationException) {
            // CRITICAL: Coroutine cancellation (e.g., user navigated away from the
            // WorkshopBrowserScreen while queryFiles was in flight) MUST be
            // re-thrown, NOT treated as a query failure.
            //
            // The previous code caught `Exception` here and reset
            // isAuthenticated=false, which caused the next query (after the user
            // came back) to needlessly re-authenticate (10-15 seconds slower).
            // This also polluted the logs with `LeftCompositionCancellationException`
            // warnings that were not real failures.
            //
            // CancellationException is the parent class of:
            //   - kotlinx.coroutines.JobCancellationException
            //   - androidx.compose.runtime.LeftCompositionCancellationException
            //   - androidx.compose.runtime.Recomposer$CancellationException
            //
            // Per the kotlinx.coroutines contract, all coroutine cancellation
            // MUST propagate up. Swallowing it would break structured concurrency.
            WepWawetLogger.workshopDebug(
                "$TAG: queryFiles cancelled (coroutine scope disposed)",
                "exception" to e.javaClass.name,
            )
            throw e
        } catch (e: Exception) {
            WepWawetLogger.workshopWarn(
                "$TAG: queryFiles failed",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""),
            )
            // Mark session as needing reconnect on next call
            isAuthenticated = false
            Result.failure(e)
        }
    }

    /**
     * Map a JavaSteam [PublishedFileDetails] protobuf to our [WorkshopItemTyped] model.
     *
     * The protobuf message has all the fields we need (publishedfileid, title, tags, etc.).
     * The type (Scene/Video/etc.) is derived from the tags via [WallpaperType.fromTags].
     */
    /**
     * Issue a `ISteamPublishedFile.GetUserFiles` service RPC to browse a specific
     * creator's published workshop files. This bypasses HTTP 429 rate limiting
     * that occurs when scraping steamcommunity.com/profiles/<steamId>/myworkshop/.
     *
     * Uses the same authenticated JavaSteam session as [queryFiles]. The response
     * contains the same [PublishedFileDetails] protobuf, so the mapping logic is
     * identical.
     */
    suspend fun getUserFiles(
        context: Context,
        details: GetUserFilesDetails,
    ): Result<List<WorkshopItemTyped>> = withContext(Dispatchers.IO) {
        try {
            if (!ensureConnected(context)) {
                return@withContext Result.failure(
                    IllegalStateException("JavaSteam browse session not available — see logs")
                )
            }

            val workshop: SteamWorkshop = downloader?.getSteamWorkshopHandler()
                ?: return@withContext Result.failure(
                    IllegalStateException("SteamWorkshop handler is null after auth")
                )

            val job = workshop.getUserFiles(details)
            // v0.5.32 (audit H4): same fix as queryFiles — use
            // QUERY_TIMEOUT_MS (was AUTH_TIMEOUT_MS) and RESET
            // isAuthenticated on timeout to force reconnect on next call.
            val response: ServiceMethodResponse<CPublishedFile_GetUserFiles_Response.Builder> =
                withTimeoutOrNull(QUERY_TIMEOUT_MS) {
                    job.await()
                } ?: run {
                    WepWawetLogger.workshopWarn(
                        "$TAG: getUserFiles timed out — marking session for reconnect",
                        "timeout_ms" to QUERY_TIMEOUT_MS,
                    )
                    isAuthenticated = false
                    return@withContext Result.failure(
                        IllegalStateException("ISteamPublishedFile.GetUserFiles timed out after $QUERY_TIMEOUT_MS ms")
                    )
                }

            val body = response.body.build()
            val items = body.publishedfiledetailsList.map { mapPublishedFileDetails(it) }

            WepWawetLogger.workshop(
                "$TAG: getUserFiles OK",
                "total" to body.total,
                "returned" to items.size,
                "steam_id" to details.steamId.toString(),
                "page" to details.page,
            )
            Result.success(items)
        } catch (e: CancellationException) {
            // See [queryFiles] for the rationale — coroutine cancellation must
            // propagate, not be treated as a query failure.
            WepWawetLogger.workshopDebug(
                "$TAG: getUserFiles cancelled (coroutine scope disposed)",
                "exception" to e.javaClass.name,
            )
            throw e
        } catch (e: Exception) {
            WepWawetLogger.workshopWarn(
                "$TAG: getUserFiles failed",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""),
            )
            isAuthenticated = false
            Result.failure(e)
        }
    }

    private fun mapPublishedFileDetails(details: PublishedFileDetails): WorkshopItemTyped {
        val tags = details.tagsList.map { it.tag }
        val item = WorkshopItem(
            publishedfileid = details.publishedfileid.toString(),
            title = details.title,
            description = details.fileDescription,
            fileSize = details.fileSize,
            previewUrl = if (details.previewUrl.isNullOrBlank()) details.imageUrl else details.previewUrl,
            subscriptions = details.subscriptions.toLong(),
            favorited = details.favorited.toLong(),
            views = details.views.toLong(),
            lifetimeSubscriptions = details.lifetimeSubscriptions.toLong(),
            lifetimeFavorited = details.lifetimeFavorited.toLong(),
            tags = tags,
            timeCreated = details.timeCreated.toLong(),
            timeUpdated = details.timeUpdated.toLong(),
            creator = details.creator.toString(),
            creatorAppId = details.creatorAppid.toLong(),
            creatorSteamId = details.creator.toString(),
            visibility = details.visibility,
            banned = details.banned,
            banReason = details.banReason,
            banner = details.banner.toString(),
        )
        return WorkshopItemTyped(item, WallpaperType.fromTags(tags))
    }

    /**
     * Force-disconnect the session. Used when the user logs out of Steam in Settings,
     * or when the app is shutting down.
     */
    fun disconnect() {
        authLock.lock()
        try {
            try {
                downloader?.cleanup()
            } catch (e: Exception) {
                WepWawetLogger.workshopWarn("$TAG: cleanup failed", "exception" to e.javaClass.name)
            }
            downloader = null
            isAuthenticated = false
        } finally {
            authLock.unlock()
        }
    }
}
