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
 * GGNetwork API client — downloads real Steam Workshop content via the
 * GGNetwork (ggntw.com) workshop downloader service.
 *
 * === WHY THIS EXISTS ===
 *
 * Steam hides the `file_url` field in `ISteamRemoteStorage.GetPublishedFileDetails/v1/`
 * for paid apps like Wallpaper Engine (431960). Even with Steam login + API key,
 * Steam returns an empty file_url. So we can't directly download workshop content
 * via Steam's API.
 *
 * GGNetwork (ggntw.com) is a free workshop downloader service that:
 *   1. Uses SteamCMD server-side to download workshop items
 *   2. Caches them on their CDN (cdn.ggntw.com, cdn.ggntw.ru, hk-cdn.ggntw.com)
 *   3. Provides an API for clients to request downloads
 *
 * The user creates a free account at https://ggntw.com/login (email + password).
 * They enter those credentials in the WepWawet app's Settings. The app:
 *   1. Logs in to ggntw.com via API → gets an access token
 *   2. Stores the token in SharedPreferences for future use
 *   3. When user taps "Send to phone" on a workshop item:
 *      a. POST to api.ggntw.com/steam.request with the workshop URL
 *      b. Get back a task ID + status (queued/downloading/ready)
 *      c. Poll api.ggntw.com/steam.update every 2s with the task ID
 *      d. When status==1 (ready), the response contains a direct download URL
 *      e. Download the .pkg file from that URL
 *      f. Extract assets via PkgReader
 *      g. Pack as .mpkg via MpkgPacker.packFromPkg
 *      h. Queue for transfer to WE Android app
 *
 * === API ENDPOINTS (verified live by curl) ===
 *
 * 1. LOGIN:
 *    POST https://api.ggntw.com/user/login
 *    Body (JSON): {"email": "...", "password": "..."}
 *    Response: {"status": 1, "token": "...", "theme": "ru"} on success
 *              {"status": 0, "message": "..."} on failure
 *
 * 2. REQUEST DOWNLOAD:
 *    POST https://api.ggntw.com/steam.request
 *    Headers: Access-Token: <token>
 *    Body (JSON): {"url": "https://steamcommunity.com/sharedfiles/filedetails/?id=<id>"}
 *    Response: {"result": <code>, "id": <task_id>, "status": <0=queued, 1=ready, 2=downloading, 3=error>, "url": <download_url>, "image": <preview_url>, "name": <title>, "size": <size>, "queue": {"position": <pos>, "total": <total>}}
 *
 * 3. POLL STATUS:
 *    POST https://api.ggntw.com/steam.update
 *    Body (JSON): {"id": <task_id>}
 *    Response: same shape as steam.request (with updated status)
 *
 * 4. DOWNLOAD:
 *    GET <url from steam.update response when status==1>
 *    (No authentication needed — the URL contains a token in the path)
 *    URL pattern: https://cdn.ggntw.com/<key> or https://cdn.ggntw.ru/<key> or https://hk-cdn.ggntw.com/<key>
 *
 * === RESULT CODES (from JS source code analysis) ===
 *
 *   0: success (login worked, or steam.request accepted)
 *   1: ready to download (steam.update returns this when download is ready)
 *   2: in queue / downloading
 *   3: error (general)
 *   4: not logged in (Access-Token missing/invalid)
 *   5: NSFW content (banned or needs premium)
 *   10: need login
 *   11: limit reached (need premium)
 *   12: NSFW blocked (need premium)
 *   15: need premium
 */
