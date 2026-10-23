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

            val downloader = DepotDownloader(steamClient!!, licenseList, false, false, 8, 4, 8, false, null)

            val listener = object : IDownloadListener {
                override fun onItemAdded(item: DownloadItem) {
                    WepWawetLogger.proxy("$TAG: item added", "app_id" to item.appId)
                }
                override fun onDownloadStarted(item: DownloadItem) {
                    WepWawetLogger.proxy("$TAG: download started", "app_id" to item.appId)
                    onStatusUpdate("Download started...")
                }
                override fun onDownloadCompleted(item: DownloadItem) {
                    WepWawetLogger.proxy("$TAG: download completed", "app_id" to item.appId)
                    onProgress(1f)
                }
                override fun onDownloadFailed(item: DownloadItem, error: Throwable) {
                    WepWawetLogger.proxyError("$TAG: download failed", "message" to (error.message ?: ""))
                    downloadError = java.lang.Exception(error.message ?: "download failed", error)
                }
                override fun onStatusUpdate(message: String) {
                    WepWawetLogger.proxyDebug("$TAG: status", "msg" to message.take(200))
                    onStatusUpdate(message)
                }
                override fun onFileCompleted(depotId: Int, fileName: String, depotPercentComplete: Float) {
                    val pct = (depotPercentComplete * 100).toInt()
                    WepWawetLogger.proxyDebug("$TAG: file", "depot" to depotId, "pct" to pct)
                    onProgress(depotPercentComplete)
                    onStatusUpdate("Downloading... $pct%")
                }
                override fun onDepotCompleted(depotId: Int, compressedBytes: Long, uncompressedBytes: Long) {
                    WepWawetLogger.proxy("$TAG: depot done", "depot" to depotId, "uncompressed" to uncompressedBytes)
                }
            }

            downloader.addListener(listener)

            val pubFileId = workshopId.toLongOrNull() ?: return@withContext null
            val pubItem = PubFileItem(APP_ID_WE, pubFileId)

            WepWawetLogger.proxy("$TAG: adding PubFileItem", "app_id" to APP_ID_WE, "pub_file_id" to pubFileId)
            downloader.add(pubItem)
            downloader.finishAdding()

            onStatusUpdate("Downloading... this may take a few minutes for large wallpapers")
            runCatching { runBlocking { downloader.awaitCompletion() } }
            downloader.removeListener(listener)
            try { downloader.close() } catch (_: Exception) {}

            if (downloadError != null) {
                onStatusUpdate("Download failed: ${downloadError!!.message}")
                return@withContext null
            }

            onStatusUpdate("Locating downloaded .pkg file...")
            val pkgFile = findPkgFile(installDir)
            if (pkgFile == null || !pkgFile.exists()) {
                WepWawetLogger.proxyWarn("$TAG: no .pkg found", "dir" to installDir.absolutePath)
                onStatusUpdate("No .pkg file found after download")
                return@withContext null
            }
            WepWawetLogger.proxy("$TAG: .pkg found", "file" to pkgFile.absolutePath, "size" to pkgFile.length())

            pkgFile.copyTo(outputFile, overwrite = true)
            WepWawetLogger.proxy("$TAG COMPLETE", "output" to outputFile.name, "size" to outputFile.length())
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
