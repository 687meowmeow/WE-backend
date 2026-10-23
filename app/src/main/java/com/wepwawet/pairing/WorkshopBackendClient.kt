package com.wepwawet.pairing

import android.content.Context
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Client for the WepWawet self-hosted workshop content backend.
 *
 * The backend is a small Python+SteamCMD service that the user runs on their
 * PC or VPS. It downloads real Steam Workshop .pkg files via SteamCMD (which
 * requires Steam login) and serves them to the WepWawet Android app via a
 * simple HTTP API.
 *
 * === WHY SELF-HOSTED ===
 *
 * Steam hides the `file_url` field in `ISteamRemoteStorage/GetPublishedFileDetails/v1/`
 * for paid apps like Wallpaper Engine (431960). Even with Steam login + API key,
 * Steam returns an empty file_url. So we can't directly download workshop content
 * via Steam's API on Android.
 *
 * Other public workshop downloader services either:
 *   - Require Discord signup (GGNetwork/ggntw.com)
 *   - Are dead (steamworkshopdownloader.io — domain expired)
 *   - Don't actually download files (steamdb.info just generates SteamCMD commands)
 *
 * The self-hosted backend is the cleanest solution: the user runs it on their
 * own hardware with their own Steam credentials. No third-party accounts, no
 * Discord, no API keys.
 *
 * === BACKEND SETUP ===
 *
 * The user deploys the backend (see `/backend/README.md`):
 *   - Docker Compose (recommended): `docker compose up -d`
 *   - Standalone Python: `STEAM_USERNAME=... python3 server.py`
 *
 * The backend runs at `http://<server-ip>:8080` with these endpoints:
 *   POST /api/workshop/<workshop_id>  → starts SteamCMD download, returns task_id
 *   GET  /api/status/<task_id>        → poll for status (queued/downloading/complete/error)
 *   GET  /api/file/<task_id>          → stream the .pkg file (when complete)
 *   GET  /api/health                  → health check
 *
 * The user enters the backend URL in Settings → Backend URL.
 *
 * === API FLOW ===
 *
 * 1. POST /api/workshop/<id> → {task_id, status: "queued"}
 * 2. Poll GET /api/status/<task_id> every 2s until status == "complete"
 * 3. GET /api/file/<task_id> → stream the .pkg to a local file
 * 4. MpkgPacker.packFromPkg() → extract assets, pack as .mpkg
 * 5. PairingService.queueWallpaper() → transfer to WE Android app
 */
