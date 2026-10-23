package com.wepwawet.pairing

import android.content.Context
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import `in`.dragonbra.javasteam.depotdownloader.data.PubFileItem
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.AuthenticationException
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import `in`.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration

class WorkshopDownloader(
    private val username: String,
    private val password: String,
    private val guardCode: String? = null,
) {
    companion object {
        private const val TAG = "WorkshopDownloader"
        private const val APP_ID_WE = 431960
        private const val PREF_NAME = "wepwawet_steam_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_ACCOUNT_NAME = "account_name"

        /**
         * Test Steam login — connects, authenticates, saves refresh token.
         * Returns a result message (success or error).
         */
        suspend fun testLogin(context: Context): String = withContext(Dispatchers.IO) {
            val username = SteamCredentialsStore.getUsername(context)
                ?: return@withContext "No Steam username saved"
            val password = SteamCredentialsStore.getPassword(context)
                ?: return@withContext "No Steam password saved"
            val guardCode = SteamCredentialsStore.getGuardCode(context)
            WepWawetLogger.proxy("$TAG.testLogin START", "username" to username,
                "has_guard" to !guardCode.isNullOrBlank())

            val downloader = WorkshopDownloader(username, password, guardCode)
            downloader.statusCallback = { msg ->
                WepWawetLogger.proxy("$TAG.testLogin: $msg")
            }
            // Try to connect and authenticate
            val connected = downloader.connectAndAuthenticate(context)
            val result = if (connected) {
                WepWawetLogger.proxy("$TAG.testLogin SUCCESS")
                "✓ Login successful! Refresh token saved for future logins."
            } else {
                val err = downloader.downloadError?.message ?: "Unknown error"
                WepWawetLogger.proxyError("$TAG.testLogin FAILED", "error" to err)
                "✗ Login failed: $err"
            }
            downloader.cleanup()
            result
        }
    }

    private var steamClient: SteamClient? = null
    private var callbackManager: CallbackManager? = null
    private var steamUser: SteamUser? = null
    private var licenseList: List<License> = emptyList()
    @Volatile private var isRunning = false
    @Volatile private var downloadError: Exception? = null
    @Volatile private var authenticated = false
    @Volatile private var cmRetries = 0
    @Volatile private var authInProgress = false
    @Volatile private var isRetrying = false  // true during TryAnotherCM retry
    @Volatile private var usedRefreshToken = false  // true if we tried refresh token login
    @Volatile private var hasValidToken = false  // true after auth session succeeds (token saved)
    @Volatile private var appContext: Context? = null  // for clearing stale token on rate limit
    @Volatile private var statusCallback: ((String) -> Unit)? = null
    private val subscriptions = mutableListOf<Closeable>()

    private fun updateStatus(msg: String) {
        WepWawetLogger.proxy("$TAG: $msg")
        statusCallback?.invoke(msg)
    }

    fun isAuthenticated(): Boolean = authenticated

    private inner class GuardAuthenticator : IAuthenticator {
        override fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String> {
            WepWawetLogger.proxy("$TAG: Steam Guard 2FA code requested",
                "previous_incorrect" to previousCodeWasIncorrect,
                "has_code" to !guardCode.isNullOrBlank())
            val future = CompletableFuture<String>()
            if (previousCodeWasIncorrect) {
                future.completeExceptionally(
                    AuthenticationException("Steam Guard code was wrong or expired. Use device confirmation (approve on Steam mobile app) instead."))
            } else if (guardCode.isNullOrBlank()) {
                future.completeExceptionally(
                    AuthenticationException("Steam Guard 2FA code required but not provided."))
            } else {
                future.complete(guardCode)
            }
            return future
        }

        override fun getEmailCode(email: String?, previousCodeWasIncorrect: Boolean): CompletableFuture<String> {
            WepWawetLogger.proxy("$TAG: Steam email code requested", "email" to (email ?: ""))
            val future = CompletableFuture<String>()
            future.completeExceptionally(AuthenticationException("Email code auth not supported."))
            return future
        }

        override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> {
            WepWawetLogger.proxy("$TAG: device confirmation — auto-accepting (user approves on Steam mobile app)")
            updateStatus("Check your Steam mobile app — tap 'Approve' to authorize WepWawet (one-time only)")
            return CompletableFuture.completedFuture(true)
        }
    }

    suspend fun downloadWorkshopPkg(
        context: Context,
        workshopId: String,
        outputFile: File,
        onProgress: (Float) -> Unit,
        onStatusUpdate: (String) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        this@WorkshopDownloader.statusCallback = onStatusUpdate
        WepWawetLogger.proxy("$TAG START", "workshop_id" to workshopId, "username" to username,
            "has_guard_code" to !guardCode.isNullOrBlank())
        try {
            updateStatus("Connecting to Steam...")
            if (!connectAndAuthenticate(context)) {
                val err = downloadError?.message ?: "Unknown error"
                onStatusUpdate("Steam login failed: $err")
                return@withContext null
            }

            onStatusUpdate("Fetching Steam licenses...")
            if (!waitForLicenses(30000)) {
                onStatusUpdate("Timed out waiting for licenses")
                return@withContext null
            }
            WepWawetLogger.proxy("$TAG: got ${licenseList.size} licenses")

            onStatusUpdate("Downloading workshop item via Steam protocol...")
            val installDir = File(context.cacheDir, "workshop_downloads/$workshopId")
            installDir.mkdirs()
            WepWawetLogger.proxy("$TAG: install dir prepared",
                "path" to installDir.absolutePath,
                "exists" to installDir.exists())

            val downloader = DepotDownloader(steamClient!!, licenseList, false, false, 8, 4, 8, false, null)

            // Heartbeat state — updated by the listener, read by the heartbeat thread.
            // Use Atomic* so cross-thread visibility is guaranteed (Kotlin local vars
            // captured by closures are boxed in Ref objects, but writes from one
            // thread aren't guaranteed visible to another thread without a
            // happens-before edge — Atomic* gives us that).
            val lastStatus = java.util.concurrent.atomic.AtomicReference("(no status yet)")
            val lastProgressPct = java.util.concurrent.atomic.AtomicInteger(-1)
            val lastChunkBytes = java.util.concurrent.atomic.AtomicLong(0L)
            val filesCompleted = java.util.concurrent.atomic.AtomicInteger(0)
            val depotsCompleted = java.util.concurrent.atomic.AtomicInteger(0)
            val chunksCompleted = java.util.concurrent.atomic.AtomicInteger(0)
            val lastActivityMs = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
            val downloadStartMs = System.currentTimeMillis()

            val listener = object : IDownloadListener {
                override fun onItemAdded(item: DownloadItem) {
                    lastActivityMs.set(System.currentTimeMillis())
                    WepWawetLogger.proxy("$TAG: item added",
                        "app_id" to item.appId,
                        "install_dir" to item.installDirectory)
                }
                override fun onDownloadStarted(item: DownloadItem) {
                    lastActivityMs.set(System.currentTimeMillis())
                    lastStatus.set("Download started")
                    WepWawetLogger.proxy("$TAG: download started",
                        "app_id" to item.appId)
                    onStatusUpdate("Download started — fetching chunks from Steam CDN...")
                }
                override fun onDownloadCompleted(item: DownloadItem) {
                    lastActivityMs.set(System.currentTimeMillis())
                    WepWawetLogger.proxy("$TAG: download completed",
                        "app_id" to item.appId,
                        "elapsed_ms" to (System.currentTimeMillis() - downloadStartMs))
                    onProgress(1f)
                    onStatusUpdate("Download completed — finalizing...")
                }
                override fun onDownloadFailed(item: DownloadItem, error: Throwable) {
                    lastActivityMs.set(System.currentTimeMillis())
                    WepWawetLogger.proxyError("$TAG: download failed",
                        "app_id" to item.appId,
                        "exception" to error.javaClass.name,
                        "message" to (error.message ?: ""))
                    downloadError = java.lang.Exception(error.message ?: "download failed", error)
                }
                override fun onStatusUpdate(message: String) {
                    lastActivityMs.set(System.currentTimeMillis())
                    lastStatus.set(message)
                    // IMPORTANT: was proxyDebug (filtered out by min level = INFO).
                    // Now logged at INFO so the user can see what's happening.
                    WepWawetLogger.proxy("$TAG: depot status",
                        "msg" to message.take(200))
                    onStatusUpdate(message)
                }
                override fun onFileCompleted(depotId: Int, fileName: String, depotPercentComplete: Float) {
                    lastActivityMs.set(System.currentTimeMillis())
                    val pct = (depotPercentComplete * 100).toInt()
                    val done = filesCompleted.incrementAndGet()
                    WepWawetLogger.proxy("$TAG: file complete",
                        "depot" to depotId,
                        "file" to fileName.take(120),
                        "depot_pct" to pct,
                        "files_done" to done,
                        "elapsed_ms" to (System.currentTimeMillis() - downloadStartMs))
                    lastProgressPct.set(pct)
                    onProgress(depotPercentComplete)
                    onStatusUpdate("Downloading… $pct% (file #$done: ${fileName.take(40)})")
                }
                override fun onChunkCompleted(depotId: Int, depotPercentComplete: Float, compressedBytes: Long, uncompressedBytes: Long) {
                    lastActivityMs.set(System.currentTimeMillis())
                    val done = chunksCompleted.incrementAndGet()
                    val pct = (depotPercentComplete * 100).toInt()
                    lastProgressPct.set(pct)
                    lastChunkBytes.addAndGet(compressedBytes)
                    // Log every chunk — this is the only true progress signal.
                    WepWawetLogger.proxy("$TAG: chunk complete",
                        "depot" to depotId,
                        "chunk#" to done,
                        "depot_pct" to pct,
                        "compressed" to compressedBytes,
                        "uncompressed" to uncompressedBytes,
                        "elapsed_ms" to (System.currentTimeMillis() - downloadStartMs))
                    onProgress(depotPercentComplete)
                    onStatusUpdate("Downloading… $pct% ($done chunks, ${formatBytes(lastChunkBytes.get())} so far)")
                }
                override fun onDepotCompleted(depotId: Int, compressedBytes: Long, uncompressedBytes: Long) {
                    lastActivityMs.set(System.currentTimeMillis())
                    val done = depotsCompleted.incrementAndGet()
                    lastChunkBytes.addAndGet(compressedBytes)
                    WepWawetLogger.proxy("$TAG: depot done",
                        "depot" to depotId,
                        "compressed" to compressedBytes,
                        "uncompressed" to uncompressedBytes,
                        "depots_done" to done,
                        "elapsed_ms" to (System.currentTimeMillis() - downloadStartMs))
                    onStatusUpdate("Depot $depotId done: ${formatBytes(uncompressedBytes)} (depot #$done)")
                }
            }

            downloader.addListener(listener)

            val pubFileId = workshopId.toLongOrNull() ?: return@withContext null
            val pubItem = PubFileItem(APP_ID_WE, pubFileId)

            WepWawetLogger.proxy("$TAG: adding PubFileItem",
                "app_id" to APP_ID_WE, "pub_file_id" to pubFileId)
            downloader.add(pubItem)
            downloader.finishAdding()
            WepWawetLogger.proxy("$TAG: items queued, finishAdding done")

            onStatusUpdate("Downloading... this may take a few minutes for large wallpapers")

            // === Heartbeat thread: log download state every 5s so the user can
            //     see whether the download is actually progressing or stuck. ===
            val heartbeatThread = Thread({
                var tick = 0
                while (true) {
                    try { Thread.sleep(5000) } catch (_: InterruptedException) { return@Thread }
                    tick++
                    val now = System.currentTimeMillis()
                    val elapsedSec = (now - downloadStartMs) / 1000
                    val silentSec = (now - lastActivityMs.get()) / 1000
                    val status = lastStatus.get() ?: "(no status yet)"
                    WepWawetLogger.proxy("$TAG: heartbeat #$tick",
                        "elapsed_s" to elapsedSec,
                        "silent_s" to silentSec,
                        "last_status" to status.take(80),
                        "last_pct" to lastProgressPct.get(),
                        "files_done" to filesCompleted.get(),
                        "chunks_done" to chunksCompleted.get(),
                        "depots_done" to depotsCompleted.get(),
                        "chunk_bytes" to lastChunkBytes.get())
                    // Update UI status with elapsed time so the user sees movement
                    // even when no chunk callback has fired yet.
                    if (silentSec > 10) {
                        onStatusUpdate("$status (${elapsedSec}s elapsed, ${silentSec}s since last activity)")
                    } else {
                        onStatusUpdate("$status (${elapsedSec}s elapsed)")
                    }
                }
            }, "JavaSteam-Heartbeat").apply {
                isDaemon = true
                start()
            }

            // === Run awaitCompletion with a 10-minute hard timeout. ===
            // Previously this could hang forever if Steam stopped sending
            // chunks but didn't disconnect — the user would see "Download
            // started..." for hours with no further updates.
            val completionTimeoutMs = 10L * 60 * 1000  // 10 minutes
            val completionStart = System.currentTimeMillis()
            var completionException: Exception? = null
            try {
                val completionJob = Thread {
                    try {
                        runBlocking { downloader.awaitCompletion() }
                    } catch (e: Exception) {
                        completionException = e
                    }
                }.apply { isDaemon = true; name = "JavaSteam-DepotCompletion"; start() }

                // Wait for either completion or timeout
                while (completionJob.isAlive &&
                       System.currentTimeMillis() - completionStart < completionTimeoutMs) {
                    if (downloadError != null) {
                        WepWawetLogger.proxyWarn("$TAG: download error detected — breaking wait",
                            "error" to (downloadError?.message ?: ""))
                        break
                    }
                    try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                }
                if (completionJob.isAlive) {
                    val elapsedSec = (System.currentTimeMillis() - completionStart) / 1000
                    val statusAtTimeout = lastStatus.get() ?: "(no status yet)"
                    WepWawetLogger.proxyError("$TAG: awaitCompletion TIMED OUT",
                        "timeout_s" to (completionTimeoutMs / 1000),
                        "elapsed_s" to elapsedSec,
                        "last_status" to statusAtTimeout,
                        "files_done" to filesCompleted.get(),
                        "chunks_done" to chunksCompleted.get(),
                        "depots_done" to depotsCompleted.get())
                    // Interrupt the completion thread (best-effort)
                    completionJob.interrupt()
                    downloadError = java.lang.Exception("Download timed out after ${elapsedSec}s — Steam CDN may be slow or stuck. Last status: $statusAtTimeout")
                } else if (completionException != null) {
                    WepWawetLogger.proxyWarn("$TAG: awaitCompletion threw",
                        "exception" to (completionException?.javaClass?.name ?: ""),
                        "message" to (completionException?.message ?: ""))
                    if (downloadError == null) {
                        downloadError = completionException
                    }
                } else {
                    WepWawetLogger.proxy("$TAG: awaitCompletion returned normally",
                        "elapsed_ms" to (System.currentTimeMillis() - completionStart),
                        "files_done" to filesCompleted.get(),
                        "chunks_done" to chunksCompleted.get(),
                        "depots_done" to depotsCompleted.get())
                }
            } catch (e: Exception) {
                WepWawetLogger.proxyWarn("$TAG: awaitCompletion wrapper exception",
                    "exception" to e.javaClass.name, "message" to (e.message ?: ""))
                if (downloadError == null) {
                    downloadError = e
                }
            }

            // Stop the heartbeat thread
            heartbeatThread.interrupt()

            downloader.removeListener(listener)
            try { downloader.close() } catch (_: Exception) {}
            WepWawetLogger.proxy("$TAG: downloader closed",
                "files_done" to filesCompleted.get(),
                "chunks_done" to chunksCompleted.get(),
                "depots_done" to depotsCompleted.get(),
                "chunk_bytes" to lastChunkBytes.get(),
                "total_elapsed_ms" to (System.currentTimeMillis() - downloadStartMs))

            if (downloadError != null) {
                onStatusUpdate("Download failed: ${downloadError!!.message}")
                return@withContext null
            }

            onStatusUpdate("Locating downloaded .pkg file...")
            WepWawetLogger.proxy("$TAG: scanning install dir for .pkg",
                "dir" to installDir.absolutePath)
            val pkgFile = findPkgFile(installDir)
            if (pkgFile == null || !pkgFile.exists()) {
                // List directory contents so we can see what *was* downloaded
                val listing = installDir.walkTopDown().toList().take(20).joinToString("\n  ") { f ->
                    "${if (f.isDirectory) "[DIR] " else ""}${f.name} (${f.length()} B)"
                }
                WepWawetLogger.proxyWarn("$TAG: no .pkg found",
                    "dir" to installDir.absolutePath,
                    "dir_listing" to listing)
                onStatusUpdate("No .pkg file found after download (see logs for directory listing)")
                return@withContext null
            }
            WepWawetLogger.proxy("$TAG: .pkg found",
                "file" to pkgFile.absolutePath,
                "size" to pkgFile.length(),
                "files_done" to filesCompleted.get(),
                "chunks_done" to chunksCompleted.get(),
                "depots_done" to depotsCompleted.get())

            pkgFile.copyTo(outputFile, overwrite = true)
            WepWawetLogger.proxy("$TAG COMPLETE",
                "output" to outputFile.name,
                "size" to outputFile.length(),
                "total_elapsed_ms" to (System.currentTimeMillis() - downloadStartMs))
            onStatusUpdate("Downloaded ${formatBytes(outputFile.length())} .pkg")
            try { installDir.deleteRecursively() } catch (_: Exception) {}
            outputFile
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG: exception", "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            onStatusUpdate("Error: ${e.message}")
            null
        } finally {
            cleanup()
        }
    }

    /**
     * Connect to Steam and authenticate. Can be called standalone for testLogin.
     */
    fun connectAndAuthenticate(context: Context): Boolean {
        val dispatcher = okhttp3.Dispatcher(Executors.newCachedThreadPool { r ->
            Thread(r, "JavaSteam-Http").apply { isDaemon = true }
        })
        val httpClient = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        appContext = context
        steamClient = SteamClient(SteamConfiguration.create { it.withHttpClient(httpClient) })
        callbackManager = CallbackManager(steamClient!!)
        steamUser = steamClient!!.getHandler(SteamUser::class.java)

        subscriptions.add(callbackManager!!.subscribe(ConnectedCallback::class.java) { onConnected(it, context) })
        subscriptions.add(callbackManager!!.subscribe(DisconnectedCallback::class.java) { onDisconnected(it) })
        subscriptions.add(callbackManager!!.subscribe(LoggedOnCallback::class.java) { onLoggedOn(it) })
        subscriptions.add(callbackManager!!.subscribe(LoggedOffCallback::class.java) { onLoggedOff(it) })
        subscriptions.add(callbackManager!!.subscribe(LicenseListCallback::class.java) { onLicenseList(it) })

        isRunning = true
        cmRetries = 0
        WepWawetLogger.proxy("$TAG: connecting to Steam...")
        steamClient!!.connect()

        Thread({
            while (isRunning) {
                try { callbackManager!!.runWaitCallbacks(1000L) } catch (_: Exception) { break }
            }
        }, "JavaSteam-Callback").start()

        val timeout = 120000L
        val start = System.currentTimeMillis()
        while (isRunning && System.currentTimeMillis() - start < timeout) {
            if (authenticated) return true
            if (downloadError != null) return false
            try { Thread.sleep(500) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    /**
     * Clean up — disconnect from Steam, close subscriptions.
     */
    fun cleanup() {
        try { steamUser?.logOff() } catch (_: Exception) {}
        try { steamClient?.disconnect() } catch (_: Exception) {}
        subscriptions.forEach { try { it.close() } catch (_: Exception) {} }
        subscriptions.clear()
        isRunning = false
    }

    private fun onConnected(callback: ConnectedCallback, context: Context) {
        WepWawetLogger.proxy("$TAG: connected — authenticating as $username")
        updateStatus("Connected to Steam — authenticating...")

        // Don't start a new auth session if one is already in progress (e.g. during TryAnotherCM retry)
        if (authInProgress) {
            WepWawetLogger.proxyWarn("$TAG: auth already in progress, skipping")
            return
        }
        authInProgress = true
        isRetrying = false  // clear retry flag on new connection

        // Step 1: Try saved refresh token first (no 2FA needed)
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedToken = prefs.getString(KEY_REFRESH_TOKEN, null)
        val savedAccountName = prefs.getString(KEY_ACCOUNT_NAME, null)
        WepWawetLogger.proxy("$TAG: checking for saved refresh token",
            "has_token" to !savedToken.isNullOrBlank(),
            "has_account_name" to !savedAccountName.isNullOrBlank(),
            "token_length" to (savedToken?.length ?: 0),
            "account_name" to (savedAccountName ?: "null"))
        if (!savedToken.isNullOrBlank() && !savedAccountName.isNullOrBlank()) {
            WepWawetLogger.proxy("$TAG: trying saved refresh token",
                "account_name" to savedAccountName,
                "user_entered" to username)
            try {
                val details = LogOnDetails()
                details.username = savedAccountName  // Use Steam's account name (case-correct)
                details.accessToken = savedToken
                details.loginID = 149
                usedRefreshToken = true
                steamUser!!.logOn(details)
                return  // Wait for onLoggedOn callback — if it fails, we'll fall back
            } catch (e: Exception) {
                WepWawetLogger.proxyWarn("$TAG: refresh token logOn call failed",
                    "exception" to e.javaClass.name)
                usedRefreshToken = false
            }
        }

        // Step 2: Use modern auth flow (supports 2FA)
        try {
            WepWawetLogger.proxy("$TAG: starting modern auth session",
                "has_guard_code" to !guardCode.isNullOrBlank())
            val authDetails = AuthSessionDetails()
            authDetails.username = username
            authDetails.password = password
            authDetails.deviceFriendlyName = "WepWawet"
            authDetails.persistentSession = true
            authDetails.authenticator = GuardAuthenticator()

            val authSessionFuture = steamClient!!.authentication.beginAuthSessionViaCredentials(authDetails)

            Thread {
                try {
                    WepWawetLogger.proxy("$TAG: waiting for auth session...")
                    updateStatus("Waiting for Steam auth session...")
                    val authSession = authSessionFuture.get()

                    WepWawetLogger.proxy("$TAG: polling auth session (may request 2FA)...")
                    updateStatus("Authenticating with Steam (using 2FA code if needed)...")
                    val result = authSession.pollingWaitForResult().get()

                    WepWawetLogger.proxy("$TAG: auth SUCCESS",
                        "account" to result.accountName,
                        "has_refresh_token" to !result.refreshToken.isNullOrBlank())

                    // Save refresh token + account name for future logins (survives app updates)
                    if (!result.refreshToken.isNullOrBlank()) {
                        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putString(KEY_REFRESH_TOKEN, result.refreshToken)
                            .putString(KEY_ACCOUNT_NAME, result.accountName)
                            .apply()
                        hasValidToken = true
                        WepWawetLogger.proxy("$TAG: refresh token + account name saved ✓ (survives app updates)",
                            "account_name" to result.accountName)
                    }

                    val details = LogOnDetails()
                    details.username = result.accountName
                    details.accessToken = result.refreshToken
                    details.loginID = 149
                    steamUser!!.logOn(details)
                } catch (e: Exception) {
                    // Don't fail if we're retrying (TryAnotherCM causes CancellationException)
                    if (isRetrying) {
                        WepWawetLogger.proxyWarn("$TAG: auth polling cancelled during retry (expected)",
                            "exception" to e.javaClass.name)
                    } else {
                        WepWawetLogger.proxyError("$TAG: auth polling failed",
                            "exception" to e.javaClass.name,
                            "message" to (e.message ?: ""))
                        downloadError = e
                    }
                    authInProgress = false
                }
            }.start()
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG: failed to start auth session",
                "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            downloadError = e
        }
    }

    private fun onLoggedOn(callback: LoggedOnCallback) {
        if (callback.result != EResult.OK) {
            WepWawetLogger.proxyError("$TAG: logon failed", "result" to callback.result,
                "used_refresh_token" to usedRefreshToken,
                "has_valid_token" to hasValidToken)
            if (callback.result == EResult.TryAnotherCM && cmRetries < 5) {
                cmRetries++
                isRetrying = true
                authInProgress = false
                WepWawetLogger.proxy("$TAG: TryAnotherCM — retry #$cmRetries with different CM server...")
                updateStatus("Switching to different Steam server (retry $cmRetries)...")
                try { Thread.sleep(3000); steamClient?.connect() } catch (_: Exception) {}
                return
            }
            // If refresh token failed (e.g. InvalidPassword due to expired token),
            // fall back to the modern auth flow (push notification)
            if (usedRefreshToken && callback.result == EResult.InvalidPassword) {
                WepWawetLogger.proxy("$TAG: refresh token invalid — falling back to modern auth (push notification)")
                usedRefreshToken = false
                authInProgress = false
                // Clear the stale refresh token
                appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    ?.edit()?.remove(KEY_REFRESH_TOKEN)?.remove(KEY_ACCOUNT_NAME)?.apply()
                WepWawetLogger.proxy("$TAG: stale refresh token cleared")
                // Reconnect — onConnected will start modern auth flow
                try {
                    updateStatus("Refresh token expired — reconnecting for fresh login...")
                    steamClient?.disconnect()
                    Thread.sleep(2000)
                    steamClient?.connect()
                } catch (_: Exception) {}
                return
            }
            // Clear stale token on RateLimitExceeded
            if (callback.result == EResult.RateLimitExceeded) {
                appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    ?.edit()?.remove(KEY_REFRESH_TOKEN)?.remove(KEY_ACCOUNT_NAME)?.apply()
            }
            // Provide user-friendly error messages
            val friendlyError = when (callback.result) {
                EResult.InvalidPassword -> "Invalid password (or 2FA code needed/expired)"
                EResult.AccountLogonDenied -> "Steam Guard email code required"
                EResult.TwoFactorCodeMismatch -> "Steam Guard 2FA code is wrong or expired"
                EResult.AccountDisabled -> "Steam account is disabled"
                EResult.RateLimitExceeded -> "Steam rate limit exceeded — wait 30 minutes and try again"
                else -> "Steam logon failed: ${callback.result}"
            }
            downloadError = java.lang.Exception(friendlyError)
            updateStatus("Login failed: $friendlyError")
            isRunning = false
            return
        }
        authenticated = true
        authInProgress = false
        usedRefreshToken = false
        WepWawetLogger.proxy("$TAG: logged on to Steam ✓")
        updateStatus("Logged in to Steam ✓ — fetching licenses...")
    }

    private fun onLoggedOff(callback: LoggedOffCallback) {
        WepWawetLogger.proxy("$TAG: logged off", "result" to callback.result)
        isRunning = false
    }

    private fun onDisconnected(callback: DisconnectedCallback) {
        WepWawetLogger.proxy("$TAG: disconnected", "user_initiated" to callback.isUserInitiated)
        if (!callback.isUserInitiated && authenticated) {
            try { Thread.sleep(2000); steamClient?.connect() } catch (_: Exception) {}
        } else if (!callback.isUserInitiated && !authenticated) {
            // Reconnect during initial connection attempt
            try { Thread.sleep(2000); steamClient?.connect() } catch (_: Exception) {}
        } else {
            isRunning = false
        }
    }

    private fun onLicenseList(callback: LicenseListCallback) {
        if (callback.result != EResult.OK) return
        licenseList = callback.licenseList
        WepWawetLogger.proxy("$TAG: got licenses", "count" to licenseList.size)
    }

    private fun waitForLicenses(timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (licenseList.isNotEmpty()) return true
            if (downloadError != null) return false
            try { Thread.sleep(200) } catch (_: InterruptedException) { return false }
        }
        return licenseList.isNotEmpty()
    }

    private fun findPkgFile(searchDir: File): File? {
        if (!searchDir.exists()) return null
        searchDir.walkTopDown().forEach { file ->
            if (file.isFile && file.length() > 1024) {
                try {
                    val raf = java.io.RandomAccessFile(file, "r")
                    val header = ByteArray(12)
                    raf.readFully(header)
                    raf.close()
                    if (header[0] == 0x08.toByte() && header[4] == 'P'.code.toByte() &&
                        header[5] == 'K'.code.toByte() && header[6] == 'G'.code.toByte() &&
                        header[7] == 'V'.code.toByte()) return file
                } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
