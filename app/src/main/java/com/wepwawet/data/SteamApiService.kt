package com.wepwawet.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonArray
// TypeToken import removed — TypeToken<T>() {} anonymous subclasses cause R8
// stripping of the generic Signature attribute, which crashes at <clinit>
// time with 'TypeToken must be created with a type argument'. Use explicit
// Class<T> for fromJson instead (as the methods below already do).
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
 * Public methods:
 *   - [browseWorkshop]            : scrape steamcommunity.com/workshop/browse HTML for item IDs
 *   - [browseCreatorWorkshop]     : scrape a creator's /profiles/<id>/myworkshop page
 *   - [searchWorkshop]            : scrape the browse HTML with `searchtext=` query param
 *   - [getPublishedFileDetails]   : POST to ISteamRemoteStorage for full metadata
 *   - [resolveCreatorName]        : scrape a creator's profile page for the display name
 *
 * Anonymous access confirmed for both endpoints — no Steam Web API key needed.
 *
 * All calls are logged via [WepWawetLogger] (STEAM domain).
 */
class SteamApiService(
    private val httpClient: OkHttpClient = defaultClient(),
    private val gson: Gson = defaultGson(),
    /** Steam Web API key for IPublishedFileService/QueryFiles (server-side sorting). */
    var apiKey: String? = null,
) {

    /**
     * In-memory cache of resolved creator display names (steamId64 → displayName).
 *
     * Populated by [resolveCreatorName] and read by [WorkshopRepository] when
     * surfacing creator names on wallpaper tiles. We never evict — the cache is
     * bounded by the number of distinct creators the user has browsed, which is
     * realistically a few hundred at most.
     */
    private val creatorNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Cursor for IPublishedFileService/QueryFiles pagination. */
    @Volatile
    private var _queryFilesCursor: String? = null

    /** Reset the cursor (call when starting a new browse session). */
    fun resetQueryFilesCursor() {
        _queryFilesCursor = null
    }

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
        sort: Sort = Sort.MOST_POPULAR_YEAR,
        page: Int = 1,
        tags: List<String> = emptyList(),
        excludedTags: List<String> = emptyList(),
        mobileCompatible: Boolean = true,
    ): Result<List<WorkshopItemTyped>> = withContext(Dispatchers.IO) {
        WepWawetLogger.steam("browseWorkshop start",
            "sort" to sort.browseParam,
            "trend_days" to sort.trendDays,
            "page" to page,
            "tags" to tags.joinToString(","),
            "excluded_tags" to excludedTags.joinToString(","),
            "mobile_compatible" to mobileCompatible)
        val idsResult = fetchWorkshopIds(
            sort = sort, page = page, tags = tags,
            excludedTags = excludedTags, mobileCompatible = mobileCompatible, query = null,
        )
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
     * Query the Steam Workshop using IPublishedFileService/QueryFiles API.
     *
     * This is the SAME API that Wallpaper Engine PC uses internally. It supports
     * ALL sort types server-side (including RankedByVotesUp and
     * RankedByTotalUniqueSubscriptions) and returns full item metadata in a single
     * call (no separate GetPublishedFileDetails needed).
     *
     * Requires [apiKey] to be set (obtainable from steamcommunity.com/dev/apikey).
     *
     * @param sort browse sort mode (uses [Sort.queryType] for the query_type param)
     * @param page 1-indexed page number (max 1000)
     * @param tags required tags (AND logic)
     * @param excludedTags tags to exclude
     * @param mobileCompatible if true, adds required_kv_tags[app_workshop_eula_version]=3
     * @param query optional search text (uses query_type=RankedByTextSearch)
     */
    suspend fun queryFiles(
        sort: Sort,
        page: Int = 1,
        tags: List<String> = emptyList(),
        excludedTags: List<String> = emptyList(),
        mobileCompatible: Boolean = true,
        query: String? = null,
    ): Result<List<WorkshopItemTyped>> = withContext(Dispatchers.IO) {
        val key = apiKey
        if (key.isNullOrBlank()) {
            WepWawetLogger.proxyWarn("queryFiles: no API key set — falling back to HTML scraping")
            return@withContext Result.failure(SteamApiException.Network("queryFiles",
                IllegalStateException("No Steam Web API key set")))
        }

        WepWawetLogger.steam("queryFiles start",
            "query_type" to sort.queryType,
            "sort" to sort.displayName,
            "page" to page,
            "tags" to tags.joinToString(","),
            "excluded_tags" to excludedTags.joinToString(","),
            "mobile_compatible" to mobileCompatible,
            "query" to (query ?: "none"))

        try {
            // Build query URL — IPublishedFileService/QueryFiles is a GET request
            val urlBuilder = StringBuilder("$BASE_URL/IPublishedFileService/QueryFiles/v1/")
            urlBuilder.append("?key=").append(URLEncoder.encode(key, "UTF-8"))
            // Use search query_type if searching, otherwise use the sort's queryType
            val effectiveQueryType = if (query != null) Sort.MOST_RELEVANT.queryType else sort.queryType
            urlBuilder.append("&query_type=").append(effectiveQueryType)
            urlBuilder.append("&page=").append(page)
            // Cursor-based pagination: use next_cursor from previous response
            // For first page, cursor=* means "start from beginning"
            urlBuilder.append("&cursor=").append(URLEncoder.encode(_queryFilesCursor ?: "*", "UTF-8"))
            urlBuilder.append("&numperpage=").append(PAGE_SIZE)
            urlBuilder.append("&appid=").append(WE_APP_ID)
            urlBuilder.append("&creator_appid=").append(WE_APP_ID)
            // Required tags — each tag as a separate repeated field
            tags.forEach { tag ->
                urlBuilder.append("&requiredtags=").append(URLEncoder.encode(tag, "UTF-8"))
            }
            // Excluded tags — ALWAYS exclude Web and Application (not mobile-compatible)
            // Each tag as a separate repeated field
            val allExcludedTags = excludedTags.toMutableList()
            if ("Application" !in allExcludedTags) allExcludedTags.add("Application")
            if ("Web" !in allExcludedTags) allExcludedTags.add("Web")
            allExcludedTags.forEach { tag ->
                urlBuilder.append("&excludedtags=").append(URLEncoder.encode(tag, "UTF-8"))
            }
            // Mobile compatible filter: required_kv_tags[app_workshop_eula_version]=3
            if (mobileCompatible) {
                urlBuilder.append("&required_kv_tags%5B").append(MOBILE_COMPAT_KVS_KEY)
                    .append("%5D=").append(MOBILE_COMPAT_KVS_VALUE)
            }
            // Trend days (only for RankedByTrend)
            if (sort.queryType == 3 && sort.trendDays > 0) {
                urlBuilder.append("&days=").append(sort.trendDays)
            }
            // Search text
            query?.let {
                urlBuilder.append("&search_text=").append(URLEncoder.encode(it, "UTF-8"))
            }
            // Return full details
            urlBuilder.append("&return_vote_data=true")
            urlBuilder.append("&return_tags=true")
            urlBuilder.append("&return_kv_tags=true")
            urlBuilder.append("&return_previews=true")
            urlBuilder.append("&return_short_description=true")
            urlBuilder.append("&match_all_tags=true")

            val url = urlBuilder.toString()
            WepWawetLogger.steamDebug("queryFiles GET", "url" to url.take(200))

            val request = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                WepWawetLogger.proxyWarn("queryFiles HTTP error",
                    "code" to response.code, "url" to url.take(200))
                response.close()
                return@withContext Result.failure(
                    SteamApiException.HttpError(response.code, "queryFiles")
                )
            }
            val jsonStr = response.body?.string()
                ?: return@withContext Result.failure(SteamApiException.EmptyResponse())

            // Parse the QueryFiles response
            val responseObj = gson.fromJson(jsonStr, JsonObject::class.java)
            val responseInner = responseObj?.getAsJsonObject("response")
                ?: return@withContext Result.failure(SteamApiException.ParseError("queryFiles parse failed", IllegalStateException("No response object in JSON")))

            val total = responseInner.get("total")?.asInt ?: 0
            // Read next_cursor for pagination
            val nextCursor = responseInner.get("next_cursor")?.asString
            // Update the cursor for the next page
            if (!nextCursor.isNullOrBlank()) {
                _queryFilesCursor = nextCursor
            }
            val fileList = responseInner.getAsJsonArray("publishedfiledetails")
                ?: return@withContext Result.success(emptyList())

            WepWawetLogger.steamDebug("queryFiles response",
                "total" to total, "items" to fileList.size())

            if (fileList.size() == 0) {
                WepWawetLogger.steam("queryFiles no results",
                    "total" to total, "page" to page)
                return@withContext Result.success(emptyList())
            }

            // Parse each item from the QueryFiles response (same format as GetPublishedFileDetails)
            val items = fileList.mapNotNull { element ->
                try {
                    val item = gson.fromJson(element, WorkshopItem::class.java)
                    val typed = WorkshopItemTyped(item, WallpaperType.fromTags(item.tags))
                    typed
                } catch (e: Exception) {
                    WepWawetLogger.proxyWarn("queryFiles parse error for item",
                        "exception" to e.message.orEmpty())
                    null
                }
            }

            WepWawetLogger.steam("queryFiles done",
                "items" to items.size, "total" to total, "page" to page)
            Result.success(items)
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("queryFiles failed",
                "exception" to e.javaClass.name, "message" to (e.message.orEmpty()))
            Result.failure(SteamApiException.Network("queryFiles", e))
        }
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
        tags: List<String> = emptyList(),
        excludedTags: List<String> = emptyList(),
        mobileCompatible: Boolean = true,
    ): Result<List<WorkshopItemTyped>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            return@withContext Result.success(emptyList())
        }
        WepWawetLogger.steam("searchWorkshop start",
            "query" to query, "page" to page,
            "tags" to tags.joinToString(","),
            "excluded_tags" to excludedTags.joinToString(","))
        val idsResult = fetchWorkshopIds(
            sort = Sort.MOST_RELEVANT,
            page = page,
            tags = tags,
            excludedTags = excludedTags,
            mobileCompatible = mobileCompatible,
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
     * Browse a specific creator's workshop items.
     *
     * URL: `https://steamcommunity.com/profiles/<steamId>/myworkshop/?appid=431960&browsesort=toprated&p=<page>`
     *
     * This reuses the [extractWorkshopIdsFromHtml] scraper against the creator's
     * personal workshop page, then bulk-fetches the metadata via
     * [getPublishedFileDetails]. Steam always uses `toprated` for the creator
     * workshop listing — the user can't change the sort here.
     *
     * Used by [com.wepwawet.ui.screens.WorkshopBrowserScreen] when the user
     * taps a creator name on a wallpaper tile to see only that creator's items.
     *
     * @param steamId the creator's Steam ID64 (17-digit number)
     * @param page    1-indexed page number
     */
    suspend fun browseCreatorWorkshop(
        steamId: String,
        page: Int = 1,
    ): Result<List<WorkshopItemTyped>> = withContext(Dispatchers.IO) {
        if (steamId.isBlank()) {
            return@withContext Result.success(emptyList())
        }
        WepWawetLogger.steam("browseCreatorWorkshop start",
            "steam_id" to steamId, "page" to page)
        val idsResult = fetchCreatorWorkshopIds(steamId = steamId, page = page)
        val ids = idsResult.getOrElse { e ->
            return@withContext Result.failure(e)
        }
        if (ids.isEmpty()) {
            WepWawetLogger.steam("browseCreatorWorkshop no results",
                "steam_id" to steamId, "page" to page)
            return@withContext Result.success(emptyList())
        }
        val typed = getPublishedFileDetails(ids).getOrElse { e ->
            return@withContext Result.failure(e)
        }
        WepWawetLogger.steam("browseCreatorWorkshop done",
            "steam_id" to steamId,
            "ids" to ids.size, "items" to typed.size, "page" to page)
        Result.success(typed)
    }

    /**
     * Resolve a Steam ID64 to a display name by scraping the profile page HTML.
     *
     * Cached in-memory ([creatorNameCache]) to avoid repeat lookups.
     *
     * URL: `https://steamcommunity.com/profiles/<steamId64>`
     *
     * The display name is found in any of:
     *   - `<span class="actual_subtitle_text">(.*?)</span>` (the in-page subtitle)
     *   - `<title>Steam Community :: (.*?)</title>` (page title fallback)
     *   - `data-miniprofile=".*?">(.*?)</a>` (the creator name link text — appears
     *     on workshop browse pages where the author name is rendered as a link)
     *
     * If none match (e.g. profile is private, Steam returns a different markup,
     * or the network call fails), the raw Steam ID is returned as a fallback —
     * the UI shows the raw ID rather than nothing.
     *
     * @param steamId the creator's Steam ID64 (17-digit number)
     * @return the resolved display name, or the raw ID on any failure
     */
    suspend fun resolveCreatorName(steamId: String): Result<String> =
        withContext(Dispatchers.IO) {
            if (steamId.isBlank()) {
                return@withContext Result.success(steamId)
            }
            // Cache hit
            creatorNameCache[steamId]?.let {
                WepWawetLogger.steamDebug("resolveCreatorName cache hit",
                    "steam_id" to steamId, "name" to it)
                return@withContext Result.success(it)
            }

            WepWawetLogger.steam("resolveCreatorName start",
                "steam_id" to steamId)
            try {
                val url = "$PROFILE_URL$steamId"
                WepWawetLogger.steamDebug("resolveCreatorName GET",
                    "url" to url)
                val request = Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    WepWawetLogger.proxyWarn("resolveCreatorName HTTP error",
                        "code" to response.code, "steam_id" to steamId)
                    // Fall back to the raw ID — UI can still show something
                    creatorNameCache[steamId] = steamId
                    return@withContext Result.success(steamId)
                }
                val html = response.body?.string().orEmpty()
                if (html.isBlank()) {
                    WepWawetLogger.proxyWarn("resolveCreatorName empty body",
                        "steam_id" to steamId)
                    // DON'T cache the raw ID — a transient empty response shouldn't
                    // permanently pin the raw numeric ID as the creator's name.
                    // Future requests will retry the lookup.
                    return@withContext Result.success(steamId)
                }

                val name = extractCreatorNameFromHtml(html)
                val resolved = name ?: steamId
                // Only cache if we actually found a name — don't pin raw ID on
                // a "not found in HTML" result, which might be a transient issue
                // (private profile, Steam rate limit, etc.)
                if (name != null) {
                    creatorNameCache[steamId] = resolved
                }
                WepWawetLogger.steam("resolveCreatorName done",
                    "steam_id" to steamId,
                    "resolved" to resolved,
                    "found_in_html" to (name != null))
                Result.success(resolved)
            } catch (e: Exception) {
                WepWawetLogger.proxyWarn("resolveCreatorName failed",
                    "steam_id" to steamId,
                    "exception" to e.javaClass.name,
                    "message" to (e.message ?: ""))
                // DON'T cache on transient failure — return the raw ID as a
                // fallback for this call, but don't pin it in the cache so
                // future requests will retry the lookup.
                Result.success(steamId)
            }
        }

    /**
     * Extract the display name from a Steam profile page HTML using a series
     * of regexes — we try each in order and return the first non-blank match.
     */
    private fun extractCreatorNameFromHtml(html: String): String? {
        // Try several patterns — order matters because Steam serves different
        // markup for public vs. private profiles, and changes occasionally.
        val patterns = listOf(
            // `<span class="actual_subtitle_text">Display Name</span>`
            Regex("<span class=\"actual_subtitle_text\">(.*?)</span>"),
            // `<title>Steam Community :: Display Name</title>`
            Regex("<title>Steam Community :: (.*?)</title>"),
            // `data-miniprofile="…">Display Name</a>` (workshop browse pages)
            Regex("data-miniprofile=\".*?\">(.*?)</a>"),
            // `<div class="header_real_name ellipsis">Display Name</div>`
            Regex("<div class=\"header_real_name[^>]*>(.*?)</div>"),
        )
        for (pattern in patterns) {
            val match = pattern.find(html)
            val raw = match?.groupValues?.getOrNull(1)?.trim()
            if (!raw.isNullOrEmpty()) {
                // Strip nested HTML tags (e.g. the title fallback may include
                // additional markup) and HTML-unescape common entities.
                val cleaned = raw
                    .replace(Regex("<[^>]+>"), "")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("&apos;", "'")
                    .replace("&nbsp;", " ")
                    .trim()
                if (cleaned.isNotEmpty()) return cleaned
            }
        }
        return null
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
        excludedTags: List<String> = emptyList(),
        mobileCompatible: Boolean = true,
        query: String?,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val url = buildString {
                append("$BROWSE_URL?appid=$WE_APP_ID")
                append("&browsesort=${sort.browseParam}")
                append("&p=$page")
                append("&numperpage=$PAGE_SIZE")
                // trend_days — use the Sort enum's trendDays field
                if (sort.browseParam == "trend" && sort.trendDays > 0) {
                    append("&days=${sort.trendDays}")
                }
                // requiredtags[] — multiple tags with AND logic
                tags.forEach { tag ->
                    append("&requiredtags[]=${URLEncoder.encode(tag, "UTF-8")}")
                }
                // excludedtags[] — ALWAYS exclude Web and Application (not mobile-compatible)
                val allExcludedTags = excludedTags.toMutableList()
                if ("Application" !in allExcludedTags) allExcludedTags.add("Application")
                if ("Web" !in allExcludedTags) allExcludedTags.add("Web")
                allExcludedTags.forEach { tag ->
                    append("&excludedtags[]=${URLEncoder.encode(tag, "UTF-8")}")
                }
                // Mobile-compatible filter: adds kvs[app_workshop_eula_version]=3
                // This is the AUTHOR PERMISSION FLAG, not a type check.
                if (mobileCompatible) {
                    append("&kvs%5B${MOBILE_COMPAT_KVS_KEY}%5D=${MOBILE_COMPAT_KVS_VALUE}")
                }
                query?.let {
                    append("&searchtext=${URLEncoder.encode(it, "UTF-8")}")
                }
            }
            fetchIdsFromUrl(url, page)
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("fetchWorkshopIds failed",
                "exception" to e.javaClass.name)
            Result.failure(SteamApiException.Network("browseWorkshop", e))
        }
    }

    /**
     * Low-level helper: GET a creator's workshop page and extract publishedfileids.
     * Same HTML scraping as [fetchWorkshopIds] but targets
     * `steamcommunity.com/profiles/<steamId>/myworkshop/`.
     *
     * Steam always uses `toprated` as the creator workshop browsesort; the user
     * cannot change it from the URL.
     */
    private suspend fun fetchCreatorWorkshopIds(
        steamId: String,
        page: Int,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val url = buildString {
                append("$PROFILE_URL$steamId/myworkshop/")
                append("?appid=$WE_APP_ID")
                append("&browsesort=toprated")
                append("&p=$page")
                append("&numperpage=$PAGE_SIZE")
            }
            fetchIdsFromUrl(url, page)
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("fetchCreatorWorkshopIds failed",
                "steam_id" to steamId,
                "exception" to e.javaClass.name)
            Result.failure(SteamApiException.Network("browseCreatorWorkshop", e))
        }
    }

    /**
     * Shared HTTP + scrape helper used by both [fetchWorkshopIds] and
     * [fetchCreatorWorkshopIds]. Performs the GET request and runs the regex
     * extraction, returning a list of workshop item IDs.
     */
    private suspend fun fetchIdsFromUrl(url: String, page: Int): Result<List<String>> =
        withContext(Dispatchers.IO) {
            try {
                WepWawetLogger.steamDebug("fetchIdsFromUrl GET", "url" to url)
                val request = Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    WepWawetLogger.proxyWarn("fetchIdsFromUrl HTTP error",
                        "code" to response.code, "url" to url)
                    return@withContext Result.failure(
                        SteamApiException.HttpError(response.code, "browseWorkshop")
                    )
                }
                val html = response.body?.string()
                    ?: return@withContext Result.failure(SteamApiException.EmptyResponse())
                val ids = extractWorkshopIdsFromHtml(html)
                WepWawetLogger.steamDebug("fetchIdsFromUrl parsed",
                    "ids_found" to ids.size, "page" to page)
                Result.success(ids)
            } catch (e: Exception) {
                WepWawetLogger.proxyWarn("fetchIdsFromUrl failed",
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
     * v0.5.0 UPDATE: Steam's /workshop/browse endpoint was upgraded to a React SSR app.
     * The old sort values `mostsubscribed` and `mostfavorited` are now SILENTLY IGNORED
     * by Steam and fall back to the default `trend` sort. Only 3 server-side sort modes
     * actually work:
     *
     *   - trend      : trending (last N days, default 7) — the new default
     *   - toprated   : effectively "most subscribed" (top result has 1.6M subs)
     *   - mostrecent : most recently created
     *
     * The `days` parameter (trend_days) is only meaningful with `browsesort=trend`.
     * Default trend_days=7; user can change to 1, 7, 30, or 90.
     *
     * `mostrelevant` is still used internally for search queries (searchtext param).
     *
     * v0.5.3 UPDATE: All sort options are now SERVER-SIDE via IPublishedFileService/QueryFiles.
     * No more client-side sorting — exact parity with Wallpaper Engine.
     *
     * The [queryType] maps to Steam's EPublishedFileQueryType enum:
     *   0  = RankedByVote
     *   1  = RankedByPublicationDate
     *   3  = RankedByTrend (uses [trendDays])
     *   10 = RankedByVotesUp
     *   11 = RankedByTextSearch
     *   12 = RankedByTotalUniqueSubscriptions
     *
     * Requires a Steam Web API key (obtainable from steamcommunity.com/dev/apikey).
     * When no key is set, falls back to HTML scraping (only trend/toprated/mostrecent
     * work — Most Up Votes and Most Subscriptions are disabled in the UI).
     *
     * Default sort = MOST_POPULAR_YEAR (trend with days=365), matching WE's default.
     */
    enum class Sort(
        val queryType: Int,       // EPublishedFileQueryType
        val browseParam: String,  // HTML browse fallback
        val displayName: String,
        val trendDays: Int = 0,
    ) {
        TOP_RATED(0, "toprated", "Top Rated"),
        MOST_POPULAR_YEAR(3, "trend", "Most Popular (Year)", trendDays = 365),
        MOST_POPULAR_MONTH(3, "trend", "Most Popular (Month)", trendDays = 31),
        MOST_POPULAR_WEEK(3, "trend", "Most Popular (Week)", trendDays = 7),
        MOST_POPULAR_TODAY(3, "trend", "Most Popular (Today)", trendDays = 1),
        MOST_RECENT(1, "mostrecent", "Most Recent"),
        MOST_UP_VOTES(11, "toprated", "Most Up Votes"),
        MOST_SUBSCRIPTIONS(9, "toprated", "Most Subscriptions"),
        MOST_RELEVANT(12, "mostrelevant", "Most Relevant"), // search only
        ;
    }

    companion object {
        const val BASE_URL = "https://api.steampowered.com"
        const val BROWSE_URL = "https://steamcommunity.com/workshop/browse"
        const val PROFILE_URL = "https://steamcommunity.com/profiles/"
    /** Key-value parameter for mobile-compatible filter (app_workshop_eula_version >= 3). */
    const val MOBILE_COMPAT_KVS_KEY = "app_workshop_eula_version"
    const val MOBILE_COMPAT_KVS_VALUE = "3"
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

                // Type coercion: Steam's QueryFiles API and GetPublishedFileDetails
                // API sometimes return the same field with different JSON types.
                // We normalize them here so plainGson's reflection-based binding
                // doesn't throw IllegalStateException.
                //
                // Known differences:
                // - banned: Boolean in QueryFiles, Int in GetPublishedFileDetails
                // - Various numeric fields might be returned as strings
                coerceTypes(obj)

                // Delegate to plain Gson's reflection-based binding (does NOT
                // re-invoke this adapter — plainGson has no custom adapters).
                return plainGson.fromJson(obj, WorkshopItem::class.java)
            }

            /**
             * Normalize JSON field types to match [WorkshopItem]'s declared types.
             * This prevents Gson from throwing IllegalStateException when the Steam
             * API returns a value in a different type than expected (e.g. boolean
             * for a field declared as Int).
             */
            private fun coerceTypes(obj: JsonObject) {
                // banned: can be Boolean (QueryFiles) or Int (GetPublishedFileDetails)
                // WorkshopItem.banned is Boolean — convert Int to Boolean if needed
                val bannedEl = obj.get("banned")
                if (bannedEl != null && bannedEl.isJsonPrimitive) {
                    val prim = bannedEl.asJsonPrimitive
                    if (prim.isNumber) {
                        obj.remove("banned")
                        obj.addProperty("banned", prim.asInt != 0)
                    }
                    // If it's already a boolean, leave it alone
                }

                // Remove the 'result' field if present — QueryFiles includes a
                // per-item 'result' field that WorkshopItem doesn't have.
                // Leaving it in is harmless (Gson ignores unknown fields by default),
                // but we strip it for cleanliness.
                obj.remove("result")

                // Ensure numeric fields that might come as strings are parsed correctly
                // Gson handles String→Long automatically, but just in case:
                listOf("subscriptions", "favorited", "views", "lifetime_subscriptions",
                    "lifetime_favorited", "file_size", "time_created", "time_updated",
                    "creator_app_id"
                ).forEach { fieldName ->
                    val el = obj.get(fieldName)
                    if (el != null && el.isJsonPrimitive && el.asJsonPrimitive.isString) {
                        // Convert string number to actual number
                        val strVal = el.asString
                        strVal.toLongOrNull()?.let { numVal ->
                            obj.remove(fieldName)
                            obj.addProperty(fieldName, numVal)
                        }
                    }
                }
            }
        }

        // NOTE: The previous `val WORKSHOP_ITEM_LIST_TYPE: Type = object : TypeToken<List<WorkshopItem>>() {}.type`
        // was REMOVED — it caused an IllegalStateException at <clinit> time because
        // R8 strips the generic Signature attribute from anonymous TypeToken
        // subclasses during minification, so Gson can't extract the type argument
        // via reflection. If bulk List<WorkshopItem> parsing is needed in the
        // future, use `gson.fromJson(json, Array<WorkshopItem>::class.java)` and
        // convert to a List, which avoids TypeToken entirely.
    }
}