class WorkshopBackendClient(
    private val httpClient: OkHttpClient = defaultClient(),
) {

    companion object {
        private const val TAG = "WorkshopBackendClient"
        private const val POLL_INTERVAL_MS = 2000L
        private const val MAX_POLL_DURATION_MS = 30 * 60 * 1000L  // 30 min timeout
        private const val PREF_NAME = "wepwawet_backend"
        private const val KEY_BACKEND_URL = "backend_url"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)  // long timeout for large .pkg downloads
            .followRedirects(true)
            .build()

        /**
         * Get the saved backend URL (or null if not configured).
         */
        fun getBackendUrl(context: Context): String? {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_BACKEND_URL, null)?.takeIf { it.isNotBlank() }
        }

        /**
         * Save the backend URL.
         */
        fun setBackendUrl(context: Context, url: String) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_BACKEND_URL, url).apply()
            WepWawetLogger.proxy("$TAG.setBackendUrl",
                "url" to url)
        }

        /**
         * Clear the saved backend URL.
         */
        fun clearBackendUrl(context: Context) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_BACKEND_URL).apply()
        }

        /**
         * Quick check whether the user has configured a backend URL.
         */
        fun hasBackendUrl(context: Context): Boolean {
            return !getBackendUrl(context).isNullOrBlank()
        }
    }

    /**
     * Check if the backend is reachable and healthy.
     * @return the health response JSON string, or null on error.
     */
    suspend fun checkHealth(context: Context): String? = withContext(Dispatchers.IO) {
        val baseUrl = getBackendUrl(context) ?: return@withContext null
        val url = baseUrl.trimEnd('/') + "/api/health"
        WepWawetLogger.proxy("$TAG.checkHealth",
            "url" to url)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "WepWawet/1.0")
            .build()
        try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()
            WepWawetLogger.proxy("$TAG.checkHealth response",
                "http_code" to response.code,
                "body" to (body?.take(300) ?: "null"))
            if (response.isSuccessful) body else null
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG.checkHealth failed",
                "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            null
        }
    }

    /**
     * Full flow: download a workshop .pkg file from the backend.
     *
     * 1. POST /api/workshop/<id> to start the download
     * 2. Poll /api/status/<task_id> until status == "complete"
     * 3. GET /api/file/<task_id> to stream the .pkg to outputFile
     *
     * @param context for reading the backend URL
     * @param workshopId the workshop item ID (numeric string)
     * @param outputFile destination .pkg file
     * @param onProgress callback for download progress (0.0 to 1.0)
     * @param onStatusUpdate callback for status messages
     * @return the downloaded File on success, or null on failure
     */
    suspend fun downloadWorkshopPkg(
        context: Context,
        workshopId: String,
        outputFile: File,
        onProgress: (Float) -> Unit,
        onStatusUpdate: (String) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        val baseUrl = getBackendUrl(context)
        if (baseUrl.isNullOrBlank()) {
            WepWawetLogger.proxyWarn("$TAG.downloadWorkshopPkg: no backend URL configured")
            onStatusUpdate("No backend URL configured. Open Settings → Backend URL.")
            return@withContext null
        }
        val apiBase = baseUrl.trimEnd('/')
        WepWawetLogger.proxy("$TAG.downloadWorkshopPkg START",
            "workshop_id" to workshopId,
            "backend_url" to apiBase,
            "output" to outputFile.absolutePath)

        // Step 1: Start the download
        onStatusUpdate("Requesting download from backend…")
        val startUrl = "$apiBase/api/workshop/$workshopId"
        WepWawetLogger.proxy("$TAG.downloadWorkshopPkg step 1: POST $startUrl")
        val startRequest = Request.Builder()
            .url(startUrl)
            .post("{\"app_id\":431960}".toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "WepWawet/1.0")
            .build()

        val startResponse = try {
            httpClient.newCall(startRequest).execute()
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG.downloadWorkshopPkg: failed to connect to backend",
                "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            onStatusUpdate("Cannot connect to backend at $apiBase — is it running?")
            return@withContext null
        }

        if (!startResponse.isSuccessful) {
            WepWawetLogger.proxyWarn("$TAG.downloadWorkshopPkg: backend returned HTTP error",
                "code" to startResponse.code)
            onStatusUpdate("Backend error: HTTP ${startResponse.code}")
            return@withContext null
        }

        val startBody = startResponse.body?.string()
            ?: return@withContext null
        WepWawetLogger.proxy("$TAG.downloadWorkshopPkg: backend accepted request",
            "response" to startBody.take(300))

        // Parse task_id from JSON response (regex — no Gson dep)
        val taskIdMatch = Regex("\"task_id\"\\s*:\\s*\"([^\"]+)\"").find(startBody)
        val taskId = taskIdMatch?.groupValues?.get(1)
        if (taskId.isNullOrBlank()) {
            WepWawetLogger.proxyWarn("$TAG.downloadWorkshopPkg: no task_id in response",
                "body" to startBody.take(500))
            onStatusUpdate("Backend returned no task ID")
            return@withContext null
        }
        WepWawetLogger.proxy("$TAG.downloadWorkshopPkg: task created",
            "task_id" to taskId)

        // Step 2: Poll status until complete
        val statusUrl = "$apiBase/api/status/$taskId"
        val startTime = System.currentTimeMillis()
        var pollCount = 0
        var lastStatus = ""
        while (true) {
            if (System.currentTimeMillis() - startTime > MAX_POLL_DURATION_MS) {
                WepWawetLogger.proxyWarn("$TAG.downloadWorkshopPkg: polling timed out",
                    "task_id" to taskId,
                    "elapsed_ms" to (System.currentTimeMillis() - startTime))
                onStatusUpdate("Download timed out after 30 minutes")
                return@withContext null
            }

            val pollRequest = Request.Builder()
                .url(statusUrl)
                .header("User-Agent", "WepWawet/1.0")
                .build()
            val pollResponse = try {
                httpClient.newCall(pollRequest).execute()
            } catch (e: Exception) {
                WepWawetLogger.proxyWarn("$TAG.downloadWorkshopPkg: poll network error",
                    "exception" to e.javaClass.name)
                delay(POLL_INTERVAL_MS)
                continue
            }
            val pollBody = pollResponse.body?.string()
            if (pollBody.isNullOrBlank()) {
                delay(POLL_INTERVAL_MS)
                continue
            }

            // Parse status
            val statusMatch = Regex("\"status\"\\s*:\\s*\"([^\"]+)\"").find(pollBody)
            val status = statusMatch?.groupValues?.get(1) ?: "unknown"
            val progressMatch = Regex("\"progress\"\\s*:\\s*([\\d.]+)").find(pollBody)
            val progress = progressMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            val errorMatch = Regex("\"error\"\\s*:\\s*(?:\"([^\"]+)\"|null)").find(pollBody)
            val error = errorMatch?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            val fileSizeMatch = Regex("\"file_size\"\\s*:\\s*(\\d+)").find(pollBody)
            val fileSize = fileSizeMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L

            if (status != lastStatus) {
                WepWawetLogger.proxy("$TAG.downloadWorkshopPkg: status changed",
                    "task_id" to taskId,
                    "status" to status,
                    "progress" to progress,
                    "file_size" to fileSize)
                lastStatus = status
            }

            when (status) {
                "complete" -> {
                    WepWawetLogger.proxy("$TAG.downloadWorkshopPkg: backend reports complete!",
                        "task_id" to taskId,
                        "file_size" to fileSize,
                        "poll_count" to pollCount,
                        "elapsed_ms" to (System.currentTimeMillis() - startTime))
                    onStatusUpdate("Download ready (${formatBytes(fileSize)})")
                    break
                }
                "error" -> {
                    WepWawetLogger.proxyWarn("$TAG.downloadWorkshopPkg: backend reports error",
                        "task_id" to taskId,
                        "error" to (error ?: "unknown"))
                    onStatusUpdate("Download failed: ${error ?: "unknown error"}")
                    return@withContext null
                }
                "downloading" -> {
                    val pctStr = (progress * 100).toInt()
                    onStatusUpdate("Downloading via SteamCMD… $pctStr%")
                }
                "queued" -> {
                    onStatusUpdate("Queued for download…")
                }
                else -> {
                    onStatusUpdate("Status: $status")
                }
            }

            pollCount++
            if (pollCount % 15 == 1) {
                WepWawetLogger.proxy("$TAG.downloadWorkshopPkg: still polling",
                    "poll_count" to pollCount,
                    "status" to status,
                    "elapsed_ms" to (System.currentTimeMillis() - startTime))
            }

            delay(POLL_INTERVAL_MS)
        }

        // Step 3: Download the .pkg file
        val fileUrl = "$apiBase/api/file/$taskId"
        WepWawetLogger.proxy("$TAG.downloadWorkshopPkg: downloading .pkg from backend",
            "url" to fileUrl)
        onStatusUpdate("Downloading .pkg file from backend…")

        val fileRequest = Request.Builder()
            .url(fileUrl)
            .header("User-Agent", "WepWawet/1.0")
            .build()
        val fileResponse = try {
            httpClient.newCall(fileRequest).execute()
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG.downloadWorkshopPkg: file download failed",
                "exception" to e.javaClass.name)
            onStatusUpdate("Failed to download .pkg from backend")
            return@withContext null
        }

        if (!fileResponse.isSuccessful) {
            WepWawetLogger.proxyWarn("$TAG.downloadWorkshopPkg: file HTTP error",
                "code" to fileResponse.code)
            onStatusUpdate("Backend file HTTP error: ${fileResponse.code}")
            return@withContext null
        }

        val body = fileResponse.body ?: return@withContext null
        val totalBytes = body.contentLength().toFloat().coerceAtLeast(1f)
        try {
            outputFile.parentFile?.mkdirs()
            body.byteStream().use { input ->
                outputFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var downloaded = 0L
                    var lastLogTime = System.currentTimeMillis()
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        val pct = (downloaded / totalBytes).coerceIn(0f, 1f)
                        onProgress(pct)
                        val now = System.currentTimeMillis()
                        if (now - lastLogTime > 2000 || downloaded.toLong() == totalBytes.toLong()) {
                            WepWawetLogger.proxy("$TAG.downloadWorkshopPkg: file download progress",
                                "downloaded" to downloaded,
                                "total" to totalBytes.toLong(),
                                "pct" to "%.1f".format(pct * 100),
                                "kbps" to if (now - lastLogTime > 0)
                                    (downloaded * 8 / 1024 / (now - lastLogTime)).toInt() else 0)
                            lastLogTime = now
                        }
                    }
                }
            }
            onProgress(1f)
            WepWawetLogger.proxy("$TAG.downloadWorkshopPkg COMPLETE",
                "file" to outputFile.name,
                "file_size" to outputFile.length(),
                "file_size_human" to formatBytes(outputFile.length()))
            onStatusUpdate("Downloaded ${formatBytes(outputFile.length())} .pkg")
            outputFile
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG.downloadWorkshopPkg: stream error",
                "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            onStatusUpdate("Download stream error: ${e.message}")
            null
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }
}
