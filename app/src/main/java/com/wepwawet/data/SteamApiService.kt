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
import `in`.dragonbra.javasteam.protobufs.steamclient.Enums.EPublishedFileQueryType
import `in`.dragonbra.javasteam.steam.handlers.steamworkshop.GetUserFilesDetails
import `in`.dragonbra.javasteam.steam.handlers.steamworkshop.QueryFilesDetails
import com.wepwawet.pairing.WorkshopBrowseSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    @Volatile
    var apiKey: String? = null,
    /**
     * Android Context for the persistent creator-name cache (SharedPreferences).
     * Nullable for backward compatibility — when null, only the in-memory
     * cache is used (no persistence across app restarts).
     *
     * v0.5.11-alpha: added so creator names survive app restarts. The user's
     * log showed EVERY resolveCreatorName call returning HTTP 429 (rate
     * limit) on a fresh app launch — meaning the in-memory cache (which
     * starts empty) was useless. The persistent cache lets us avoid
     * re-resolving names the user has already seen in previous sessions.
     */
    private val appContext: android.content.Context? = null,
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

    /**
     * In-memory cache of resolved creator avatar URLs (steamId64 → avatar full URL).
     *
     * Populated as a side-effect of [getPlayerSummaries] (which already fetches
     * the `avatarfull` field from the GetPlayerSummaries response) and read by
     * the Favorites screen to render circular profile pictures without an
     * extra API call.
     */
    val creatorAvatarCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** SharedPreferences for persistent creator-name cache (v0.5.11-alpha). */
    private val creatorNamesPrefs: android.content.SharedPreferences? by lazy {
        appContext?.getSharedPreferences("wepwawet_creator_names", android.content.Context.MODE_PRIVATE)
    }

    /** True after we've loaded the persistent cache into memory (lazy on first read). */
    @Volatile
    private var persistentCacheLoaded: Boolean = false

    /**
     * Load the persistent creator-name cache into memory on first access.
     * Idempotent — subsequent calls are no-ops.
     */
    private fun loadPersistentCacheIfNeeded() {
        if (persistentCacheLoaded) return
        persistentCacheLoaded = true
        val prefs = creatorNamesPrefs ?: return
        try {
            val all = prefs.all
            for ((steamId, name) in all) {
                if (name is String && name.isNotBlank() && !creatorNameCache.containsKey(steamId)) {
                    creatorNameCache[steamId] = name
                }
            }
            if (all.isNotEmpty()) {
                WepWawetLogger.steamDebug("creatorNameCache loaded from persistent storage",
                    "count" to all.size)
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("creatorNameCache load persistent failed",
                "exception" to e.javaClass.name, "message" to (e.message ?: ""))
        }
    }

    /**
     * Persist a single creator name to SharedPreferences (fire-and-forget on
     * IO dispatcher — never blocks the UI).
     *
     * v0.7.4: launched on the shared WepWawetApplication.appScope instead of a
     * brand-new CoroutineScope(SupervisorJob()) per call — the old code leaked a
     * scope (and its Job) on every invocation. Also re-throws CancellationException
     * so cancellation propagates instead of being swallowed.
     */
    private fun persistCreatorName(steamId: String, name: String) {
        val prefs = creatorNamesPrefs ?: return
        com.wepwawet.WepWawetApplication.appScope.launch {
            try {
                prefs.edit().putString(steamId, name).apply()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                WepWawetLogger.proxyWarn("persistCreatorName failed",
                    "steam_id" to steamId, "exception" to e.javaClass.name)
            }
        }
    }

    /**
     * Cursor for IPublishedFileService/QueryFiles pagination.
     *
     * v0.5.32 (audit C5/M6 fix): this instance-level cursor cache was the
     * root cause of the "downloads in other menus not updating" /
     * "browse stops loading after JavaSteam→HTTP fallback" bug. The cache
     * was updated ONLY by the HTTP `queryFiles()` path (never by the
     * `queryFilesViaJavaSteam()` primary path). When the user browsed
     * pages 1-N via JavaSteam (cursor stays at the initial "*" from
     * startBrowse's reset) and then the JavaSteam path failed on page
     * N+1, the HTTP fallback ran with cursor="*" — Steam ignored the
     * `page=N+1` argument (per Steam docs: "When used [cursor], the
     * page parameter will be ignored") and returned page 1 results.
     * WorkshopRepository.applyClientFilters + distinctBy then stripped
     * all 30 items (already in _browseItems), consecutiveEmptyPages++
     * … after 20 such cycles hasMore=false and the user saw "no more
     * results" despite thousands more existing on Steam.
     *
     * Fix: drop the cursor cache entirely. `queryFiles()` now takes an
     * explicit `cursor` parameter (mirroring `queryFilesViaJavaSteam`).
     * When the caller passes a null/blank cursor, we OMIT `&cursor=`
     * from the URL so Steam uses the `&page=N` argument — making the
     * HTTP path consistent with the JavaSteam path (both use page-based
     * pagination). If a future caller wants cursor-based pagination,
     * they can pass a non-null cursor and track it themselves.
     */
    @Deprecated(
        "Cursor caching was removed in v0.5.32 (audit C5). " +
            "Pass cursor explicitly to queryFiles() instead.",
        level = DeprecationLevel.WARNING,
    )
    fun resetQueryFilesCursor() {
        // No-op — kept for source compatibility with WorkshopRepository
        // which calls this from startBrowse()/refresh(). Safe to remove
        // once those call sites are updated.
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
                        // v0.7.4: close the response so the connection returns to
                        // OkHttp's pool (the body was neither consumed nor closed
                        // on this path — under sustained 429s the pool exhausts).
                        response.close()
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // v0.7.4: coroutine cancellation MUST propagate — the old
                    // catch (e: Exception) converted it into Result.failure,
                    // breaking structured concurrency (a cancelled caller kept
                    // seeing "normal" failures and the loop kept making requests).
                    throw e
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
        // v0.5.32 (audit C5): cursor is now an explicit parameter (mirrors
        // queryFilesViaJavaSteam) instead of instance state. When null/blank,
        // we omit `&cursor=` from the URL so Steam uses `&page=N`. When
        // non-blank, we pass it and Steam ignores the page parameter.
        cursor: String? = null,
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
            "cursor" to (cursor ?: "page-mode"),
            "query" to (query ?: "none"))

        try {
            // Build query URL — IPublishedFileService/QueryFiles is a GET request
            val urlBuilder = StringBuilder("$BASE_URL/IPublishedFileService/QueryFiles/v1/")
            urlBuilder.append("?key=").append(URLEncoder.encode(key, "UTF-8"))
            // Use search query_type if searching, otherwise use the sort's queryType
            val effectiveQueryType = if (query != null) Sort.MOST_RELEVANT.queryType else sort.queryType
            urlBuilder.append("&query_type=").append(effectiveQueryType)
            urlBuilder.append("&page=").append(page)
            // v0.5.32 (audit C5): only append `&cursor=` when the caller
            // explicitly passed a non-blank cursor. Per Steam docs
            // (https://partner.steamgames.com/doc/webapi/IPublishedFileService):
            //   "Prefer [cursor] over using the page parameter … When used,
            //    the page parameter will be ignored."
            // So passing cursor="*" (as the old code did unconditionally)
            // makes Steam ignore page=N and always return page 1 — the
            // root cause of the "browse stops loading after fallback" bug.
            // Now we let Steam use page=N when the caller doesn't ask for
            // cursor-based pagination.
            if (!cursor.isNullOrBlank()) {
                urlBuilder.append("&cursor=").append(URLEncoder.encode(cursor, "UTF-8"))
            }
            urlBuilder.append("&numperpage=").append(pageSize)
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
            // v0.7.4: gate on the EFFECTIVE query type, not sort.queryType. When
            // a text search overrides query_type to RankedByTextSearch (12), the
            // old check still appended `&days=` if the browse sort happened to be
            // a trend sort — sending a trend-days parameter on a text-search
            // query. (Steam's docs: days is only meaningful for RankedByTrend,
            // range [1,7].)
            if (effectiveQueryType == 3 && sort.trendDays > 0) {  // queryType 3 = RankedByTrend; days range [1,7] per Steam docs
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
            // v0.5.32 (audit C5): read next_cursor only for logging — we
            // no longer cache it in instance state. The caller can pass
            // it explicitly to the next queryFiles() call if they want
            // cursor-based pagination.
            val nextCursor = responseInner.get("next_cursor")?.asString
            val fileList = responseInner.getAsJsonArray("publishedfiledetails")
                ?: return@withContext Result.success(emptyList())

            WepWawetLogger.steamDebug("queryFiles response",
                "total" to total, "items" to fileList.size(),
                "has_next_cursor" to (nextCursor?.isNotBlank() == true))

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
        } catch (e: kotlinx.coroutines.CancellationException) {
            // v0.7.4: re-throw — see getPublishedFileDetails for rationale.
            throw e
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("queryFiles failed",
                "exception" to e.javaClass.name, "message" to (e.message.orEmpty()))
            Result.failure(SteamApiException.Network("queryFiles", e))
        }
    }

    /**
     * Browse the Steam Workshop using the native Steam CM protocol via JavaSteam.
     *
     * This is the SAME call WE PC's `wallpaperui.exe` makes via `steam_api64.dll`'s
     * `ISteamUGC::SendQueryUGCRequest` — the ISteamPublishedFile.QueryFiles service RPC.
     * JavaSteam speaks the Steam CM protocol directly (not the HTTP API), so it bypasses
     * the IPublishedFileService HTTP API's [1,7] days limit for RankedByTrend.
     *
     * Achieves ABSOLUTE PARITY with WE PC:
     *  - Same wire protocol (byte-identical service call to Steam's CM servers)
     *  - Same `days` parameter behavior (any positive integer honored)
     *  - Same authentication context (user's Steam session, not an API key)
     *
     * This is the PRIMARY path for browse queries in
     * [com.wepwawet.data.WorkshopRepository.loadNextPageLocked]. The HTTP API +
     * HTML scraping paths are kept as fallbacks for when the JavaSteam session
     * is unavailable (user not logged in, Steam down, etc.).
     *
     * @param sort browse sort mode (uses [Sort.queryType] for the query_type param)
     * @param page 1-indexed page number (max 1000)
     * @param tags required tags (AND logic)
     * @param excludedTags tags to exclude (Application and Web are ALWAYS added)
     * @param mobileCompatible if true, adds required_kv_tags[app_workshop_eula_version]=3
     * @param query optional search text (uses query_type=RankedByTextSearch)
     * @param cursor pagination cursor — pass null for page-mode, or the `next_cursor` value
     *   from the previous response for cursor-mode (preferred by Steam — allows deep pagination)
     */
    suspend fun queryFilesViaJavaSteam(
        sort: Sort,
        page: Int = 1,
        tags: List<String> = emptyList(),
        excludedTags: List<String> = emptyList(),
        mobileCompatible: Boolean = true,
        query: String? = null,
        cursor: String? = null,
    ): Result<List<WorkshopItemTyped>> = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext Result.failure(
            SteamApiException.Network(
                "queryFilesViaJavaSteam",
                IllegalStateException("SteamApiService constructed without appContext — JavaSteam path unavailable"),
            )
        )

        val queryType = EPublishedFileQueryType.forNumber(sort.queryType)
            ?: return@withContext Result.failure(
                SteamApiException.Network(
                    "queryFilesViaJavaSteam",
                    IllegalArgumentException("Sort ${sort.name} has invalid queryType=${sort.queryType}"),
                )
            )

        // Always exclude Web and Application types (never mobile-compatible)
        val allExcludedTags = excludedTags.toMutableList().apply {
            if ("Application" !in this) add("Application")
            if ("Web" !in this) add("Web")
        }.toList()

        // Mobile-compatible filter: required_kv_tags[app_workshop_eula_version]=3
        // This is the AUTHOR PERMISSION FLAG (kvs), same as the HTTP API path.
        val requiredKvTags: List<Pair<String, String>> =
            if (mobileCompatible) listOf(MOBILE_COMPAT_KVS_KEY to MOBILE_COMPAT_KVS_VALUE)
            else emptyList()

        val details = QueryFilesDetails(
            appId = WE_APP_ID.toInt(),
            queryType = queryType,
            page = page,
            cursor = cursor,
            numPerPage = pageSize,
            requiredTags = tags,
            excludedTags = allExcludedTags,
            matchAllTags = true,
            searchText = query,
            // For RankedByTrend (query_type=3), pass the trendDays value. NO [1,7] limit
            // on this service path — Steam honors 365 for Year, 30 for Month, etc.
            days = if (sort.queryType == 3) sort.trendDays.toUInt() else null,
            requiredKvTags = requiredKvTags,
            returnVoteData = true,
            returnTags = true,
            returnKvTags = true,
            returnPreviews = true,
            returnShortDescription = true,
        )

        WorkshopBrowseSession.queryFiles(context, details)
    }

    /**
     * Browse a specific creator's published workshop files via the JavaSteam
     * `ISteamPublishedFile.GetUserFiles` service RPC. Bypasses HTTP 429 rate
     * limiting that occurs when scraping steamcommunity.com/profiles/<id>/myworkshop/.
     *
     * @param creatorSteamId The creator's Steam ID64 string (17-digit number).
     * @param page 1-indexed page number.
     * @param tags Required tags.
     * @param excludedTags Tags to exclude (Application and Web are ALWAYS added).
     * @param mobileCompatible If true, adds required_kv_tags[app_workshop_eula_version]=3.
     */
    suspend fun getUserFilesViaJavaSteam(
        creatorSteamId: String,
        page: Int = 1,
        tags: List<String> = emptyList(),
        excludedTags: List<String> = emptyList(),
        mobileCompatible: Boolean = true,
    ): Result<List<WorkshopItemTyped>> = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext Result.failure(
            SteamApiException.Network(
                "getUserFilesViaJavaSteam",
                IllegalStateException("SteamApiService constructed without appContext"),
            )
        )

        val steamId = creatorSteamId.toULongOrNull()
            ?: return@withContext Result.failure(
                SteamApiException.Network(
                    "getUserFilesViaJavaSteam",
                    IllegalArgumentException("Invalid Steam ID64: $creatorSteamId"),
                )
            )

        val allExcludedTags = excludedTags.toMutableList().apply {
            if ("Application" !in this) add("Application")
            if ("Web" !in this) add("Web")
        }.toList()

        val requiredKvTags: List<Pair<String, String>> =
            if (mobileCompatible) listOf(MOBILE_COMPAT_KVS_KEY to MOBILE_COMPAT_KVS_VALUE)
            else emptyList()

        val details = GetUserFilesDetails(
            steamId = steamId,
            appId = WE_APP_ID.toInt(),
            page = page,
            numPerPage = pageSize,
            requiredTags = tags,
            excludedTags = allExcludedTags,
            requiredKvTags = requiredKvTags,
            returnVoteData = true,
            returnTags = true,
            returnKvTags = true,
            returnPreviews = true,
            returnShortDescription = true,
        )

        WorkshopBrowseSession.getUserFiles(context, details)
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
            // Lazy-load the persistent cache on first access (v0.5.11-alpha).
            // Subsequent calls are no-ops.
            loadPersistentCacheIfNeeded()
            // Cache hit (in-memory, possibly populated from persistent storage)
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
                    // v0.7.4: close the response so the connection returns to
                    // OkHttp's pool (see getPublishedFileDetails for rationale).
                    response.close()
                    // Fall back to the raw ID — UI can still show something.
                    // v0.5.11-alpha: DON'T cache the raw ID on HTTP error
                    // (was previously pinned in the in-memory cache, which
                    // permanently broke retries — even after Steam's rate
                    // limit cleared, the cached raw ID would be returned
                    // forever). Now: return the raw ID for THIS call only,
                    // don't cache it. Future requests will retry.
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
                    persistCreatorName(steamId, resolved)  // v0.5.11-alpha: persistent
                }
                WepWawetLogger.steam("resolveCreatorName done",
                    "steam_id" to steamId,
                    "resolved" to resolved,
                    "found_in_html" to (name != null))
                Result.success(resolved)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // v0.7.4: re-throw — see getPublishedFileDetails for rationale.
                throw e
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
     * === v0.5.11-alpha (creator name fix) ===
     *
     * Batch-resolve display names for up to 100 Steam IDs in a SINGLE API
     * call via ISteamUser/GetPlayerSummaries/v2/. This replaces the previous
     * pattern of calling [resolveCreatorName] once per ID (which made N
     * parallel HTTP requests to steamcommunity.com/profiles/<id> — Steam
     * rate-limited these to HTTP 429 almost immediately when N > 4).
     *
     * User's log showed:
     *   [WEP-PROXY] WARN: resolveCreatorName HTTP error code=429
     *     steam_id=76561198077404589
     *   ... (repeated for every creator on every page)
     *   [WEP-WS] INFO: resolveCreatorsForItems done looked_up=4
     *     resolved=0 fallback=4 cache_size=0
     *
     * cache_size=0 means the in-memory cache was empty (fresh app launch)
     * AND no successful resolutions happened (all 429s). So every page
     * loaded showed "by <raw steam ID>" instead of "by Display Name".
     *
     * The GetPlayerSummaries API:
     *   - URL: https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v2/
     *   - GET params: key=<API_KEY>&steamids=<comma-separated-list>
     *   - Returns: { "response": { "players": [
     *       { "steamid": "7656...", "personaname": "Display Name", ... }
     *     ] } }
     *   - Rate limit: ~10 requests/second (much higher than profile HTML scraping)
     *   - Max IDs per request: 100
     *
     * When the API key is not set, falls back to per-ID HTML scraping via
     * [resolveCreatorName] (which is rate-limited but works anonymously).
     *
     * @param steamIds list of Steam ID64 strings (will be chunked to 100s)
     * @return Result.success(Map<steamId, displayName>) for the IDs that
     *   successfully resolved; failed IDs are simply omitted from the map.
     */
    suspend fun getPlayerSummaries(steamIds: List<String>): Result<Map<String, String>> =
        withContext(Dispatchers.IO) {
            if (steamIds.isEmpty()) return@withContext Result.success(emptyMap())
            loadPersistentCacheIfNeeded()

            val key = apiKey
            if (key.isNullOrBlank()) {
                WepWawetLogger.proxyWarn("getPlayerSummaries: no API key set — falling back to per-ID HTML scraping",
                    "id_count" to steamIds.size)
                // Fall back to per-ID HTML scraping (slow, rate-limited, but
                // works anonymously). Sequential (not parallel) to avoid
                // triggering 429s.
                val result = mutableMapOf<String, String>()
                for (id in steamIds) {
                    val name = resolveCreatorName(id).getOrNull()
                    if (name != null && name != id) {
                        result[id] = name
                    }
                }
                return@withContext Result.success(result)
            }

            val allResults = mutableMapOf<String, String>()
            for ((chunkIdx, chunk) in steamIds.chunked(100).withIndex()) {  // Steam max 100 IDs per request
                try {
                    val url = "$BASE_URL/ISteamUser/GetPlayerSummaries/v2/" +
                        "?key=${java.net.URLEncoder.encode(key, "UTF-8")}" +
                        "&steamids=${chunk.joinToString(",")}"
                    WepWawetLogger.steamDebug("getPlayerSummaries GET",
                        "url" to url.take(200), "id_count" to chunk.size)
                    val request = Request.Builder().url(url)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .build()
                    val response = httpClient.newCall(request).execute()
                    if (!response.isSuccessful) {
                        WepWawetLogger.proxyWarn("getPlayerSummaries HTTP error",
                            "code" to response.code, "chunk_size" to chunk.size)
                        response.close()
                        continue  // skip this chunk, try the next
                    }
                    val jsonStr = response.body?.string().orEmpty()
                    if (jsonStr.isBlank()) continue

                    val responseObj = gson.fromJson(jsonStr, JsonObject::class.java)
                    val responseInner = responseObj?.getAsJsonObject("response")
                    val playersArray = responseInner?.getAsJsonArray("players")
                    if (playersArray == null) continue

                    for (i in 0 until playersArray.size()) {
                        val player = playersArray[i].asJsonObject
                        val steamId = player.get("steamid")?.asString ?: continue
                        val personaName = player.get("personaname")?.asString
                        if (!steamId.isNullOrBlank() && !personaName.isNullOrBlank()) {
                            allResults[steamId] = personaName
                            creatorNameCache[steamId] = personaName
                            persistCreatorName(steamId, personaName)
                        }
                        // Side-effect: cache the avatar URL so the Favorites
                        // screen can render profile pictures without an extra
                        // API call. We do this even if the persona name was
                        // blank — the avatar is still useful on its own.
                        val avatarFull = player.get("avatarfull")?.asString
                        if (avatarFull != null) {
                            creatorAvatarCache[steamId] = avatarFull
                        }
                    }
                    WepWawetLogger.steam("getPlayerSummaries chunk done",
                        "chunk_size" to chunk.size, "resolved" to playersArray.size(),
                        "cache_size" to creatorNameCache.size)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // v0.7.4: re-throw. The old catch (e: Exception) ALSO swallowed
                    // the CancellationException thrown by the delay(100) below when
                    // the calling coroutine was cancelled — the loop then kept
                    // making HTTP requests + sleeping for a cancelled caller.
                    throw e
                } catch (e: Exception) {
                    WepWawetLogger.proxyWarn("getPlayerSummaries chunk failed",
                        "exception" to e.javaClass.name, "message" to (e.message ?: ""))
                }
                // Throttle between chunks to stay under Steam's rate limit
                // (~10 req/sec — we sleep 100ms between chunks to be safe).
                // v0.7.4: only BETWEEN chunks — the old trailing delay added
                // a pointless 100ms after the LAST request too.
                if (chunkIdx < (steamIds.chunked(100).size - 1)) {
                    kotlinx.coroutines.delay(100)
                }
            }
            Result.success(allResults)
        }

    /** Get a cached avatar URL for a Steam creator (null if not yet resolved). */
    fun getCreatorAvatarUrl(steamId: String): String? = creatorAvatarCache[steamId]

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
                append("&numperpage=$pageSize")
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            // v0.7.4: re-throw — see getPublishedFileDetails for rationale.
            throw e
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
                append("&numperpage=$pageSize")
            }
            fetchIdsFromUrl(url, page)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // v0.7.4: re-throw — see getPublishedFileDetails for rationale.
            throw e
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
                    // v0.7.4: close the response so the connection returns to
                    // OkHttp's pool (see getPublishedFileDetails for rationale).
                    response.close()
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                // v0.7.4: re-throw — see getPublishedFileDetails for rationale.
                throw e
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
        // v0.5.32 (audit L3): do a SINGLE pass over the HTML with a combined
        // regex so the result order matches Steam's actual browse order.
        //
        // Previously this used three separate `regex.findAll(html)` calls
        // (one per pattern), so all `sharedfiles/filedetails/?id=N` matches
        // were appended first (in HTML order), then all
        // `data-publishedfileid="N"` matches (in HTML order), then all
        // single-quote variants. If the HTML mixed both patterns (which
        // Steam's HTML does — each item appears as both a link URL and a
        // data attribute on the same row), the deduplication filter
        // `id !in ids` would keep the URL-based position and silently drop
        // the data-attribute position, but items that ONLY appeared via the
        // data attribute (rare, but happens for some creator-card layouts)
        // would be appended at the END of the list — out of HTML order.
        //
        // With a single combined regex using alternation, `findAll` walks
        // the HTML left-to-right and emits matches in encounter order,
        // regardless of which alternative matched.
        //
        // The third alternative uses a backreference (\2) to require that
        // the closing quote matches the opening quote — so
        // `data-publishedfileid="12345'` (open double, close single) does
        // NOT match, preserving the original strictness.
        val combined = Regex(
            "sharedfiles/filedetails/\\?id=(\\d+)" +
            "|data-publishedfileid=([\"'])(\\d+)\\2"
        )
        combined.findAll(html).forEach { match ->
            // groupValues[1] is the first capture group (URL form) —
            // empty if the second alternative matched.
            // groupValues[3] is the third capture group (data-attr form) —
            // empty if the first alternative matched.
            // (groupValues[2] is the quote char, which we discard.)
            val id = match.groupValues[1].ifEmpty { match.groupValues[3] }
            if (id.length >= 4 && id !in ids) ids.add(id)
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
     *   2  = AcceptedForGameRankedByAcceptanceDate
     *   3  = RankedByTrend (uses [trendDays], range [1,7] per Steam docs)
     *   9  = RankedByTotalUniqueSubscriptions
     *   10 = RankedByTotalVotesAsc (ASCENDING — lowest first, OPPOSITE of Most Up Votes)
     *   11 = RankedByVotesUp (DESCENDING — highest first)
     *   12 = RankedByTextSearch (search only)
     *
     * Source: https://partner.steamgames.com/doc/webapi/IPublishedFileService
     * DO NOT trust this file's old comment — it was wrong and caused v0.5.14-alpha's
     * C3 fix to be applied BACKWARDS, breaking Most Popular, Most Up Votes, Most
     * Subscriptions, and Most Relevant sorts.
     *
     * Requires a Steam Web API key (obtainable from steamcommunity.com/dev/apikey).
     * When no key is set, falls back to HTML scraping (only trend/toprated/mostrecent
     * work — Most Up Votes and Most Subscriptions are disabled in the UI).
     *
     * Default sort = MOST_POPULAR_YEAR (trend with days=365). The JavaSteam
     * service path (WorkshopBrowseSession.queryFiles) honors any days value —
     * no [1,7] limit. The HTTP API path clamps days > 7 to 7 as fallback.
     */
    enum class Sort(
        val queryType: Int,       // EPublishedFileQueryType
        val browseParam: String,  // HTML browse fallback
        val displayName: String,
        val trendDays: Int = 0,
    ) {
        TOP_RATED(0, "toprated", "Top Rated"),
        // For the JavaSteam service path (WorkshopBrowseSession.queryFiles), trendDays
        // can be ANY positive integer (no [1,7] limit) — Steam honors 365 for Year,
        // 30 for Month, etc. The JavaSteam path speaks the same Steam CM protocol
        // that steam_api64.dll (the native Steamworks SDK WE PC uses) speaks.
        //
        // For the HTTP API path (when JavaSteam session is unavailable), days > 7 are
        // silently clamped to 7 by Steam — but the WorkshopRepository will use the
        // HTML endpoint as a fallback for trend sorts, which DOES accept arbitrary
        // day values (verified: days=1, days=30, days=365 all return distinct results).
        MOST_POPULAR_YEAR(3, "trend", "Most Popular (Year)", trendDays = 365),
        MOST_POPULAR_MONTH(3, "trend", "Most Popular (Month)", trendDays = 30),
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
        const val DEFAULT_PAGE_SIZE = 30     // default page size (Settings slider default)

        /**
         * v0.7.4: RUNTIME-CONFIGURABLE page size — previously a hardcoded
         * `const val PAGE_SIZE = 30` that completely ignored the "Page size"
         * slider in Settings (the slider persisted its value but nothing ever
         * read it). Loaded from the "wepwawet_settings" prefs at app start via
         * [initPageSize] (called from WepWawetApplication.onCreate) and updated
         * live via [setPageSize] (called by SettingsScreen when the slider
         * moves). @Volatile so queries on IO threads always see the latest value.
         */
        @Volatile var pageSize: Int = DEFAULT_PAGE_SIZE
            private set

        /** v0.7.4: apply a new page size (clamped to the slider's 10..100 range). */
        fun setPageSize(size: Int) {
            pageSize = size.coerceIn(10, 100)
        }

        /** v0.7.4: load the persisted page size at app start. */
        fun initPageSize(context: android.content.Context) {
            val saved = context.getSharedPreferences(
                "wepwawet_settings", android.content.Context.MODE_PRIVATE)
                .getInt("page_size", DEFAULT_PAGE_SIZE)
            pageSize = saved.coerceIn(10, 100)
        }
        const val USER_AGENT =
            // v0.5.32 (audit L2): use a DESKTOP Chrome UA instead of a mobile
            // UA so Steam serves the standard desktop HTML layout for
            // steamcommunity.com/workshop/browse (which the
            // extractWorkshopIdsFromHtml regex parser expects). A mobile UA
            // may cause Steam to serve a different layout that breaks the
            // regex-based ID extraction. Verified working today with the
            // mobile UA, but the layout is fragile to Steam markup changes
            // — switching to the standard desktop UA removes that fragility.
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

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
                // v0.7.4: a NON-numeric string (e.g. "unknown") is now REMOVED so
                // plainGson binds the field's default (0) — previously it was left
                // in place and the reflection binding threw JsonSyntaxException,
                // causing the whole item to be silently dropped by the callers'
                // mapNotNull. Dropping the field keeps the item visible.
                listOf("subscriptions", "favorited", "views", "lifetime_subscriptions",
                    "lifetime_favorited", "file_size", "time_created", "time_updated",
                    "creator_app_id"
                ).forEach { fieldName ->
                    val el = obj.get(fieldName)
                    if (el != null && el.isJsonPrimitive && el.asJsonPrimitive.isString) {
                        // Convert string number to actual number
                        val strVal = el.asString
                        if (strVal.toLongOrNull()?.let { numVal ->
                                obj.remove(fieldName)
                                obj.addProperty(fieldName, numVal)
                                true
                            } != true) {
                            // Non-numeric string — strip the field entirely.
                            obj.remove(fieldName)
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
