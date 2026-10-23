package com.wepwawet.pairing

import com.wepwawet.auth.SteamLoginActivity
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Attempts to download the actual workshop CONTENT (the .pkg file) for a
 * Wallpaper Engine workshop item.
 *
 * === WHY THIS IS HARD ===
 *
 * Steam hides the `file_url` field in `ISteamRemoteStorage/GetPublishedFileDetails/v1/`
 * for paid apps like Wallpaper Engine (app 431960). So even though the API returns
 * the metadata (title, description, file_size, preview_url), the actual content
 * download URL is empty.
 *
 * === APPROACHES ===
 *
 * 1. **IPublishedFileService.GetDetails with Steam login cookies + Web API key**
 *    The user provides their Steam Web API key (free at steamcommunity.com/dev/apikey)
 *    AND logs into Steam via [SteamLoginActivity] WebView. The combination of
 *    cookies + API key may give us access to the file_url. (Not guaranteed —
 *    Steam may still hide it for paid apps even with auth.)
 *
 * 2. **Steam Workshop HTML scraping** — uses session cookies to fetch the
 *    workshop page HTML, look for a download link. (Steam has removed public
 *    download links from the HTML for paid apps.)
 *
 * 3. **Fall back to user-provided .pkg file** — the user manually copies the
 *    .pkg file from their PC's Steam folder (`Steam/steamapps/workshop/content/
 *    431960/<id>/scene.pkg`) to their phone's Download folder. Then they pick
 *    it via a file picker in the WepWawet UI.
 *
 * === CURRENT IMPLEMENTATION ===
 *
 * v1: We attempt approach #1 (Steam login + API key). If file_url is populated,
 * we download the .pkg. If it's empty (most common case), we return null and the
 * UI prompts the user to use approach #3 (local .pkg file picker).
 *
 * Future: integrate with a backend service running SteamCMD that downloads
 * the .pkg server-side, then transfers it to the Android app.
 */
class WorkshopContentDownloader(
    private val httpClient: OkHttpClient = defaultClient(),
) {

    companion object {
        private const val TAG = "WorkshopContentDownloader"
        private const val BASE_URL = "https://api.steampowered.com"
        private const val GET_DETAILS_ENDPOINT = "/IPublishedFileService/GetDetails/v1/"
        private const val APP_ID_WE = 431960L  // Wallpaper Engine

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * The result of a download attempt.
     */
    sealed class Result {
        /** Download succeeded; .pkg file is at [pkgFile]. */
        data class Success(val pkgFile: File) : Result()

        /** Steam API returned empty file_url — user must provide .pkg manually. */
        data class EmptyFileUrl(val workshopId: String, val fileDescription: String) : Result()

        /** Authentication required (no Steam login cookies, no API key). */
        data class AuthRequired(val reason: String) : Result()

        /** Network error or other failure. */
        data class Failure(val error: String, val exception: Exception? = null) : Result()
    }

    /**
     * Attempt to download the .pkg file for a workshop item.
     *
     * Requires:
     *   - User logged into Steam via [SteamLoginActivity] (cookies in prefs)
     *   - Steam Web API key set in Settings (stored in `wepwawet_settings` prefs)
     *
     * @param context Android context (for reading Steam cookies from prefs)
     * @param workshopId the publishedfileid of the workshop item
     * @param outputFile destination .pkg file
     * @param onProgress callback for download progress (0.0 to 1.0)
     * @return [Result] indicating success or failure mode
     */
    suspend fun download(
        context: android.content.Context,
        workshopId: String,
        outputFile: File,
        apiKey: String,
        onProgress: (Float) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        WepWawetLogger.proxy("$TAG.download START",
            "workshop_id" to workshopId,
            "output" to outputFile.absolutePath,
            "has_api_key" to apiKey.isNotEmpty())

        if (apiKey.isBlank()) {
            WepWawetLogger.proxyWarn("$TAG no API key — aborting",
                "reason" to "Steam Web API key required (free at steamcommunity.com/dev/apikey)")
            return@withContext Result.AuthRequired("Steam Web API key required")
        }

        // Get Steam login cookies from SteamLoginActivity's shared preferences
        val cookies = SteamLoginActivity.getCookies(context)
        if (cookies.isNullOrBlank()) {
            WepWawetLogger.proxyWarn("$TAG no Steam cookies — user not logged in",
                "reason" to "Login to Steam via Settings first")
            return@withContext Result.AuthRequired("Steam login required")
        }
        WepWawetLogger.proxy("$TAG auth ready",
            "has_cookies" to true,
            "cookie_length" to cookies.length,
            "has_api_key" to true)

        // Step 1: Call IPublishedFileService.GetDetails with the API key + cookies
        val url = "$BASE_URL$GET_DETAILS_ENDPOINT?key=$apiKey&publishedfileids[0]=$workshopId"
        WepWawetLogger.proxy("$TAG calling IPublishedFileService.GetDetails",
            "url" to url.take(200))

        val requestBuilder = Request.Builder().url(url).header("User-Agent", "WepWawet/1.0")
        requestBuilder.header("Cookie", cookies)

        val request = requestBuilder.build()
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG HTTP call failed",
                "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            return@withContext Result.Failure("HTTP call failed: ${e.message}", e)
        }

        if (!response.isSuccessful) {
            WepWawetLogger.proxyError("$TAG HTTP error",
                "code" to response.code,
                "message" to response.message)
            return@withContext Result.Failure("HTTP ${response.code}: ${response.message}")
        }

        val body = response.body?.string()
            ?: return@withContext Result.Failure("Empty response body")
        WepWawetLogger.proxy("$TAG API response received",
            "body_size" to body.length,
            "body_preview" to body.take(500))

        // Parse the JSON response to extract file_url
        val fileUrl = extractFileUrl(body)
        val title = extractTitle(body)
        val fileSize = extractFileSize(body)
        val type = extractType(body)

        WepWawetLogger.proxy("$TAG parsed workshop item details",
            "file_url_present" to !fileUrl.isNullOrBlank(),
            "file_url" to (fileUrl?.take(150) ?: "null"),
            "title" to (title ?: "unknown"),
            "file_size" to fileSize,
            "type" to (type ?: "unknown"))

        if (fileUrl.isNullOrBlank()) {
            WepWawetLogger.proxyWarn("$TAG file_url is EMPTY — Steam hides it for paid apps",
                "workshop_id" to workshopId,
                "app_id" to APP_ID_WE,
                "explanation" to "Steam Web API does not provide direct download URLs for paid apps like Wallpaper Engine (431960). The user must manually download the .pkg file via Steam client on PC, then copy it to the phone and use the 'Pick local .pkg file' button.")
            return@withContext Result.EmptyFileUrl(
                workshopId = workshopId,
                fileDescription = "Steam API returned empty file_url. The user must download the .pkg file manually via Steam client."
            )
        }

        // Step 2: Download the .pkg file from file_url (with auth cookies)
        WepWawetLogger.proxy("$TAG downloading .pkg file",
            "url" to fileUrl.take(200),
            "expected_size" to fileSize)

        val downloadRequest = Request.Builder()
            .url(fileUrl)
            .header("User-Agent", "WepWawet/1.0")
            .header("Cookie", cookies)
            .build()

        val downloadResponse = try {
            httpClient.newCall(downloadRequest).execute()
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG download HTTP failed",
                "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            return@withContext Result.Failure("Download HTTP failed: ${e.message}", e)
        }

        if (!downloadResponse.isSuccessful) {
            WepWawetLogger.proxyError("$TAG download HTTP error",
                "code" to downloadResponse.code,
                "message" to downloadResponse.message)
            return@withContext Result.Failure("Download HTTP ${downloadResponse.code}: ${downloadResponse.message}")
        }

        val downloadBody = downloadResponse.body
            ?: return@withContext Result.Failure("Empty download body")

        val totalBytes = downloadBody.contentLength().toFloat().coerceAtLeast(1f)
        WepWawetLogger.proxy("$TAG download body opened",
            "content_length" to downloadBody.contentLength(),
            "expected_total_bytes" to totalBytes)

        try {
            outputFile.parentFile?.mkdirs()
            downloadBody.byteStream().use { input ->
                outputFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)  // 64KB
                    var read: Int
                    var downloaded = 0L
                    var lastLogTime = System.currentTimeMillis()
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        val pct = (downloaded / totalBytes).coerceIn(0f, 1f)
                        onProgress(pct)
                        // Log every 2 seconds OR when complete
                        val now = System.currentTimeMillis()
                        if (now - lastLogTime > 2000 || downloaded.toLong() == totalBytes.toLong()) {
                            WepWawetLogger.proxy("$TAG download progress",
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
            WepWawetLogger.proxy("$TAG.download COMPLETE",
                "file" to outputFile.name,
                "file_size" to outputFile.length(),
                "absolute_path" to outputFile.absolutePath)
            return@withContext Result.Success(outputFile)
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG download stream failed",
                "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            return@withContext Result.Failure("Download stream failed: ${e.message}", e)
        }
    }

    /**
     * Extract file_url from the IPublishedFileService.GetDetails JSON response.
     * Returns null if empty or not found.
     */
    private fun extractFileUrl(json: String): String? {
        // Simple regex-based parse to avoid Gson dependency
        val regex = Regex("\"file_url\"\\s*:\\s*\"([^\"]+)\"")
        val match = regex.find(json)
        return match?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private fun extractTitle(json: String): String? {
        val regex = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"")
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun extractFileSize(json: String): Long {
        val regex = Regex("\"file_size\"\\s*:\\s*\"?(\\d+)\"?")
        return regex.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }

    private fun extractType(json: String): String? {
        // Type is in tags array, look for "Video", "Scene", "Image", etc.
        val regex = Regex("\"tag\"\\s*:\\s*\"(Scene|Video|Image|Web|Application|Playlist)\"")
        return regex.find(json)?.groupValues?.get(1)
    }
}
