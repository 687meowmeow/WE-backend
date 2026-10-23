package com.wepwawet.pairing

import android.content.Context
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import `in`.dragonbra.javasteam.depotdownloader.data.PubFileItem
import `in`.dragonbra.javasteam.enums.EResult
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
) {
    companion object {
        private const val TAG = "WorkshopDownloader"
        private const val APP_ID_WE = 431960
    }

    private var steamClient: SteamClient? = null
    private var callbackManager: CallbackManager? = null
    private var steamUser: SteamUser? = null
    private var licenseList: List<License> = emptyList()
    @Volatile private var isRunning = false
    @Volatile private var downloadError: Exception? = null
    private val subscriptions = mutableListOf<Closeable>()

    suspend fun downloadWorkshopPkg(
        context: Context,
        workshopId: String,
        outputFile: File,
        onProgress: (Float) -> Unit,
        onStatusUpdate: (String) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        WepWawetLogger.proxy("$TAG START", "workshop_id" to workshopId, "username" to username)
        try {
            onStatusUpdate("Connecting to Steam...")
            if (!connectAndAuthenticate()) {
                onStatusUpdate("Failed to connect to Steam")
                return@withContext null
            }

            onStatusUpdate("Fetching Steam licenses...")
            if (!waitForLicenses(30000)) {
                onStatusUpdate("Timed out waiting for licenses")
                return@withContext null
            }

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
                    WepWawetLogger.proxyDebug("$TAG: file", "depot" to depotId, "pct" to "%.1f".format(depotPercentComplete * 100))
                    onProgress(depotPercentComplete)
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
            try { steamUser?.logOff() } catch (_: Exception) {}
            try { steamClient?.disconnect() } catch (_: Exception) {}
            subscriptions.forEach { try { it.close() } catch (_: Exception) {} }
            subscriptions.clear()
            isRunning = false
        }
    }

    private fun connectAndAuthenticate(): Boolean {
        val dispatcher = okhttp3.Dispatcher(Executors.newCachedThreadPool { r ->
            Thread(r, "JavaSteam-Http").apply { isDaemon = true }
        })
        val httpClient = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        steamClient = SteamClient(SteamConfiguration.create { it.withHttpClient(httpClient) })
        callbackManager = CallbackManager(steamClient!!)
        steamUser = steamClient!!.getHandler(SteamUser::class.java)

        subscriptions.add(callbackManager!!.subscribe(ConnectedCallback::class.java) { onConnected(it) })
        subscriptions.add(callbackManager!!.subscribe(DisconnectedCallback::class.java) { onDisconnected(it) })
        subscriptions.add(callbackManager!!.subscribe(LoggedOnCallback::class.java) { onLoggedOn(it) })
        subscriptions.add(callbackManager!!.subscribe(LoggedOffCallback::class.java) { onLoggedOff(it) })
        subscriptions.add(callbackManager!!.subscribe(LicenseListCallback::class.java) { onLicenseList(it) })

        isRunning = true
        WepWawetLogger.proxy("$TAG: connecting to Steam...")
        steamClient!!.connect()

        Thread({
            while (isRunning) {
                try { callbackManager!!.runWaitCallbacks(1000L) } catch (_: Exception) { break }
            }
        }, "JavaSteam-Callback").start()

        val timeout = 60000L
        val start = System.currentTimeMillis()
        while (isRunning && System.currentTimeMillis() - start < timeout) {
            if (licenseList.isNotEmpty()) return true
            if (downloadError != null) return false
            Thread.sleep(500)
        }
        return false
    }

    private fun onConnected(callback: ConnectedCallback) {
        WepWawetLogger.proxy("$TAG: connected — authenticating as $username")
        try {
            val details = LogOnDetails()
            details.username = username
            details.password = password
            details.loginID = 149
            steamUser!!.logOn(details)
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG: auth failed", "exception" to e.javaClass.name)
            downloadError = e
        }
    }

    private fun onLoggedOn(callback: LoggedOnCallback) {
        if (callback.result != EResult.OK) {
            WepWawetLogger.proxyError("$TAG: logon failed", "result" to callback.result)
            downloadError = java.lang.Exception("Steam logon failed: ${callback.result}")
            isRunning = false
            return
        }
        WepWawetLogger.proxy("$TAG: logged on to Steam")
    }

    private fun onLoggedOff(callback: LoggedOffCallback) {
        WepWawetLogger.proxy("$TAG: logged off", "result" to callback.result)
        isRunning = false
    }

    private fun onDisconnected(callback: DisconnectedCallback) {
        WepWawetLogger.proxy("$TAG: disconnected", "user_initiated" to callback.isUserInitiated)
        if (!callback.isUserInitiated) {
            try { Thread.sleep(2000); steamClient?.connect() } catch (_: Exception) {}
        } else isRunning = false
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
            Thread.sleep(200)
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
