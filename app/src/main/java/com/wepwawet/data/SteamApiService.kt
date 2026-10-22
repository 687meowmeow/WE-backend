package com.wepwawet.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.reflect.TypeToken
import com.wepwawet.data.api.GetPublishedFileDetailsResponse
import com.wepwawet.data.api.SteamApiException
import com.wepwawet.data.model.WallpaperType
import com.wepwawet.data.model.WorkshopItem
import com.wepwawet.data.model.WorkshopItemTyped
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.lang.reflect.Type
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * High-level service for Steam workshop data. All HTTP is done with OkHttp + Gson
 * (no Retrofit — Retrofit can't represent the variable-arity `publishedfileids[N]`
 * form fields that the GetPublishedFileDetails endpoint requires).
 *
 * Three public methods (per Task 2):
 *   - [browseWorkshop] : scrape steamcommunity.com/workshop/browse HTML for item IDs
 *   - [getPublishedFileDetails] : POST to ISteamRemoteStorage for full metadata
 *   - [searchWorkshop] : scrape the browse HTML with `searchtext=` query param
 *
 * Anonymous access confirmed for both endpoints — no Steam Web API key needed.
 *
 * All calls are logged via [WepWawetLogger] (STEAM domain).
 */
class SteamApiService(
    private val httpClient: OkHttpClient = defaultClient(),
    private val gson: Gson = defaultGson(),
) {

    /**
     * Bulk-fetch metadata for up to N workshop items via the
     * ISteamRemoteStorage/GetPublishedFileDetails/v1/ endpoint.
     *
     * The Steam API accepts `itemcount=N` + `publishedfileids[0..N-1]` — we build the
     * FormBody manually with OkHttp because Retrofit's @Field can only do fixed arity.
     *
     * Returns a typed list of [WorkshopItemTyped] (with [WallpaperType] derived from tags).
     */
    suspend fun getPublishedFileDetails(ids: List<String>): Result<List<WorkshopItemTyped>> =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext Result.success(emptyList())

            WepWawetLogger.steam("getPublishedFileDetails start",
                "id_count" to ids.size)

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
                        .header("User-Agent", USER_AGENT)
                        .build()

                    WepWawetLogger.steamDebug("getPublishedFileDetails POST",
                        "url" to request.url.toString(),
                        "chunk_size" to chunk.size)

                    val response = httpClient.newCall(request).execute()
                    if (!response.isSuccessful) {
                        WepWawetLogger.proxyWarn(
                            "getPublishedFileDetails HTTP error",
                            "code" to response.code,
                            "chunk_size" to chunk.size,
                        )
                        return@withContext Result.failure(
                            SteamApiException.HttpError(response.code, "GetPublishedFileDetails")
                        )
                    }
                    val body = response.body?.string()
                        ?: return@withContext Result.failure(SteamApiException.EmptyResponse())

                    val parsed: GetPublishedFileDetailsResponse = try {
                        gson.fromJson(body, GetPublishedFileDetailsResponse::class.java)
                            ?: return@withContext Result.failure(SteamApiException.EmptyResponse())
                    } catch (e: Exception) {
                        WepWawetLogger.proxyWarn("getPublishedFileDetails parse error",
                            "exception" to e.javaClass.name, "message" to (e.message ?: ""))
                        return@withContext Result.failure(
                            SteamApiException.ParseError("GetPublishedFileDetails parse failed", e)
                        )
                    }

                    val details = parsed.response.publishedFileDetails
                    allResults += details
                    WepWawetLogger.steamDebug("getPublishedFileDetails chunk parsed",
                        "chunk_size" to chunk.size,
                        "returned" to details.size,
                        "result_code" to parsed.response.result)
                } catch (e: Exception) {
                    WepWawetLogger.proxyWarn("getPublishedFileDetails chunk failed",
                        "chunk_size" to chunk.size,
                        "exception" to e.javaClass.name)
                    return@withContext Result.failure(
                        SteamApiException.Network("GetPublishedFileDetails chunk failed", e)
                    )
                }
            }

            val typed = allResults.map {
                WorkshopItemTyped(it, WallpaperType.fromTags(it.tags))
            }
            WepWawetLogger.steam("getPublishedFileDetails done",
                "requested" to ids.size,
                "returned" to typed.size)
            Result.success(typed)
        }

    /**
     * Scrape the HTML workshop browse page for a list of full [WorkshopItemTyped] objects.
     *
     * Two-step process:
     *   1. GET https://steamcommunity.com/workshop/browse/?appid=431960&...
     *      → extract publishedfileids from the HTML via regex
     *   2. POST to GetPublishedFileDetails for full metadata
     *
     * The Steam API does NOT require a key for either step.
     *
     * @param sort browse sort mode (mostrecent / mostsubscribed / mostfavorited / trend)
     * @param page 1-indexed page number
     * @param tags optional required tags (e.g. ["Video"], ["Scene"])
     */
    suspend fun browseWorkshop(
        sort: Sort = Sort.MOST_SUBSCRIBED,
        page: Int = 1,
        tags: List<String> = emptyList(),
    ): Result<List<WorkshopItemTyped>> = withContext(Dispatchers.IO) {
        WepWawetLogger.steam("browseWorkshop start",
            "sort" to sort.browseParam,
            "page" to page,
            "tags" to tags.joinToString(","))
        val idsResult = fetchWorkshopIds(sort = sort, page = page, tags = tags, query = null)
        val ids = idsResult.getOrElse { e ->
            return@withContext Result.failure(e)
        }
        if (ids.isEmpty()) {
            WepWawetLogger.steam("browseWorkshop no results",
                "sort" to sort.browseParam, "page" to page)
            return@withContext Result.success(emptyList())
        }
        val typed = getPublishedFileDetails(ids).getOrElse { e ->
            return@withContext Result.failure(e)
        }
        WepWawetLogger.steam("browseWorkshop done",
            "ids" to ids.size, "items" to typed.size, "page" to page)
        Result.success(typed)
    }

    /**
     * Search the workshop by free-text query.
     *
     * This reuses the browse HTML scraper with `searchtext=<query>` — the Steam
     * IPublishedFileService/QueryFiles endpoint would be more reliable but needs an
     * API key.
     *
     * @param query search string (e.g. "Cyberpunk")
     * @param page 1-indexed page number
     */
    suspend fun searchWorkshop(
        query: String,
        page: Int = 1,
    ): Result<List<WorkshopItemTyped>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            return@withContext Result.success(emptyList())
        }
        WepWawetLogger.steam("searchWorkshop start",
            "query" to query, "page" to page)
        val idsResult = fetchWorkshopIds(
            sort = Sort.MOST_RELEVANT,
            page = page,
            tags = emptyList(),
            query = query,
        )
        val ids = idsResult.getOrElse { e ->
            return@withContext Result.failure(e)
        }
        if (ids.isEmpty()) {
            WepWawetLogger.steam("searchWorkshop no results",
                "query" to query, "page" to page)
            return@withContext Result.success(emptyList())
        }
        val typed = getPublishedFileDetails(ids).getOrElse { e ->
            return@withContext Result.failure(e)
        }
        WepWawetLogger.steam("searchWorkshop done",
            "query" to query, "ids" to ids.size, "items" to typed.size)
        Result.success(typed)
    }

    /**
     * Low-level helper: GET the workshop browse HTML and extract publishedfileids via regex.
     * The Steam browse page contains anchor tags like:
     *   `<a class="ugc" href="https://steamcommunity.com/sharedfiles/filedetails/?id=1234567890">`
     * and occasionally inline `data-publishedfileid="1234567890"` attributes.
     */
    private suspend fun fetchWorkshopIds(
        sort: Sort,
        page: Int,
        tags: List<String>,
        query: String?,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val url = buildString {
                append("$BROWSE_URL?appid=$WE_APP_ID")
                append("&browsesort=${sort.browseParam}")
                append("&p=$page")
                append("&numperpage=$PAGE_SIZE")
                tags.forEach { tag ->
                    append("&requiredtags[]=${URLEncoder.encode(tag, "UTF-8")}")
                }
                query?.let {
                    append("&searchtext=${URLEncoder.encode(it, "UTF-8")}")
                }
            }
            WepWawetLogger.steamDebug("fetchWorkshopIds GET", "url" to url)
            val request = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                WepWawetLogger.proxyWarn("fetchWorkshopIds HTTP error",
                    "code" to response.code, "url" to url)
                return@withContext Result.failure(
                    SteamApiException.HttpError(response.code, "browseWorkshop")
                )
            }
            val html = response.body?.string()
                ?: return@withContext Result.failure(SteamApiException.EmptyResponse())
            val ids = extractWorkshopIdsFromHtml(html)
            WepWawetLogger.steamDebug("fetchWorkshopIds parsed",
                "ids_found" to ids.size, "page" to page)
            Result.success(ids)
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("fetchWorkshopIds failed",
                "exception" to e.javaClass.name)
            Result.failure(SteamApiException.Network("browseWorkshop", e))
        }
    }

    /**
     * Extract publishedfileids from the workshop browse HTML.
     *
     * Steam's HTML uses a few patterns for the same ID:
     *   1. `<a class="ugc" href="https://steamcommunity.com/sharedfiles/filedetails/?id=123">`
     *   2. `data-publishedfileid="123"`
     *   3. `<div class="workshopItemAuthorName" ... data-publishedfileid="123">`
     *
     * We try multiple regexes to be robust against Steam markup changes. Duplicates are
     * filtered (a single item can appear multiple times in the HTML).
     */
    private fun extractWorkshopIdsFromHtml(html: String): List<String> {
        val ids = mutableListOf<String>()
        val regexes = listOf(
            Regex("sharedfiles/filedetails/\\?id=(\\d+)"),
            Regex("data-publishedfileid=\"(\\d+)\""),
            Regex("data-publishedfileid='(\\d+)'"),
        )
        for (regex in regexes) {
            regex.findAll(html).forEach { match ->
                val id = match.groupValues[1]
                if (id.length >= 4 && id !in ids) ids.add(id)
            }
        }
        return ids
    }

    /**
     * Browse sort modes.
     *
     * These map directly to Steam's `browsesort` URL parameter:
     *   - mostrecent    : most recently created
     *   - mostsubscribed: most subscribers (all-time)
     *   - mostfavorited : most favorited (all-time)
     *   - trend         : trending (last 90 days)
     *   - mostrelevant  : for search queries (most relevant)
     */
    enum class Sort(val browseParam: String, val displayName: String) {
        MOST_RECENT("mostrecent", "Most Recent"),
        MOST_SUBSCRIBED("mostsubscribed", "Most Subscribed"),
        MOST_FAVORITED("mostfavorited", "Most Favorited"),
        TRENDING("trend", "Trending"),
        MOST_RELEVANT("mostrelevant", "Most Relevant"),
    }

    companion object {
        const val BASE_URL = "https://api.steampowered.com"
        const val BROWSE_URL = "https://steamcommunity.com/workshop/browse"
        const val WE_APP_ID = 431960L
        const val BATCH_SIZE = 50            // Steam API limit per request
        const val PAGE_SIZE = 30             // Steam browse HTML page size
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; WepWawet/0.1) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** Default OkHttp client. */
        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }

        /**
         * Default Gson instance — registered with a custom deserializer for
         * [WorkshopItem] that flattens Steam's `[{tag:"X"},{tag:"Y"}]` array
         * into the `List<String>` field.
         */
        fun defaultGson(): Gson {
            return GsonBuilder()
                .registerTypeAdapter(WorkshopItem::class.java, WorkshopItemDeserializer())
                .create()
        }

        /**
         * Custom Gson deserializer for [WorkshopItem].
         *
         * Why: Steam's API returns `tags` as an array of objects:
         *   `"tags":[{"tag":"Video","internal_name":""}, ...]`
         * We want `tags: List<String>` on the data class for ergonomics, so we
         * flatten the objects into plain strings here.
         *
         * Everything else on [WorkshopItem] is delegated to Gson's reflection-based
         * deserialization via a separate plain Gson instance — this is critical:
         * calling `context.deserialize(...)` from inside a JsonDeserializer would
         * re-invoke our own adapter and recurse infinitely.
         */
        private class WorkshopItemDeserializer : JsonDeserializer<WorkshopItem> {
            // Plain Gson instance with no custom adapters — used for delegate
            // deserialization to avoid infinite recursion.
            private val plainGson: Gson = Gson()

            override fun deserialize(
                json: JsonElement,
                typeOfT: Type,
                context: JsonDeserializationContext,
            ): WorkshopItem {
                if (!json.isJsonObject) return WorkshopItem()

                val obj = json.asJsonObject.deepCopy()  // copy so we don't mutate the input
                // Rewrite tags:[{tag:"X"},{tag:"Y"}] → tags:["X","Y"]
                val tagsEl = obj.get("tags")
                if (tagsEl != null && tagsEl.isJsonArray) {
                    val flattened = JsonArray()
                    tagsEl.asJsonArray.forEach { tagEl ->
                        if (tagEl.isJsonObject) {
                            val tagStr = tagEl.asJsonObject.get("tag")
                            if (tagStr != null && !tagStr.isJsonNull) {
                                flattened.add(tagStr.asString)
                            }
                        } else if (tagEl.isJsonPrimitive) {
                            flattened.add(tagEl.asString)
                        }
                    }
                    obj.remove("tags")
                    obj.add("tags", flattened)
                }

                // Delegate to plain Gson's reflection-based binding (does NOT
                // re-invoke this adapter — plainGson has no custom adapters).
                return plainGson.fromJson(obj, WorkshopItem::class.java)
            }
        }

        /** Gson [TypeToken] for List<WorkshopItem> — used by future bulk calls. */
        val WORKSHOP_ITEM_LIST_TYPE: Type =
            object : TypeToken<List<WorkshopItem>>() {}.type
    }
}
