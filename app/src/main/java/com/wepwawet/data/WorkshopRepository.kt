package com.wepwawet.data

import com.wepwawet.data.model.WorkshopItemTyped
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for workshop data in the UI.
 *
 * Responsibilities:
 *   - Browse:Paginated scroll loading (Steam workshop doesn't expose cursor; we use page numbers)
 *   - In-memory cache of the current browse session (so navigation between screens doesn't re-fetch)
 *   - Subscriptions state (which IDs are subscribed) — synced with the emulated Steam's `workshop/`
 *   - Lookup by ID (for the Wallpaper detail screen)
 */
class WorkshopRepository constructor(
    private val steamApi: SteamApiService,
) {

    // ====== Browse state (single session — replaced when user changes filter) ======

    private val _browseItems = MutableStateFlow<List<WorkshopItemTyped>>(emptyList())
    val browseItems: StateFlow<List<WorkshopItemTyped>> = _browseItems.asStateFlow()

    private val _browseState = MutableStateFlow<BrowseState>(BrowseState.Idle)
    val browseState: StateFlow<BrowseState> = _browseState.asStateFlow()

    private var currentFilter: Filter? = null
    private var currentPage: Int = 0
    private var hasMore: Boolean = true

    /**
     * Reset and start a new browse session with a different filter.
     */
    suspend fun startBrowse(filter: Filter) {
        currentFilter = filter
        currentPage = 0
        hasMore = true
        _browseItems.value = emptyList()
        loadNextPage()
    }

    suspend fun loadNextPage() {
        val filter = currentFilter ?: return
        if (!hasMore) return
        _browseState.value = BrowseState.Loading(currentPage)
        try {
            val page = currentPage + 1
            val ids = steamApi.browseWorkshop(
                query = filter.query,
                tag = filter.tag,
                sort = filter.sort,
                page = page,
            ).getOrThrow()

            if (ids.isEmpty()) {
                hasMore = false
                _browseState.value = BrowseState.Done
                return
            }

            val typed = steamApi.getBatchDetails(ids).getOrThrow()
            _browseItems.value = (_browseItems.value + typed)
                .distinctBy { it.id }
            currentPage = page
            _browseState.value = BrowseState.Loaded(page, hasMore)

            WepWawetLogger.workshopDebug("loadNextPage", "page" to page, "items_so_far" to _browseItems.value.size)
        } catch (e: Exception) {
            _browseState.value = BrowseState.Error(e.message ?: "Unknown error", currentPage)
            WepWawetLogger.workshopWarn("loadNextPage failed", "page" to currentPage, "exception" to e.javaClass.name)
        }
    }

    // ====== Subscriptions ======

    private val _subscribedIds = MutableStateFlow<Set<String>>(emptySet())
    val subscribedIds: StateFlow<Set<String>> = _subscribedIds.asStateFlow()

    /**
     * Add a subscription locally — the actual Steam-side subscription is initiated by
     * emulated Steam client via `steam://subscribe/<id>`.
     */
    suspend fun subscribe(itemId: String) {
        _subscribedIds.value = _subscribedIds.value + itemId
        // The actual Steam-side call is in WineProcessService/WeControlClient
    }

    suspend fun unsubscribe(itemId: String) {
        _subscribedIds.value = _subscribedIds.value - itemId
    }

    fun isSubscribed(itemId: String): Boolean = itemId in _subscribedIds.value

    // ====== Lookup ======

    suspend fun getById(id: String): WorkshopItemTyped? {
        // Try local cache first
        _browseItems.value.firstOrNull { it.id == id }?.let { return it }
        // Otherwise fetch fresh
        return runCatching {
            steamApi.getBatchDetails(listOf(id)).getOrThrow().firstOrNull()
        }.getOrNull()
    }

    // ====== Types ======

    data class Filter(
        val query: String? = null,
        val tag: String? = null,
        val sort: SteamApiService.Sort = SteamApiService.Sort.MOST_SUBSCRIBED,
    )

    sealed class BrowseState {
        data object Idle : BrowseState()
        data class Loading(val page: Int) : BrowseState()
        data class Loaded(val page: Int, val hasMore: Boolean) : BrowseState()
        data class Error(val message: String, val page: Int) : BrowseState()
        data object Done : BrowseState()
    }
}
