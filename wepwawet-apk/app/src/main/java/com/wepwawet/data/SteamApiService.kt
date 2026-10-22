package com.wepwawet.data

import com.wepwawet.data.api.SteamApiException
import com.wepwawet.data.api.SteamRemoteStorageApi
import com.wepwawet.data.model.WorkshopItem
import com.wepwawet.data.model.WorkshopItemTyped
import com.wepwawet.data.model.WallpaperType
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level service for Steam workshop data. Wraps the Retrofit [SteamRemoteStorageApi]
 * and adds:
 *   - Bulk fetch (any number of IDs, batched into chunks of 50)
 *   - HTML scraping of `steamcommunity.com/workshop/browse` for browse/search
 *   - Wraps all responses in [Result] with typed [SteamApiException] errors
 *   - Logs every call via [WepWawetLogger] (STEAM domain)
 *
 * TODO: implement IPublishedFileService/QueryFiles once user has provided their Steam Web API key
 * (in Settings -> Steam account -> Enable Web API key). That path is faster + more reliable
 * than HTML scraping but requires the key.
 */
@Singleton
class SteamApiService @Inject constructor(
    private val api: SteamRemoteStorageApi,
    private val httpClient: OkHttpClient,
) {

    /**
     * Bulk-fetch metadata for up to N workshop items. The Steam API accepts
     * itemcount=N + publishedfileids[0..N-1] — but Retrofit doesn't natively
     * support variable-arity Field arrays. So we build the form body manually
     * with OkHttp for the multi-item case (Retrofit's @Field can only do single-item).
     *
     * Returns a typed list of WorkshopItemTyped (with WallpaperType derived from tags).
     */
    suspend fun getBatchDetails(ids: List<String>): Result<List<WorkshopItemTyped>> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext Result.success(emptyList())

        val allResults = mutableListOf<WorkshopItem>()
        for (chunk in ids.chunked(BATCH_SIZE)) {
            try {
                val formBody = FormBody.Builder().apply {
                    add("itemcount", chunk.size.toString())
                    chunk.forEachIndexed { i, id -> add("publishedfileids[$i]", id) }
                }.build()
                val request = Request.Builder()
                    .url("$BASE_URL/ISteamRemoteStorage/GetPublishedFileDetails/v1/")
                    .post(formBody)
                    .build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        SteamApiException.HttpError(response.code, "GetPublishedFileDetails")
                    )
                }
                val body = response.body?.string()
                    ?: return@withContext Result.failure(SteamApiException.EmptyResponse())
                val parsed = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString<com.wepwawet.data.api.GetPublishedFileDetailsResponse>(body)
                allResults += parsed.response.publishedFileDetails
                WepWawetLogger.steamDebug("getBatchDetails chunk",
                    "chunk_size" to chunk.size,
                    "returned" to parsed.response.publishedFileDetails.size)
            } catch (e: Exception) {
                WepWawetLogger.steamDebug("getBatchDetails chunk failed",
                    "chunk_size" to chunk.size,
                    "exception" to e.javaClass.name)
                return@withContext Result.failure(
                    SteamApiException.Network("GetPublishedFileDetails chunk failed", e)
                )
            }
        }
        Result.success(allResults.map { WorkshopItemTyped(it, WallpaperType.fromTags(it.tags)) })
    }

    /**
     * Scrape the HTML workshop browse page for a list of publishedfileids.
     *
     * This is a fallback for users who haven't provided a Steam Web API key.
     * Once a key is provided in Settings, we should switch to IPublishedFileService/QueryFiles
     * which is faster + more reliable.
     *
     * Search parameters supported:
     *   - query text (search string)
     *   - tag filter (e.g. "Scene", "Video", ...)
     *   - sort (MostRecent / MostSubscribed / HighestRated)
     *   - pagination (page=1, 2, 3, ...)
     */
    suspend fun browseWorkshop(
        query: String? = null,
        tag: String? = null,
        sort: Sort = Sort.MOST_SUBSCRIBED,
        page: Int = 1,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val url = buildString {
                append("$BROWSE_URL?appid=$WE_APP_ID")
                sort.urlParam?.let { append("&$it") }
                tag?.let { append("&requiredtags[]=${java.net.URLEncoder.encode(it, "UTF-8")}") }
                query?.let { append("&searchtext=${java.net.URLEncoder.encode(it, "UTF-8")}") }
                append("&p=$page&numperpage=$PAGE_SIZE")
            }
            WepWawetLogger.steamDebug("browseWorkshop fetch", "url" to url)
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 WepWawet/0.1")
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    SteamApiException.HttpError(response.code, "browseWorkshop")
                )
            }
            val html = response.body?.string()
                ?: return@withContext Result.failure(SteamApiException.EmptyResponse())
            val ids = extractWorkshopIdsFromHtml(html)
            WepWawetLogger.steamDebug("browseWorkshop parsed", "ids_found" to ids.size, "page" to page)
            Result.success(ids)
        } catch (e: Exception) {
            Result.failure(SteamApiException.Network("browseWorkshop", e))
        }
    }

    /**
     * Extract publishedfileids from the workshop browse HTML.
     * The HTML contains lines like: data-publishedfileid="1234567890"
     */
    private fun extractWorkshopIdsFromHtml(html: String): List<String> {
        val ids = mutableListOf<String>()
        val regex = Regex("data-publishedfileid=\"(\\d+)\"")
        regex.findAll(html).forEach { match ->
            val id = match.groupValues[1]
            if (id !in ids) ids.add(id)
        }
        return ids
    }

    enum class Sort(val urlParam: String?) {
        MOST_RECENT("sort=mostrecent"),
        MOST_SUBSCRIBED("sort=trend&browsesort=trend"),  // WE sorts "trend" by default
        HIGHEST_RATED("sort=score"),
        TOTAL_SUBSCRIBERS("sort=totaluniquesubscribers"),
    }

    companion object {
        const val BASE_URL = "https://api.steampowered.com"
        const val BROWSE_URL = "https://steamcommunity.com/workshop/browse"
        const val WE_APP_ID = 431960L
        const val BATCH_SIZE = 50
        const val PAGE_SIZE = 50
    }
}