class GgntwApiClient(
    private val httpClient: OkHttpClient = defaultClient(),
) {

    companion object {
        private const val TAG = "GgntwApiClient"
        private const val BASE_URL = "https://api.ggntw.com"
        private const val LOGIN_ENDPOINT = "$BASE_URL/user/login"
        private const val STEAM_REQUEST_ENDPOINT = "$BASE_URL/steam.request"
        private const val STEAM_UPDATE_ENDPOINT = "$BASE_URL/steam.update"

        private const val PREF_NAME = "wepwawet_ggntw"
        private const val KEY_TOKEN = "access_token"
        private const val KEY_EMAIL = "email"
        private const val KEY_LOGIN_TIMESTAMP = "login_timestamp"

        // Steam.update polling configuration
        private const val POLL_INTERVAL_MS = 2000L  // 2 seconds between polls
        private const val MAX_POLL_DURATION_MS = 5 * 60 * 1000L  // 5 minute timeout

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        /**
         * Quick check whether the user has saved GGNetwork credentials.
         */
        fun hasCredentials(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_TOKEN, null) != null
        }

        /**
         * Get the saved access token (or null if not logged in).
         */
        fun getToken(context: Context): String? {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_TOKEN, null)
        }

        /**
         * Get the saved email (or null).
         */
        fun getEmail(context: Context): String? {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_EMAIL, null)
        }

        /**
         * Clear saved credentials (logout).
         */
        fun logout(context: Context) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            WepWawetLogger.proxy("$TAG.logout — credentials cleared")
        }
    }

    /**
     * Login result.
     */
    sealed class LoginResult {
        data class Success(val token: String, val email: String) : LoginResult()
        data class Failure(val message: String, val statusCode: Int = 0) : LoginResult()
        data class NetworkError(val exception: Exception) : LoginResult()
    }

    /**
     * Steam.request / steam.update response (parsed from JSON).
     */
    data class SteamRequestResponse(
        val result: Int,        // result code (0=success, 4=not logged in, etc.)
        val id: String? = null, // task ID for polling
        val status: Int = 0,    // 0=queued, 1=ready, 2=downloading, 3=error
        val url: String? = null,// download URL (when status==1)
        val image: String? = null,
        val name: String? = null,
        val size: String? = null,
        val queue: QueueInfo? = null,
        val update: String? = null,
    )

    data class QueueInfo(
        val position: Int = 0,
        val total: Int = 0,
    )

    /**
     * Login to GGNetwork with email + password.
     * On success, persists the access token to SharedPreferences.
     *
     * Returns [LoginResult.Success] on success or [LoginResult.Failure] on bad credentials.
     */
    suspend fun login(context: Context, email: String, password: String): LoginResult =
        withContext(Dispatchers.IO) {
            WepWawetLogger.proxy("$TAG.login START",
                "email" to email,
                "password_length" to password.length)

            val jsonBody = """{"email":"$email","password":"$password"}"""
            val request = Request.Builder()
                .url(LOGIN_ENDPOINT)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "WepWawet/1.0")
                .build()

            val response = try {
                httpClient.newCall(request).execute()
            } catch (e: Exception) {
                WepWawetLogger.proxyError("$TAG.login network error",
                    "exception" to e.javaClass.name, "message" to (e.message ?: ""))
                return@withContext LoginResult.NetworkError(e)
            }

            val body = response.body?.string()
                ?: return@withContext LoginResult.Failure("Empty response body", response.code)
            WepWawetLogger.proxy("$TAG.login response received",
                "http_code" to response.code,
                "body_length" to body.length,
                "body_preview" to body.take(300))

            // Parse JSON response: {"status": 1, "token": "...", "theme": "ru"} on success
            // OR {"status": 0, "message": "..."} on failure
            val statusMatch = Regex("\"status\"\\s*:\\s*(\\d+)").find(body)
            val status = statusMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (status == 0) {
                // Failure — extract message
                val messageMatch = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"").find(body)
                val message = messageMatch?.groupValues?.get(1) ?: "Login failed (no message)"
                WepWawetLogger.proxyWarn("$TAG.login failed",
                    "status" to status, "message" to message)
                return@withContext LoginResult.Failure(message, response.code)
            }

            // Success — extract token
            val tokenMatch = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(body)
            val token = tokenMatch?.groupValues?.get(1)
            if (token.isNullOrBlank()) {
                WepWawetLogger.proxyWarn("$TAG.login: status=success but no token in response",
                    "body" to body.take(500))
                return@withContext LoginResult.Failure("Login succeeded but no token in response")
            }
            WepWawetLogger.proxy("$TAG.login SUCCESS",
                "token_length" to token.length,
                "token_preview" to token.take(16))

            // Persist to SharedPreferences
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_TOKEN, token)
                .putString(KEY_EMAIL, email)
                .putLong(KEY_LOGIN_TIMESTAMP, System.currentTimeMillis())
                .apply()
            WepWawetLogger.proxy("$TAG.login credentials persisted",
                "pref_name" to PREF_NAME)

            LoginResult.Success(token, email)
        }

    /**
     * Request a workshop item download from GGNetwork.
     *
     * This queues the item for download on GGNetwork's servers (which use SteamCMD
     * server-side). The item is then available via [pollDownloadStatus] until
     * the download URL is ready.
     *
     * @param context for reading the access token from SharedPreferences
     * @param workshopUrl the full workshop URL (e.g. "https://steamcommunity.com/sharedfiles/filedetails/?id=3822839089")
     * @return the parsed response, or null on network error
     */
    suspend fun requestDownload(context: Context, workshopUrl: String): SteamRequestResponse? =
        withContext(Dispatchers.IO) {
            val token = getToken(context)
            if (token.isNullOrBlank()) {
                WepWawetLogger.proxyWarn("$TAG.requestDownload: no access token — user not logged in")
                return@withContext null
            }
            WepWawetLogger.proxy("$TAG.requestDownload START",
                "workshop_url" to workshopUrl.take(120))

            val jsonBody = """{"url":"$workshopUrl"}"""
            val request = Request.Builder()
                .url(STEAM_REQUEST_ENDPOINT)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "WepWawet/1.0")
                .header("Access-Token", token)
                .build()

            val response = try {
                httpClient.newCall(request).execute()
            } catch (e: Exception) {
                WepWawetLogger.proxyError("$TAG.requestDownload network error",
                    "exception" to e.javaClass.name)
                return@withContext null
            }

            val body = response.body?.string()
                ?: return@withContext null
            WepWawetLogger.proxy("$TAG.requestDownload response",
                "http_code" to response.code,
                "body" to body.take(500))

            parseSteamResponse(body)
        }

    /**
     * Poll the status of a workshop download request.
     *
     * @param context for reading the access token
     * @param taskId the task ID returned by [requestDownload]
     * @return the parsed response, or null on network error
     */
    suspend fun pollDownloadStatus(context: Context, taskId: String): SteamRequestResponse? =
        withContext(Dispatchers.IO) {
            val token = getToken(context)
            if (token.isNullOrBlank()) {
                WepWawetLogger.proxyWarn("$TAG.pollDownloadStatus: no access token")
                return@withContext null
            }

            val jsonBody = """{"id":"$taskId"}"""
            val request = Request.Builder()
                .url(STEAM_UPDATE_ENDPOINT)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "WepWawet/1.0")
                .header("Access-Token", token)
                .build()

            val response = try {
                httpClient.newCall(request).execute()
            } catch (e: Exception) {
                WepWawetLogger.proxyError("$TAG.pollDownloadStatus network error",
                    "exception" to e.javaClass.name)
                return@withContext null
            }

            val body = response.body?.string()
                ?: return@withContext null
            parseSteamResponse(body)
        }

    /**
     * Request a workshop download, then poll until the .pkg is ready, then download it.
     *
     * @param context for access token
     * @param workshopUrl the workshop URL
     * @param outputFile destination .pkg file
     * @param onProgress callback for download progress (0.0 to 1.0) — also reports polling status
     * @param onStatusUpdate callback for status messages (e.g. "Queued at position 3/10", "Downloading…")
     * @return the downloaded File on success, or null on failure
     */
    suspend fun downloadWorkshopPkg(
        context: Context,
        workshopUrl: String,
        outputFile: File,
        onProgress: (Float) -> Unit,
        onStatusUpdate: (String) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        WepWawetLogger.proxy("$TAG.downloadWorkshopPkg START",
            "workshop_url" to workshopUrl.take(120),
            "output" to outputFile.absolutePath)

        // Step 1: Request the download
        onStatusUpdate("Requesting download from GGNetwork…")
        val initialResponse = requestDownload(context, workshopUrl)
            ?: return@withContext null

        WepWawetLogger.proxy("$TAG.downloadWorkshopPkg initial response",
            "result" to initialResponse.result,
            "task_id" to (initialResponse.id ?: "null"),
            "status" to initialResponse.status,
            "url_present" to !initialResponse.url.isNullOrBlank(),
            "name" to (initialResponse.name ?: "null"),
            "size" to (initialResponse.size ?: "null"))

        // Check for auth/premium errors
        when (initialResponse.result) {
            4 -> {
                onStatusUpdate("GGNetwork login expired. Please re-enter your credentials in Settings.")
                return@withContext null
            }
            11 -> {
                onStatusUpdate("GGNetwork daily limit reached. Try again tomorrow or upgrade to premium.")
                return@withContext null
            }
            12 -> {
                onStatusUpdate("GGNetwork: NSFW content requires premium account.")
                return@withContext null
            }
            15 -> {
                onStatusUpdate("GGNetwork: This content requires a premium account.")
                return@withContext null
            }
            3 -> {
                onStatusUpdate("GGNetwork: Error processing this workshop item.")
                return@withContext null
            }
        }

        val taskId = initialResponse.id
        if (taskId.isNullOrBlank()) {
            WepWawetLogger.proxyWarn("$TAG.downloadWorkshopPkg: no task ID in response",
                "result" to initialResponse.result)
            onStatusUpdate("GGNetwork: failed to queue download (no task ID)")
            return@withContext null
        }

        // Step 2: Poll until ready (or timeout)
        var currentResponse = initialResponse
        val startTime = System.currentTimeMillis()
        var pollCount = 0
        while (currentResponse.status != 1) {
            if (currentResponse.status == 3) {
                WepWawetLogger.proxyError("$TAG.downloadWorkshopPkg: GGNetwork reported error",
                    "task_id" to taskId)
                onStatusUpdate("GGNetwork: download failed (server error)")
                return@withContext null
            }

            if (System.currentTimeMillis() - startTime > MAX_POLL_DURATION_MS) {
                WepWawetLogger.proxyWarn("$TAG.downloadWorkshopPkg: polling timed out",
                    "task_id" to taskId,
                    "elapsed_ms" to (System.currentTimeMillis() - startTime))
                onStatusUpdate("GGNetwork: download timed out after 5 minutes")
                return@withContext null
            }

            // Update UI with status
            val statusMsg = when (currentResponse.status) {
                0 -> {
                    val queue = currentResponse.queue
                    if (queue != null && queue.total > 0) {
                        "Queued: position ${queue.position}/${queue.total}…"
                    } else {
                        "Queued for download…"
                    }
                }
                2 -> "Downloading from Steam via GGNetwork…"
                else -> "Status: ${currentResponse.status}"
            }
            onStatusUpdate(statusMsg)

            pollCount++
            if (pollCount % 10 == 1) {
                WepWawetLogger.proxy("$TAG.downloadWorkshopPkg polling",
                    "poll_count" to pollCount,
                    "status" to currentResponse.status,
                    "elapsed_ms" to (System.currentTimeMillis() - startTime),
                    "queue" to (currentResponse.queue?.let { "${it.position}/${it.total}" } ?: "n/a"))
            }

            delay(POLL_INTERVAL_MS)
            currentResponse = pollDownloadStatus(context, taskId) ?: continue
        }

        // status == 1 means ready
        val downloadUrl = currentResponse.url
        if (downloadUrl.isNullOrBlank()) {
            WepWawetLogger.proxyWarn("$TAG.downloadWorkshopPkg: status=ready but no download URL",
                "task_id" to taskId)
            onStatusUpdate("GGNetwork: download ready but URL missing")
            return@withContext null
        }
        WepWawetLogger.proxy("$TAG.downloadWorkshopPkg download ready!",
            "download_url" to downloadUrl.take(150),
            "size" to (currentResponse.size ?: "unknown"),
            "name" to (currentResponse.name ?: "unknown"),
            "poll_count" to pollCount,
            "elapsed_ms" to (System.currentTimeMillis() - startTime))

        // Step 3: Download the .pkg file
        onStatusUpdate("Downloading .pkg (${currentResponse.size ?: "?"})…")
        val downloaded = downloadFile(downloadUrl, outputFile, onProgress)
        if (downloaded) {
            WepWawetLogger.proxy("$TAG.downloadWorkshopPkg COMPLETE",
                "file" to outputFile.name,
                "file_size" to outputFile.length(),
                "file_size_human" to formatBytes(outputFile.length()))
            onStatusUpdate("Downloaded ${formatBytes(outputFile.length())} .pkg")
            outputFile
        } else {
            WepWawetLogger.proxyWarn("$TAG.downloadWorkshopPkg download failed",
                "download_url" to downloadUrl.take(150))
            onStatusUpdate("Failed to download .pkg from GGNetwork CDN")
            null
        }
    }

    /**
     * Download a file from a URL to a local file with progress tracking.
     */
    private suspend fun downloadFile(url: String, outputFile: File, onProgress: (Float) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "WepWawet/1.0")
                .build()

            val response = try {
                httpClient.newCall(request).execute()
            } catch (e: Exception) {
                WepWawetLogger.proxyError("$TAG.downloadFile network error",
                    "exception" to e.javaClass.name, "message" to (e.message ?: ""))
                return@withContext false
            }

            if (!response.isSuccessful) {
                WepWawetLogger.proxyWarn("$TAG.downloadFile HTTP error",
                    "code" to response.code, "url" to url.take(150))
                return@withContext false
            }

            val body = response.body ?: return@withContext false
            val totalBytes = body.contentLength().toFloat().coerceAtLeast(1f)

            try {
                outputFile.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    outputFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)  // 64KB chunks
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
                                WepWawetLogger.proxy("$TAG.downloadFile progress",
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
                true
            } catch (e: Exception) {
                WepWawetLogger.proxyError("$TAG.downloadFile stream error",
                    "exception" to e.javaClass.name, "message" to (e.message ?: ""))
                false
            }
        }

    /**
     * Parse the JSON response from steam.request / steam.update.
     * Uses regex (not Gson) to avoid adding a JSON parsing dependency in this class.
     */
    private fun parseSteamResponse(json: String): SteamRequestResponse? {
        return try {
            val result = Regex("\"result\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val id = Regex("\"id\"\\s*:\\s*\"?([^\",}]+)\"?").find(json)?.groupValues?.get(1)
            val status = Regex("\"status\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val url = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            val image = Regex("\"image\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            val name = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            val size = Regex("\"size\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            val update = Regex("\"update\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            // queue is a nested object: "queue":{"position":N,"total":M}
            val queuePosition = Regex("\"position\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
            val queueTotal = Regex("\"total\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
            val queue = if (queuePosition != null || queueTotal != null) {
                QueueInfo(queuePosition ?: 0, queueTotal ?: 0)
            } else null
            SteamRequestResponse(
                result = result, id = id, status = status, url = url,
                image = image, name = name, size = size, queue = queue, update = update,
            )
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("$TAG.parseSteamResponse failed",
                "exception" to e.javaClass.name, "json" to json.take(300))
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
