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
import java.util.concurrent.atomic.AtomicBoolean

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
import `in`.dragonbra.javasteam.util.log.LogListener
import `in`.dragonbra.javasteam.util.log.LogManager

/**
 * Bridge that forwards JavaSteam library log messages into our [WepWawetLogger]
 * so they show up in the in-app Logs screen + log dumps.
 *
 * Without this, the DepotDownloader's internal debug logs (RPC requests,
 * manifest fetches, chunk downloads, errors) are invisible — making it
 * impossible to diagnose why a download hangs at "Download started".
 *
 * Installed once globally (since JavaSteam's LogManager is a singleton).
 */
private object JavaSteamLogBridge : LogListener {
    private val installed = AtomicBoolean(false)
    fun installIfNeeded() {
        if (installed.compareAndSet(false, true)) {
            LogManager.addListener(this)
            WepWawetLogger.proxy("JavaSteamLogBridge installed — forwarding library logs to WepWawetLogger")
        }
    }
    override fun onLog(clazz: Class<*>, message: String?, throwable: Throwable?) {
        // JavaSteam uses onLog for INFO-level messages
        WepWawetLogger.proxy("JavaSteam: ${clazz.simpleName}",
            "msg" to (message?.take(200) ?: ""),
            "level" to "INFO",
            "throwable" to (throwable?.javaClass?.name))
    }
    override fun onError(clazz: Class<*>, message: String?, throwable: Throwable?) {
        WepWawetLogger.proxyError("JavaSteam ERROR: ${clazz.simpleName}",
            "msg" to (message?.take(200) ?: ""),
            "exception" to (throwable?.javaClass?.name ?: "null"))
    }
}

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
    @Volatile private var hasTriedModernAuthFallback = false  // true if we already fell back to modern auth once (prevents infinite loop)
    @Volatile private var appContext: Context? = null  // for clearing stale token on rate limit
    @Volatile private var statusCallback: ((String) -> Unit)? = null
    @Volatile private var isDownloadInProgress = false  // true during DepotDownloader.awaitCompletion — used by onDisconnected to decide whether to auto-reconnect
    @Volatile private var isReconnectingForFallback = false  // true when our modern-auth fallback called steamClient.connect() — prevents onDisconnected(user_initiated=true) from killing the callback thread
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
            val mkdirsOk = installDir.mkdirs()
            WepWawetLogger.proxy("$TAG: install dir prepared",
                "path" to installDir.absolutePath,
                "exists" to installDir.exists(),
                "mkdirs_returned" to mkdirsOk,
                "is_directory" to installDir.isDirectory,
                "can_write" to installDir.canWrite(),
                "cache_dir" to context.cacheDir.absolutePath,
                "parent_exists" to (installDir.parentFile?.exists() ?: false))

            WepWawetLogger.proxy("$TAG: constructing DepotDownloader",
                "steam_client_connected" to (steamClient?.isConnected ?: false),
                "licenses_count" to licenseList.size,
                "install_dir" to installDir.absolutePath)
            val downloader = DepotDownloader(steamClient!!, licenseList, false, false, 8, 4, 8, false, null)
            WepWawetLogger.proxy("$TAG: DepotDownloader constructed",
                "downloader_class" to downloader.javaClass.name)

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
            WepWawetLogger.proxy("$TAG: listener registered",
                "listener_class" to listener.javaClass.name,
                "downloader_class" to downloader.javaClass.name)

            val pubFileId = workshopId.toLongOrNull() ?: run {
                WepWawetLogger.proxyError("$TAG: invalid workshop id (not a long)",
                    "input" to workshopId)
                return@withContext null
            }
            // === CRITICAL: pass installDirectory to PubFileItem ===
            // The 2-arg constructor PubFileItem(appId, pubFileId) leaves installDirectory=null,
            // which causes the DepotDownloader to fall back to "depots/" relative to the
            // JVM's current working directory — NOT our prepared installDir. So the file
            // watcher scans our installDir and sees 0 files, even though the DepotDownloader
            // might be writing to a totally different location. The 4-arg constructor
            // PubFileItem(appId, pubFile, installToGameNameDirectory=false, installDirectory=...)
            // lets us tell the DepotDownloader EXACTLY where to write.
            val pubItem = PubFileItem(APP_ID_WE, pubFileId, false, installDir.absolutePath)

            WepWawetLogger.proxy("$TAG: adding PubFileItem",
                "app_id" to APP_ID_WE,
                "pub_file_id" to pubFileId,
                "install_dir" to installDir.absolutePath,
                "pub_item_install_dir" to pubItem.installDirectory,
                "install_dir_exists" to installDir.exists(),
                "install_dir_writable" to installDir.canWrite(),
                "licenses_count" to licenseList.size,
                "steam_client_connected" to (steamClient?.isConnected ?: false),
                "steam_client_class" to (steamClient?.javaClass?.name ?: "null"))
            downloader.add(pubItem)
            WepWawetLogger.proxy("$TAG: PubFileItem added — calling finishAdding")
            downloader.finishAdding()
            WepWawetLogger.proxy("$TAG: items queued, finishAdding done")

            onStatusUpdate("Downloading... this may take a few minutes for large wallpapers")

            // === Heartbeat thread: log download state every 3s (was 5s) so the
            //     user can see whether the download is actually progressing or stuck. ===
            val heartbeatThread = Thread({
                var tick = 0
                while (true) {
                    try { Thread.sleep(3000) } catch (_: InterruptedException) { return@Thread }
                    tick++
                    try {
                        val now = System.currentTimeMillis()
                        val elapsedSec = (now - downloadStartMs) / 1000
                        val silentSec = (now - lastActivityMs.get()) / 1000
                        val status = lastStatus.get() ?: "(no status yet)"
                        // Check steam client state for diagnostics
                        val steamConnected = steamClient?.isConnected ?: false
                        // Scan install dir for current file count and total bytes
                        var fileCount = 0
                        var totalBytes = 0L
                        var largestFile = ""
                        var largestSize = 0L
                        try {
                            installDir.walkTopDown().forEach { f ->
                                if (f.isFile) {
                                    fileCount++
                                    totalBytes += f.length()
                                    if (f.length() > largestSize) {
                                        largestSize = f.length()
                                        largestFile = f.name
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                        WepWawetLogger.proxy("$TAG: heartbeat #$tick",
                            "elapsed_s" to elapsedSec,
                            "silent_s" to silentSec,
                            "last_status" to status.take(80),
                            "last_pct" to lastProgressPct.get(),
                            "files_done" to filesCompleted.get(),
                            "chunks_done" to chunksCompleted.get(),
                            "depots_done" to depotsCompleted.get(),
                            "chunk_bytes" to lastChunkBytes.get(),
                            "steam_connected" to steamConnected,
                            "dir_file_count" to fileCount,
                            "dir_total_bytes" to totalBytes,
                            "dir_largest_file" to largestFile.take(60),
                            "dir_largest_size" to largestSize)
                        // Update UI status with elapsed time so the user sees movement
                        // even when no chunk callback has fired yet.
                        if (silentSec > 5) {
                            onStatusUpdate("$status (${elapsedSec}s elapsed, ${silentSec}s since last activity, $fileCount files in cache, ${formatBytes(totalBytes)} total)")
                        } else {
                            onStatusUpdate("$status (${elapsedSec}s elapsed, $fileCount files in cache, ${formatBytes(totalBytes)} total)")
                        }
                        // === Stuck-detection: if we've been silent for 60s+ AND
                        // no files have been written to installDir, the download is
                        // almost certainly dead (Steam disconnected, DepotDownloader
                        // is hung on a dead connection, etc.). Set downloadError so
                        // the awaitCompletion wrapper breaks out and the user gets
                        // a clear "stuck" message instead of waiting the full 10min. ===
                        if (silentSec >= 60 && fileCount == 0 && downloadError == null) {
                            WepWawetLogger.proxyError("$TAG: stuck-detection — aborting download",
                                "silent_s" to silentSec,
                                "dir_file_count" to fileCount,
                                "steam_connected" to steamConnected,
                                "last_status" to status.take(80))
                            downloadError = java.lang.Exception(
                                "Download stuck — no activity for ${silentSec}s and no files written. " +
                                "Steam may have disconnected mid-download (last status: $status, " +
                                "steam_connected=$steamConnected). " +
                                "Try again — the next attempt should be cleaner now that we don't auto-reconnect during downloads.")
                            // Also try to nudge the DepotDownloader's awaitCompletion
                            // by interrupting its thread (best-effort; if the library
                            // doesn't respond to interrupts, the 10min timeout will
                            // still fire).
                        }
                    } catch (he: Throwable) {
                        // Never let the heartbeat thread die silently — that
                        // would hide the fact that the download is stuck.
                        WepWawetLogger.proxyError("$TAG: heartbeat exception",
                            "exception" to he.javaClass.name,
                            "message" to (he.message ?: ""))
                    }
                }
            }, "JavaSteam-Heartbeat").apply {
                isDaemon = true
                start()
            }
            WepWawetLogger.proxy("$TAG: heartbeat thread started",
                "interval_ms" to 3000)

            // === File-watcher thread: scans installDir every 2s for NEW files
            //     and logs them. This catches cases where the depotdownloader
            //     writes files but doesn't fire onFileCompleted (e.g. for very
            //     small workshop items that arrive in a single chunk). ===
            val seenFiles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val fileWatcherThread = Thread({
                var tick = 0
                while (true) {
                    try { Thread.sleep(2000) } catch (_: InterruptedException) { return@Thread }
                    try {
                        tick++
                        val currentFiles = mutableListOf<File>()
                        try {
                            installDir.walkTopDown().forEach { f ->
                                if (f.isFile) currentFiles.add(f)
                            }
                        } catch (_: Exception) {}
                        for (f in currentFiles) {
                            val key = f.absolutePath
                            if (seenFiles.add(key)) {
                                WepWawetLogger.proxy("$TAG: file-watcher NEW file",
                                    "tick" to tick,
                                    "file" to f.name.take(120),
                                    "size" to f.length(),
                                    "path" to f.absolutePath.take(200),
                                    "parent" to f.parentFile?.name)
                            } else {
                                // Existing file — log if it grew significantly (>=10%)
                                // Useful to see chunks being appended
                            }
                        }
                    } catch (fe: Throwable) {
                        WepWawetLogger.proxyError("$TAG: file-watcher exception",
                            "exception" to fe.javaClass.name,
                            "message" to (fe.message ?: ""))
                    }
                }
            }, "JavaSteam-FileWatcher").apply {
                isDaemon = true
                start()
            }
            WepWawetLogger.proxy("$TAG: file-watcher thread started",
                "interval_ms" to 2000,
                "install_dir" to installDir.absolutePath)

            // === Mark download as in-progress so onDisconnected doesn't auto-reconnect ===
            // (which would interfere with the DepotDownloader's state — see comment in
            // onDisconnected). Cleared in the finally block after awaitCompletion returns.
            isDownloadInProgress = true

            // === Run awaitCompletion with a 10-minute hard timeout. ===
            // Previously this could hang forever if Steam stopped sending
            // chunks but didn't disconnect — the user would see "Download
            // started..." for hours with no further updates.
            val completionTimeoutMs = 10L * 60 * 1000  // 10 minutes
            val completionStart = System.currentTimeMillis()
            var completionException: Exception? = null
            WepWawetLogger.proxy("$TAG: calling awaitCompletion (with ${completionTimeoutMs / 1000}s timeout)",
                "thread" to Thread.currentThread().name,
                "downloader_class" to downloader.javaClass.name)
            try {
                val completionJob = Thread {
                    WepWawetLogger.proxy("$TAG: awaitCompletion thread ENTERED",
                        "thread" to Thread.currentThread().name)
                    try {
                        runBlocking { downloader.awaitCompletion() }
                        WepWawetLogger.proxy("$TAG: awaitCompletion thread RETURNED NORMALLY",
                            "thread" to Thread.currentThread().name)
                    } catch (e: Exception) {
                        WepWawetLogger.proxyError("$TAG: awaitCompletion thread THREW",
                            "thread" to Thread.currentThread().name,
                            "exception" to e.javaClass.name,
                            "message" to (e.message ?: ""))
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

            // Stop the heartbeat + file-watcher threads
            heartbeatThread.interrupt()
            fileWatcherThread.interrupt()
            WepWawetLogger.proxy("$TAG: heartbeat + file-watcher threads interrupted")

            // Final scan: list every file in installDir so we know what's there
            // before we close the downloader. This is critical for debugging
            // cases where the .pkg lookup fails — we'll have a snapshot of
            // exactly what files exist on disk at this point.
            try {
                val allFiles = installDir.walkTopDown().toList()
                val fileSummary = allFiles.map { f ->
                    val type = if (f.isDirectory) "[D]" else "[F ${f.length()}B]"
                    "$type ${f.absolutePath.replace(installDir.absolutePath, "<root>")}"
                }
                WepWawetLogger.proxy("$TAG: FINAL install dir snapshot",
                    "dir" to installDir.absolutePath,
                    "total_entries" to allFiles.size,
                    "listing" to fileSummary.joinToString("\n  ").take(2000))
            } catch (e: Exception) {
                WepWawetLogger.proxyWarn("$TAG: failed to list install dir for final snapshot",
                    "exception" to e.javaClass.name)
            }

            downloader.removeListener(listener)
            WepWawetLogger.proxy("$TAG: listener removed")
            try { downloader.close() } catch (_: Exception) {}
            // === Clear the download-in-progress flag — onDisconnected can
            // auto-reconnect again now that the DepotDownloader is done. ===
            isDownloadInProgress = false
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
        // === Install the JavaSteam library log bridge ONCE globally so we can
        // see what the library is doing internally (RPC requests, callback
        // dispatch, errors). Without this, the DepotDownloader's logs are
        // invisible — making it impossible to diagnose why a download hangs. ===
        JavaSteamLogBridge.installIfNeeded()

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
        // Reset the modern-auth fallback flag so a new session can fall back again
        hasTriedModernAuthFallback = false
        WepWawetLogger.proxy("$TAG: connecting to Steam...")
        steamClient!!.connect()

        // === Callback manager thread ===
        // Runs runWaitCallbacks(1000) in a loop while isRunning is true.
        // The JavaSteam library's CoroutineScope (used by DepotDownloader) suspends
        // on AsyncJobSingle.await() — this only resumes when the corresponding
        // callback is dispatched through the callback manager. If this thread dies
        // (e.g., due to an unhandled exception), every async RPC call hangs forever.
        //
        // Previously, the catch(_:Exception){break} silently killed the thread —
        // leaving no log evidence that the thread had died. Now we log every
        // exception and continue the loop (don't break) so a transient failure
        // doesn't permanently disable RPC dispatch.
        Thread({
            var tick = 0
            var lastException: String? = null
            while (isRunning) {
                try {
                    callbackManager!!.runWaitCallbacks(1000L)
                } catch (e: InterruptedException) {
                    WepWawetLogger.proxy("$TAG: callback thread interrupted, exiting")
                    return@Thread
                } catch (e: Exception) {
                    // Log the exception ONCE per unique message to avoid spam
                    val excName = e.javaClass.name
                    if (excName != lastException) {
                        lastException = excName
                        WepWawetLogger.proxyError("$TAG: callback thread exception (continuing)",
                            "exception" to excName,
                            "message" to (e.message ?: "").take(200))
                    }
                }
                // Heartbeat every 10 iterations (~10s) so we can verify
                // the thread is alive in the logs
                tick++
                if (tick % 10 == 0) {
                    WepWawetLogger.proxy("$TAG: callback thread heartbeat",
                        "tick" to tick,
                        "is_running" to isRunning,
                        "authenticated" to authenticated,
                        "steam_connected" to (steamClient?.isConnected ?: false))
                }
            }
            WepWawetLogger.proxy("$TAG: callback thread EXITED (isRunning=false)")
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

        // === Clear the fallback-reconnect flag — the new connection has been
        // established, so the previous disconnect (from the connect() call
        // inside our modern-auth fallback) is no longer relevant. The callback
        // thread is preserved and will process this onConnected. ===
        if (isReconnectingForFallback) {
            isReconnectingForFallback = false
            WepWawetLogger.proxy("$TAG: isReconnectingForFallback cleared (new connection established)")
        }

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
            // If refresh token failed for any token-related reason (Steam backend
            // changes mean refresh tokens for SteamClient platform are now
            // authenticated — so tokens that previously worked can suddenly start
            // returning AccessDenied, Expired, Revoked, etc.), fall back to the
            // modern auth flow (push notification). This is one-shot — if modern
            // auth ALSO fails, we don't loop. (hasTriedModernAuthFallback guards.)
            val isTokenRelatedFailure = callback.result == EResult.InvalidPassword ||
                callback.result == EResult.AccessDenied ||
                callback.result == EResult.Expired ||
                callback.result == EResult.Revoked ||
                callback.result == EResult.LogonSessionReplaced ||
                callback.result == EResult.AlreadyLoggedInElsewhere ||
                callback.result == EResult.PasswordRequiredToKickSession
            if (usedRefreshToken && isTokenRelatedFailure && !hasTriedModernAuthFallback) {
                hasTriedModernAuthFallback = true
                WepWawetLogger.proxy("$TAG: refresh token rejected (result=${callback.result}) — falling back to modern auth (push notification)",
                    "result" to callback.result)
                usedRefreshToken = false
                authInProgress = false
                // Clear the stale refresh token so we don't try it again next time
                appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    ?.edit()?.remove(KEY_REFRESH_TOKEN)?.remove(KEY_ACCOUNT_NAME)?.apply()
                WepWawetLogger.proxy("$TAG: stale refresh token cleared (was rejected with ${callback.result})")
                // Reconnect — onConnected will see no saved token and start the
                // modern auth flow (push notification to Steam mobile app).
                //
                // === CRITICAL FIX: set isReconnectingForFallback=true so that
                // when steamClient.connect() INTERNALLY calls disconnect() (which
                // it does when already connected), the resulting
                // onDisconnected(user_initiated=true) callback does NOT set
                // isRunning=false and kill our callback thread.
                //
                // Previously: just call steamClient.connect() — but connect()
                // internally calls disconnect() first, which triggers
                // onDisconnected(user_initiated=true) which sets isRunning=false
                // → kills the callback thread → the new connect()'s
                // onConnected callback never gets processed → user sees
                // "Steam login failed: Unknown error".
                //
                // The flag is cleared in onConnected (after the new connection
                // is established) and is one-shot per session.
                try {
                    updateStatus("Refresh token rejected (${callback.result}) — reconnecting for fresh push-notification login...")
                    isReconnectingForFallback = true
                    WepWawetLogger.proxy("$TAG: calling steamClient.connect() for fallback (isReconnectingForFallback=true to preserve callback thread)")
                    steamClient?.connect()
                } catch (e: Exception) {
                    isReconnectingForFallback = false
                    WepWawetLogger.proxyError("$TAG: fallback connect() threw",
                        "exception" to e.javaClass.name,
                        "message" to (e.message ?: ""))
                }
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
        WepWawetLogger.proxy("$TAG: disconnected",
            "user_initiated" to callback.isUserInitiated,
            "authenticated" to authenticated,
            "download_in_progress" to isDownloadInProgress,
            "is_reconnecting_for_fallback" to isReconnectingForFallback)
        // === CRITICAL FIX: when our modern-auth fallback called connect()
        // (which INTERNALLY calls disconnect() first when already connected),
        // the resulting onDisconnected(user_initiated=true) would normally
        // set isRunning=false → kill the callback thread → break the
        // subsequent connect()'s onConnected callback.
        //
        // The isReconnectingForFallback flag (set in onLoggedOn before
        // calling connect()) prevents this: when set, we DON'T set
        // isRunning=false, just wait for the new onConnected to fire.
        // Cleared in onConnected.
        if (isReconnectingForFallback) {
            WepWawetLogger.proxy("$TAG: disconnect from fallback's connect() — keeping callback thread alive (isReconnectingForFallback=true)")
            // Don't set isRunning=false; the new connection will fire onConnected
            // which clears the flag and continues the modern auth flow.
            // Don't auto-reconnect either — connect() was already called.
            return
        }
        // === CRITICAL: do NOT auto-reconnect while a download is in progress. ===
        // The DepotDownloader holds a reference to steamClient and is making API
        // calls through it. If we reconnect (creating a new internal session),
        // the DepotDownloader's state becomes inconsistent with the new connection
        // — its network calls hang forever waiting for chunks that will never
        // arrive.
        //
        // Also: if the disconnect is NOT user-initiated during a download
        // (e.g., the WebSocket "Watchdog: No response for 30 seconds"
        // disconnect), the connection is already dead — we can't recover
        // mid-download. Set downloadError immediately so the user sees a
        // clear "Steam disconnected during download" message instead of
        // waiting the full 60s stuck-detection window.
        if (isDownloadInProgress) {
            WepWawetLogger.proxyError("$TAG: disconnect DURING download (likely WebSocket watchdog) — aborting immediately",
                "user_initiated" to callback.isUserInitiated)
            if (downloadError == null) {
                downloadError = java.lang.Exception(
                    "Steam connection dropped during download" +
                    if (!callback.isUserInitiated) " (WebSocket watchdog — Steam was unresponsive for 30s)" else "" +
                    ". The DepotDownloader can't recover mid-download — please retry.")
            }
            updateStatus("Steam connection dropped during download — aborting (please retry)")
            return
        }
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
