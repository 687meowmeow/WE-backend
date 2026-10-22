package com.wepwawet.data

import com.wepwawet.data.model.WorkshopItemTyped
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single source of truth for workshop data in the UI.
 *
 * Responsibilities (per Task 4):
 *   - Paginated browse with sort options (mostrecent / mostsubscribed / mostfavorited / trend)
 *   - Search by free-text query
 *   - Filter by tags (Video, Scene, Application, Image, Web)
 *   - Get details by ID (cache-first)
 *   - In-memory cache of the current browse session AND a per-id detail cache
 *   - Logs all operations via [WepWawetLogger] (WORKSHOP domain)
 *
 * Steam doesn't expose cursor-based pagination; we use page numbers (1, 2, …).
 * When the user changes the filter (sort / tag / query), we reset the session and
 * start from page 1.
 */
class WorkshopRepository constructor(
    private val steamApi: SteamApiService,
) {

    // ====== Browse state (single session — replaced when user changes filter) ======

    private val _browseItems = MutableStateFlow<List<WorkshopItemTyped>>(emptyList())
    val browseItems: StateFlow<List<WorkshopItemTyped>> = _browseItems.asStateFlow()

    private val _browseState = MutableStateFlow<BrowseState>(BrowseState.Idle)
    val browseState: StateFlow<BrowseState> = _browseState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private var currentFilter: Filter? = null
    private var currentPage: Int = 0
    @Volatile private var hasMore: Boolean = true

    /** Mutex guards concurrent browse/search operations against each other. */
    private val browseLock = Mutex()

    // ====== Search state (separate from browse so user can switch back) ======

    private val _searchItems = MutableStateFlow<List<WorkshopItemTyped>>(emptyList())
    val searchItems: StateFlow<List<WorkshopItemTyped>> = _searchItems.asStateFlow()

    private val _searchState = MutableStateFlow<BrowseState>(BrowseState.Idle)
    val searchState: StateFlow<BrowseState> = _searchState.asStateFlow()

    private var currentSearchQuery: String? = null
    private var currentSearchPage: Int = 0
    @Volatile private var searchHasMore: Boolean = true

    // ====== In-memory detail cache (id → typed item) ======

    private val detailCache = mutableMapOf<String, WorkshopItemTyped>()

    // ====== Subscriptions (local mirror of what the user has subscribed to) ======

    private val _subscribedIds = MutableStateFlow<Set<String>>(emptySet())
    val subscribedIds: StateFlow<Set<String>> = _subscribedIds.asStateFlow()

    // ====== Browse ======

    /**
     * Reset and start a new browse session with a different filter.
     * Called when the user changes sort, tag, or query.
     */
    suspend fun startBrowse(filter: Filter) {
        browseLock.withLock {
            currentFilter = filter
            currentPage = 0
            hasMore = true
            _browseItems.value = emptyList()
            _browseState.value = BrowseState.Idle
            loadNextPageLocked()
        }
    }

    /**
     * Load the next page of the current browse session (or search session if active).
     * Safe to call from UI — does nothing if a load is already in progress or there
     * is no more data.
     */
    suspend fun loadNextPage() {
        // If we're in search mode, delegate to search loadNextPage
        if (currentSearchQuery != null) {
            loadNextSearchPage()
            return
        }
        browseLock.withLock {
            loadNextPageLocked()
        }
    }

    private suspend fun loadNextPageLocked() {
        val filter = currentFilter ?: run {
            WepWawetLogger.workshopWarn("loadNextPage: no filter set")
            return
        }
        if (!hasMore) {
            WepWawetLogger.workshopDebug("loadNextPage: no more pages")
            return
        }
        val page = currentPage + 1
        _browseState.value = BrowseState.Loading(page)
        WepWawetLogger.workshop("loadNextPage start",
            "page" to page,
            "sort" to filter.sort.browseParam,
            "tag" to (filter.tag ?: "none"),
            "query" to (filter.query ?: "none"))

        try {
            val tagList = filter.tag?.let { listOf(it) } ?: emptyList()
            val result = if (filter.query != null) {
                // Use search path when a query is set
                steamApi.searchWorkshop(filter.query, page)
            } else {
                steamApi.browseWorkshop(
                    sort = filter.sort,
                    page = page,
                    tags = tagList,
                )
            }
            val items = result.getOrElse { e ->
                _browseState.value = BrowseState.Error(
                    e.message ?: "Unknown error", page
                )
                WepWawetLogger.workshopWarn("loadNextPage failed",
                    "page" to page,
                    "exception" to e.javaClass.name,
                    "message" to (e.message ?: ""))
                return
            }

            if (items.isEmpty()) {
                hasMore = false
                _browseState.value = BrowseState.Done
                WepWawetLogger.workshop("loadNextPage: end of results",
                    "page" to page)
                return
            }

            // Cache each item by id for fast detail-screen lookup
            items.forEach { detailCache[it.id] = it }

            _browseItems.value = (_browseItems.value + items).distinctBy { it.id }
            currentPage = page
            _browseState.value = BrowseState.Loaded(page, hasMore)

            WepWawetLogger.workshop("loadNextPage done",
                "page" to page,
                "new_items" to items.size,
                "total_items" to _browseItems.value.size)
        } catch (e: Exception) {
            _browseState.value = BrowseState.Error(e.message ?: "Unknown error", currentPage)
            WepWawetLogger.workshopWarn("loadNextPage exception",
                "page" to currentPage,
                "exception" to e.javaClass.name)
        }
    }

    /**
     * Pull-to-refresh: reload page 1 of the current session, replace all items.
     */
    suspend fun refresh() {
        _isRefreshing.value = true
        try {
            val filter = currentFilter
            val query = currentSearchQuery
            if (query != null) {
                // Refresh search
                currentSearchPage = 0
                searchHasMore = true
                _searchItems.value = emptyList()
                loadNextSearchPage()
            } else if (filter != null) {
                // Refresh browse
                currentPage = 0
                hasMore = true
                _browseItems.value = emptyList()
                loadNextPageLocked()
            }
        } finally {
            _isRefreshing.value = false
        }
    }

    // ====== Search ======

    /**
     * Start a new text search. Replaces the current search session.
     * Search uses [SteamApiService.searchWorkshop] which scrapes the browse HTML
     * with `searchtext=<query>`.
     */
    suspend fun startSearch(query: String) {
        if (query.isBlank()) {
            clearSearch()
            return
        }
        browseLock.withLock {
            currentSearchQuery = query
            currentSearchPage = 0
            searchHasMore = true
            _searchItems.value = emptyList()
            _searchState.value = BrowseState.Idle
            loadNextSearchPageLocked()
        }
    }

    /**
     * Clear the active search session — UI falls back to showing browse items.
     */
    fun clearSearch() {
        currentSearchQuery = null
        currentSearchPage = 0
        searchHasMore = false
        _searchItems.value = emptyList()
        _searchState.value = BrowseState.Idle
        WepWawetLogger.workshop("clearSearch")
    }

    private suspend fun loadNextSearchPage() {
        browseLock.withLock { loadNextSearchPageLocked() }
    }

    private suspend fun loadNextSearchPageLocked() {
        val query = currentSearchQuery ?: return
        if (!searchHasMore) return
        val page = currentSearchPage + 1
        _searchState.value = BrowseState.Loading(page)
        WepWawetLogger.workshop("loadNextSearchPage start",
            "query" to query, "page" to page)

        try {
            val items = steamApi.searchWorkshop(query, page).getOrElse { e ->
                _searchState.value = BrowseState.Error(
                    e.message ?: "Unknown error", page
                )
                WepWawetLogger.workshopWarn("loadNextSearchPage failed",
                    "page" to page, "exception" to e.javaClass.name)
                return
            }
            if (items.isEmpty()) {
                searchHasMore = false
                _searchState.value = BrowseState.Done
                return
            }
            items.forEach { detailCache[it.id] = it }
            _searchItems.value = (_searchItems.value + items).distinctBy { it.id }
            currentSearchPage = page
            _searchState.value = BrowseState.Loaded(page, searchHasMore)
            WepWawetLogger.workshop("loadNextSearchPage done",
                "page" to page,
                "new_items" to items.size,
                "total_items" to _searchItems.value.size)
        } catch (e: Exception) {
            _searchState.value = BrowseState.Error(e.message ?: "Unknown error", currentSearchPage)
            WepWawetLogger.workshopWarn("loadNextSearchPage exception",
                "page" to currentSearchPage, "exception" to e.javaClass.name)
        }
    }

    // ====== Subscriptions ======

    /**
     * Add a subscription locally — the actual Steam-side subscription is initiated by
     * emulated Steam client via `steam://subscribe/<id>`.
     */
    suspend fun subscribe(itemId: String) {
        _subscribedIds.value = _subscribedIds.value + itemId
        WepWawetLogger.workshop("subscribe", "id" to itemId)
    }

    suspend fun unsubscribe(itemId: String) {
        _subscribedIds.value = _subscribedIds.value - itemId
        WepWawetLogger.workshop("unsubscribe", "id" to itemId)
    }

    fun isSubscribed(itemId: String): Boolean = itemId in _subscribedIds.value

    // ====== Lookup ======

    /**
     * Get a single workshop item by ID.
     * Cache-first: try the in-memory detail cache, then the current browse/search
     * session, then fall back to a fresh API call.
     */
    suspend fun getById(id: String): WorkshopItemTyped? {
        detailCache[id]?.let {
            WepWawetLogger.workshopDebug("getById cache hit", "id" to id)
            return it
        }
        _browseItems.value.firstOrNull { it.id == id }?.let {
            detailCache[id] = it
            return it
        }
        _searchItems.value.firstOrNull { it.id == id }?.let {
            detailCache[id] = it
            return it
        }
        // Fresh fetch
        WepWawetLogger.workshop("getById fresh fetch", "id" to id)
        return runCatching {
            steamApi.getPublishedFileDetails(listOf(id)).getOrThrow().firstOrNull()
        }.onSuccess { typed ->
            if (typed != null) detailCache[id] = typed
        }.onFailure { e ->
            WepWawetLogger.workshopWarn("getById failed",
                "id" to id, "exception" to e.javaClass.name)
        }.getOrNull()
    }

    // ====== Types ======

    /**
     * Active filter for the browse session.
     *
     * @param query optional free-text search query — if set, [startBrowse] will route
     *   to [SteamApiService.searchWorkshop] instead of the plain browse.
     * @param tag optional type filter (e.g. "Video", "Scene", "Application", "Image", "Web")
     * @param sort browse sort mode
     */
    data class Filter(
        val query: String? = null,
        val tag: String? = null,
        val sort: SteamApiService.Sort = SteamApiService.Sort.MOST_SUBSCRIBED,
    )

    /**
     * Browse/search state machine.
     *
     * - [Idle]: no session active
     * - [Loading]: page N loading
     * - [Loaded]: page N loaded, hasMore flag indicates if more pages exist
     * - [Error]: page N errored (errorMessage)
     * - [Done]: no more results (end of list)
     */
    sealed class BrowseState {
        data object Idle : BrowseState()
        data class Loading(val page: Int) : BrowseState()
        data class Loaded(val page: Int, val hasMore: Boolean) : BrowseState()
        data class Error(val message: String, val page: Int) : BrowseState()
        data object Done : BrowseState()
    }
}
