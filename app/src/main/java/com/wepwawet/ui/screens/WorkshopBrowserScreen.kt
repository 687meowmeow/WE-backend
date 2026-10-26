package com.wepwawet.ui.screens

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wepwawet.data.SteamApiService
import com.wepwawet.data.WorkshopRepository
import com.wepwawet.data.model.WorkshopItemTyped
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.ui.components.TagPickerSheet
import com.wepwawet.ui.components.TileSize
import com.wepwawet.ui.components.WallpaperCard
import com.wepwawet.ui.components.defaultExcludedTags
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Workshop browser screen — the default landing screen for WepWawet.
 *
 * v0.5.3 OVERHAUL — the filter UI has been further tightened:
 *
 *   - The "Mobile Compatible" toggle and the "Filter ▼" button both live
 *     in the [TagPickerSheet] / FilterBar respectively — the TopAppBar no
 *     longer hosts the Filter button.
 *   - The Filter button is now the LEFTMOST control in the [FilterBar]
 *     (the same horizontal row as the Sort and Tile size selectors), so
 *     the layout is:
 *
 *         [Filter ▼] [Sort: Most Popular (Year) ▼] [Tile: Medium ▼]
 *
 *     All three controls share a single horizontally-scrollable row
 *     right below the search bar.
 *   - The TopAppBar has been slimmed down — its windowInsets are removed
 *     and the search bar / filter row paddings are reduced, so there is
 *     much less empty space between the status bar and the wallpaper
 *     grid.
 *   - The trend-days selector has been removed entirely. Trend days are
 *     now baked into the chosen [SteamApiService.Sort] (Year=365,
 *     Month=31, Week=7, Today=1) so the user picks the period directly
 *     from the Sort dropdown.
 *   - The Sort dropdown now exposes ALL eight user-facing sort options
 *     (Top Rated, Most Popular Year/Month/Week/Today, Most Recent,
 *     Most Up Votes, Most Subscriptions). MOST_RELEVANT is search-only
 *     and remains hidden. The previous "client-side" badge on the
 *     Most Up Votes / Most Subscriptions items has been removed (the
 *     [SteamApiService.Sort] enum no longer has `isClientSide`).
 *   - The default sort is [SteamApiService.Sort.MOST_POPULAR_YEAR].
 *   - The default excluded-tags set is [com.wepwawet.ui.components.defaultExcludedTags]
 *     — only the four non-mobile resolution sections (Standard,
 *     Ultrawide, Dual, Triple) are OFF by default. Every other section
 *     (Mobile Resolutions, Age Rating, Genre, Type, Miscellaneous) is ON.
 *   - The user's `sort`, `tileSize`, `excludedTags` and `mobileCompatible`
 *     selections are persisted to the `wepwawet_filters` SharedPreferences
 *     file so they survive app restarts. Values are loaded once on screen
 *     entry via [LaunchedEffect] and saved immediately whenever they change.
 *
 * Top bar (compact, single row): Back (creator mode only) | Bluetooth |
 * Refresh | Downloads | Settings | Logs.
 *
 * Filter row (single horizontal scroll, below the search bar):
 * Filter ▼ | Sort ▼ | Tile ▼.
 *
 * State management:
 *   - Any change to sort / mobileCompatible / excludedTags / creatorSteamId
 *     rebuilds the [WorkshopRepository.Filter] and calls
 *     [WorkshopRepository.startBrowse].
 *   - Typing into the search bar (debounced) calls
 *     [WorkshopRepository.startSearch] if non-blank, else
 *     [WorkshopRepository.clearSearch]. Search is disabled (and the field
 *     hidden) in creator browse mode.
 *
 * @param onItemClick invoked with a workshop item id when a card is tapped
 * @param onOpenDownloads invoked when the user taps the Downloads action icon
 * @param onOpenSettings invoked when the user taps the Settings action icon
 * @param onOpenLogs invoked when the user taps the Logs action icon
 * @param onOpenPairing invoked when the user taps the Bluetooth (pairing) icon
 * @param onCreatorClick invoked with a creator Steam ID64 when the user taps
 *   the "by <creator>" line on a wallpaper tile. Typically wires up to
 *   `nav.navigate("creator/$steamId")` in the NavHost. Defaults to a no-op.
 * @param onBack optional back callback — when non-null, a back arrow is shown
 *   in the top bar. Used by the creator-browse route to return to global browse.
 * @param creatorSteamId optional Steam ID64 to scope the browse to a single
 *   creator's workshop page. When non-null, the search bar is hidden, the
 *   sort control is disabled (Steam forces `toprated` for creator listings),
 *   and a "Showing items by <creator>" header is shown.
 * @param creatorDisplayName optional resolved display name for the creator —
 *   shown in the header when [creatorSteamId] is non-null.
 * @param repo shared workshop repository (created in [com.wepwawet.ui.WepWawetNavHost])
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun WorkshopBrowserScreen(
    onItemClick: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenPairing: () -> Unit,
    repo: WorkshopRepository,
    onCreatorClick: (String) -> Unit = {},
    onBack: (() -> Unit)? = null,
    creatorSteamId: String? = null,
    creatorDisplayName: String? = null,
) {
    // === Repository state ===
    val items by repo.browseItems.collectAsState()
    val searchItems by repo.searchItems.collectAsState()
    val browseState by repo.browseState.collectAsState()
    val searchState by repo.searchState.collectAsState()
    val isRefreshing by repo.isRefreshing.collectAsState()
    val creatorNames by repo.creatorNames.collectAsState()
    val scope = rememberCoroutineScope()

    val isCreatorMode = creatorSteamId != null

    // === SharedPreferences for persisting filter state across app restarts ===
    // We store `sort`, `tileSize`, `excludedTags` (as a comma-separated string)
    // and `mobileCompatible` here. Values are loaded once on screen entry via
    // LaunchedEffect(Unit) below, and saved immediately whenever any value
    // changes via per-key LaunchedEffects.
    val context = LocalContext.current
    val filterPrefs = remember {
        context.getSharedPreferences("wepwawet_filters", Context.MODE_PRIVATE)
    }
    // Guard flag — `false` until the LaunchedEffect(Unit) finishes loading the
    // saved filter values. Prevents the per-key save effects from overwriting
    // the saved values with the defaults before the load has had a chance to
    // apply them.
    var filterLoaded by remember { mutableStateOf(false) }

    // === User-controlled filter state (initialized to defaults; overwritten
    //     by LaunchedEffect(Unit) if a saved value exists) ===
    // v0.4.7/v0.5.9-alpha: query is now hoisted into WorkshopRepository
    // via `searchQueryText` StateFlow. The local `remember { mutableStateOf("") }`
    // was destroyed on navigation to detail/{itemId} and back, causing:
    //   1. query resets to ""
    //   2. snapshotFlow { query } re-emits the empty value
    //   3. debounce fires clearSearch() → search results wiped
    // Now: query survives navigation because WorkshopRepository is a singleton
    // held at the NavHost level. We use a local mutableState as a proxy for
    // the text field (Compose requires State<String> for OutlinedTextField
    // value), but we initialize it from the repo and write back to the repo
    // on every change.
    val repoQuery by repo.searchQueryText.collectAsState()
    var query by remember { mutableStateOf(repoQuery) }
    // Sync local state when the repo's query changes (e.g., after navigation
    // back, or after clearSearch was called from elsewhere — sort/tag change
    // LaunchedEffect below calls repo.clearSearch() which clears the repo
    // value, so we need to mirror that into the local query).
    LaunchedEffect(repoQuery) {
        if (query != repoQuery) {
            query = repoQuery
        }
    }
    fun updateQuery(newValue: String) {
        query = newValue
        repo.updateSearchQueryText(newValue)
    }
    var sort by remember { mutableStateOf(SteamApiService.Sort.MOST_POPULAR_YEAR) }
    var mobileCompatible by remember { mutableStateOf(true) }
    var tileSize by remember { mutableStateOf(TileSize.MEDIUM) }
    // Default excluded tags — only the four non-mobile resolution sections
    // (Standard / Ultrawide / Dual / Triple) are OFF by default; everything
    // else (Mobile Resolutions, Age Rating, Genre, Type, Miscellaneous) is ON.
    // This initial value is computed once per composition via remember { ... }.
    var excludedTags by remember {
        mutableStateOf(defaultExcludedTags())
    }
    var showTagPicker by remember { mutableStateOf(false) }

    // === Dropdown menu open/close flags ===
    var sortMenuOpen by remember { mutableStateOf(false) }
    var tileMenuOpen by remember { mutableStateOf(false) }

    // === Grid state for infinite scroll ===
    val gridState = rememberLazyGridState()

    // === Load saved filter values on screen entry ===
    // Uses LaunchedEffect(Unit) so it runs a single time per composition.
    LaunchedEffect(Unit) {
        val savedSortName = filterPrefs.getString("sort", null)
        savedSortName?.let { name ->
            runCatching { SteamApiService.Sort.valueOf(name) }.getOrNull()?.let { loaded ->
                if (loaded != sort) {
                    sort = loaded
                    WepWawetLogger.workshop(
                        "filter_state_loaded",
                        "key" to "sort",
                        "value" to loaded.name,
                    )
                }
            }
        }

        val savedTileName = filterPrefs.getString("tileSize", null)
        savedTileName?.let { name ->
            runCatching { TileSize.valueOf(name) }.getOrNull()?.let { loaded ->
                if (loaded != tileSize) {
                    tileSize = loaded
                    WepWawetLogger.workshop(
                        "filter_state_loaded",
                        "key" to "tileSize",
                        "value" to loaded.name,
                    )
                }
            }
        }

        val savedMobile = filterPrefs.getBoolean("mobileCompatible", true)
        if (savedMobile != mobileCompatible) {
            mobileCompatible = savedMobile
            WepWawetLogger.workshop(
                "filter_state_loaded",
                "key" to "mobileCompatible",
                "value" to savedMobile,
            )
        }

        val savedExcludedStr = filterPrefs.getString("excludedTags", null)
        if (!savedExcludedStr.isNullOrBlank()) {
            val savedExcluded = savedExcludedStr
                .split(",")
                .filter { it.isNotBlank() }
            if (savedExcluded.toSet() != excludedTags.toSet()) {
                excludedTags = savedExcluded
                WepWawetLogger.workshop(
                    "filter_state_loaded",
                    "key" to "excludedTags",
                    "count" to savedExcluded.size,
                )
            }
        }

        // Mark load complete so the per-key save effects below start persisting
        // changes again. Setting this last ensures we don't save the just-loaded
        // values back to prefs (which would be a harmless no-op, but generates
        // noisy log entries).
        filterLoaded = true
    }

    // === Persist filter state immediately when any value changes ===
    // Each effect is keyed on its state plus filterLoaded, and is gated by
    // filterLoaded so it doesn't fire until after the LaunchedEffect(Unit)
    // above has finished restoring saved values.
    LaunchedEffect(sort, filterLoaded) {
        if (!filterLoaded) return@LaunchedEffect
        filterPrefs.edit().putString("sort", sort.name).apply()
        WepWawetLogger.workshop(
            "filter_state_saved",
            "key" to "sort",
            "value" to sort.name,
        )
    }
    LaunchedEffect(tileSize, filterLoaded) {
        if (!filterLoaded) return@LaunchedEffect
        filterPrefs.edit().putString("tileSize", tileSize.name).apply()
        WepWawetLogger.workshop(
            "filter_state_saved",
            "key" to "tileSize",
            "value" to tileSize.name,
        )
    }
    LaunchedEffect(mobileCompatible, filterLoaded) {
        if (!filterLoaded) return@LaunchedEffect
        filterPrefs.edit().putBoolean("mobileCompatible", mobileCompatible).apply()
        WepWawetLogger.workshop(
            "filter_state_saved",
            "key" to "mobileCompatible",
            "value" to mobileCompatible,
        )
    }
    LaunchedEffect(excludedTags, filterLoaded) {
        if (!filterLoaded) return@LaunchedEffect
        filterPrefs.edit()
            .putString("excludedTags", excludedTags.joinToString(","))
            .apply()
        WepWawetLogger.workshop(
            "filter_state_saved",
            "key" to "excludedTags",
            "count" to excludedTags.size,
        )
    }

    // === Build the current Filter snapshot from user state ===
    // NOTE: `trendDays` was removed from Filter in v0.5.2 — it's now part
    // of the chosen Sort enum (Year=365, Month=31, Week=7, Today=1).
    fun currentFilter(): WorkshopRepository.Filter = WorkshopRepository.Filter(
        query = null,
        tags = emptyList(),
        excludedTags = excludedTags,
        mobileCompatible = mobileCompatible,
        sort = if (isCreatorMode) SteamApiService.Sort.TOP_RATED else sort,
        creatorSteamId = creatorSteamId,
    )

    // === When ANY filter changes (or creatorSteamId), start a new browse session ===
    LaunchedEffect(sort, mobileCompatible, excludedTags, creatorSteamId, filterLoaded) {
        // Don't restart the browse session until the saved filter state has
        // been loaded — otherwise the very first composition would start a
        // browse with the defaults, then immediately restart it once the
        // LaunchedEffect(Unit) applies the saved values.
        if (!filterLoaded) return@LaunchedEffect
        try {
            WepWawetLogger.workshop(
                "filter_changed restart_browse",
                "sort" to sort.name,
                "trend_days" to sort.trendDays,
                "mobile_compatible" to mobileCompatible,
                "excluded_tags_count" to excludedTags.size,
                "creator_steam_id" to (creatorSteamId ?: "none"),
            )
            // Clear search so we fall back to browse mode display
            if (query.isNotBlank()) {
                updateQuery("")
                repo.clearSearch()
            }
            repo.startBrowse(currentFilter())
        } catch (e: Exception) {
            WepWawetLogger.workshopWarn(
                "filter_changed restart_browse failed",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""),
            )
        }
    }

    // === Debounced search: when the user types, after 500 ms of quiet, fire startSearch ===
    // (Disabled in creator mode — no search box shown there)
    // v0.4.7/v0.5.9-alpha: Skip the initial empty query emission if search
    // results already exist (prevents the snapshotFlow from clearing a
    // restored search on navigation back). We track this with a
    // `firstEmission` flag.
    if (!isCreatorMode) {
        LaunchedEffect(Unit) {
            var firstEmission = true
            snapshotFlow { query }
                .debounce(500)
                .distinctUntilChanged()
                .collect { q ->
                    if (firstEmission) {
                        firstEmission = false
                        // On first emission (screen just composed or recomposed):
                        //   - If q is blank AND search items exist → DON'T clear
                        //     (the user just navigated back to a previous search)
                        //   - If q is blank AND no search items → no-op
                        //   - If q has text → it's a restored query, search is already
                        //     loaded, no need to re-trigger
                        if (q.isBlank() && repo.searchItems.value.isNotEmpty()) {
                            WepWawetLogger.workshop("first emission: preserving restored search",
                                "search_items_count" to repo.searchItems.value.size)
                            return@collect
                        }
                        if (q.isNotBlank()) {
                            WepWawetLogger.workshop("first emission: restored query, no re-trigger",
                                "query" to q.take(80))
                            return@collect
                        }
                        // First emission with empty query and no search items — fall through
                    }
                    if (q.isBlank()) {
                        if (repo.searchItems.value.isNotEmpty()) {
                            WepWawetLogger.workshop("query cleared — clearSearch")
                            repo.clearSearch()
                        }
                    } else {
                        WepWawetLogger.workshop(
                            "debounced query — startSearch",
                            "query" to q.take(80),
                        )
                        repo.startSearch(q)
                    }
                }
        }
    }

    // === Active state for display (browse vs search) ===
    val inSearchMode = !isCreatorMode && (searchItems.isNotEmpty() ||
        (query.isNotBlank() && searchState !is WorkshopRepository.BrowseState.Idle))
    val displayState = if (inSearchMode) searchState else browseState
    val displayItems: List<WorkshopItemTyped> = if (inSearchMode) searchItems else items

    // === Infinite scroll: when last visible item is within 3 of the end, load more ===
    val shouldLoadMore by remember(
        gridState, displayState, displayItems.size,
    ) {
        derivedStateOf {
            val total = displayItems.size
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && lastVisible >= total - 3
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            WepWawetLogger.workshop(
                "infinite_scroll triggered loadNextPage",
                "last_visible" to (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1),
                "total" to displayItems.size,
            )
            repo.loadNextPage()
        }
    }

    // === Pull-to-refresh handler ===
    val onRefresh: () -> Unit = {
        WepWawetLogger.workshop("pull_to_refresh triggered")
        scope.launch { repo.refresh() }
    }

    // The creator display name to show in the header — prefer the resolved
    // name from the StateFlow, fall back to the passed-in display name, then
    // to the raw steam ID.
    val resolvedCreatorName: String? = if (isCreatorMode) {
        creatorNames[creatorSteamId] ?: creatorDisplayName ?: creatorSteamId
    } else null

    Scaffold(
        topBar = {
            // Compact TopAppBar — no title text, only icon actions.
            // The Filter button was moved OUT of the top bar and INTO the
            // FilterBar (the row below the search bar that also hosts the
            // Sort and Tile size selectors). What's left on the top bar:
            //   - Left (navigationIcon): Back arrow (creator mode only),
            //     Bluetooth (pairing) button.
            //   - Right (actions): Refresh, Downloads, Settings, Logs.
            //
            // The TopAppBar's windowInsets are set to WindowInsets(0) so
            // the bar's icons sit right at the top edge of the bar (no
            // extra status bar inset eating into the 64dp height). The
            // default status bar padding is preserved by applying
            // Modifier.statusBarsPadding() to the TopAppBar itself, so the
            // bar is still pushed below the system status bar.
            TopAppBar(
                modifier = Modifier
                    .statusBarsPadding()
                    .height(56.dp),
                title = { },
                navigationIcon = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // Back arrow (creator mode only) — leftmost control
                        // on the top bar when in creator mode.
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                )
                            }
                        }
                        // Pairing (Bluetooth) button.
                        IconButton(onClick = {
                            WepWawetLogger.workshop("topbar pairing tapped")
                            onOpenPairing()
                        }) {
                            Icon(
                                Icons.Filled.Bluetooth,
                                contentDescription = "Pairing",
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        WepWawetLogger.workshop("topbar refresh tapped")
                        onRefresh()
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = {
                        WepWawetLogger.workshop("topbar downloads tapped")
                        onOpenDownloads()
                    }) {
                        Icon(Icons.Filled.Download, contentDescription = "Downloads")
                    }
                    IconButton(onClick = {
                        WepWawetLogger.workshop("topbar settings tapped")
                        onOpenSettings()
                    }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = {
                        WepWawetLogger.workshop("topbar logs tapped")
                        onOpenLogs()
                    }) {
                        Icon(Icons.Filled.Terminal, contentDescription = "Logs")
                    }
                },
                // Strip the TopAppBar's default status bar inset — we apply
                // statusBarsPadding() ourselves above. This keeps the bar at
                // its 56dp height (down from the default 64dp + 24dp inset).
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        // No additional content window insets — the top app bar already
        // accounts for the status bar via statusBarsPadding(), and the
        // grid's bottom inset is handled by the LazyVerticalGrid's
        // contentPadding.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            // === Creator header (only shown in creator mode) ===
            if (isCreatorMode) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = "Showing items by " +
                                (resolvedCreatorName ?: creatorSteamId ?: "unknown"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // === Search bar (hidden in creator mode) ===
            if (!isCreatorMode) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { updateQuery(it) },
                    placeholder = { Text("Search wallpapers…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            WepWawetLogger.workshop(
                                "ime search action — startSearch",
                                "query" to query.take(80),
                            )
                            if (query.isBlank()) repo.clearSearch()
                            else scope.launch { repo.startSearch(query) }
                        },
                    ),
                    singleLine = true,
                )
            }

            // === Single-row filter bar (horizontal scroll on narrow screens) ===
            // The Filter button is the LEFTMOST control here (moved out of
            // the TopAppBar in v0.5.3), followed by the Sort and Tile size
            // dropdowns. All three share the same horizontal row.
            val filterLabel = when {
                excludedTags.isEmpty() && mobileCompatible -> "Filter"
                mobileCompatible -> "Filter (${excludedTags.size})"
                else -> "Filter (${excludedTags.size}+mob)"
            }
            FilterBar(
                sort = sort,
                onSortChange = { sort = it },
                sortMenuOpen = sortMenuOpen,
                onSortMenuOpenChange = { sortMenuOpen = it },
                tileSize = tileSize,
                onTileSizeChange = { tileSize = it },
                tileMenuOpen = tileMenuOpen,
                onTileMenuOpenChange = { tileMenuOpen = it },
                sortEnabled = !isCreatorMode,
                filterLabel = filterLabel,
                onFilterClick = {
                    WepWawetLogger.workshop(
                        "filter_picker open",
                        "currently_excluded" to excludedTags.size,
                        "mobile_compatible" to mobileCompatible,
                    )
                    showTagPicker = true
                },
            )

            // === Grid (with pull-to-refresh) ===
            Box(modifier = Modifier.fillMaxSize()) {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    when (displayState) {
                        WorkshopRepository.BrowseState.Idle -> EmptyState(
                            text = if (isCreatorMode)
                                "Loading creator's workshop…"
                            else "Pick a filter to start browsing.",
                        )

                        is WorkshopRepository.BrowseState.Loading -> {
                            if (displayItems.isEmpty()) {
                                LoadingState()
                            } else {
                                WallpaperGrid(
                                    gridState = gridState,
                                    items = displayItems,
                                    tileSize = tileSize,
                                    onItemClick = onItemClick,
                                    onCreatorClick = onCreatorClick,
                                    creatorNames = creatorNames,
                                    footerState = displayState,
                                )
                            }
                        }

                        is WorkshopRepository.BrowseState.Error -> ErrorState(
                            message = displayState.message,
                        )

                        else -> {
                            if (displayItems.isEmpty()) {
                                EmptyState(
                                    text = if (isCreatorMode)
                                        "No wallpapers from this creator."
                                    else if (inSearchMode)
                                        "No wallpapers match your search."
                                    else "No wallpapers found.",
                                )
                            } else {
                                WallpaperGrid(
                                    gridState = gridState,
                                    items = displayItems,
                                    tileSize = tileSize,
                                    onItemClick = onItemClick,
                                    onCreatorClick = onCreatorClick,
                                    creatorNames = creatorNames,
                                    footerState = displayState,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // === Tag picker bottom sheet (also hosts the Mobile Compatible toggle) ===
    TagPickerSheet(
        visible = showTagPicker,
        excludedTags = excludedTags,
        mobileCompatible = mobileCompatible,
        onApply = { newExcluded, newMobileCompat ->
            WepWawetLogger.workshop(
                "filter_apply",
                "total_excluded" to newExcluded.size,
                "excluded" to newExcluded.joinToString(","),
                "mobile_compatible" to newMobileCompat,
            )
            excludedTags = newExcluded
            mobileCompatible = newMobileCompat
            showTagPicker = false
        },
        onDismiss = { showTagPicker = false },
    )
}

// ====== Sub-composables (kept in this file for locality) ======

/**
 * Compact single-row filter bar with horizontal scroll for narrow screens.
 *
 * v0.5.3 — the Filter button (which used to live in the TopAppBar) has been
 * moved INTO this row, so the layout is:
 *
 *     [Filter ▼] [Sort: Most Popular (Year) ▼] [Tile: Medium ▼]
 *
 * All three controls share the same horizontal row, right below the search
 * bar.
 *
 * v0.5.2 — the trend-days selector and the Mobile Compatible toggle were
 * both removed from this bar (the toggle moved into the [TagPickerSheet]
 * bottom sheet; trend-days is now part of the Sort enum). Only the Sort
 * dropdown, Tile size dropdown and the Filter button remain.
 *
 * All sub-controls are wrapped in [TextButton]s with small icons + labels to
 * keep the visual density low.
 *
 * @param filterLabel text label to show on the Filter button (e.g. "Filter",
 *   "Filter (12)", "Filter (12+mob)"). The caller is responsible for
 *   computing this label from the current `excludedTags` and
 *   `mobileCompatible` state.
 * @param onFilterClick invoked when the user taps the Filter button.
 *   Typically opens the [TagPickerSheet] bottom sheet.
 */
@Composable
private fun FilterBar(
    sort: SteamApiService.Sort,
    onSortChange: (SteamApiService.Sort) -> Unit,
    sortMenuOpen: Boolean,
    onSortMenuOpenChange: (Boolean) -> Unit,
    tileSize: TileSize,
    onTileSizeChange: (TileSize) -> Unit,
    tileMenuOpen: Boolean,
    onTileMenuOpenChange: (Boolean) -> Unit,
    sortEnabled: Boolean,
    filterLabel: String,
    onFilterClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // === Filter button (leftmost control on the row) ===
        // Opens the TagPickerSheet bottom sheet, which hosts the
        // excluded-tags picker and the Mobile Compatible toggle.
        Box {
            TextButton(onClick = onFilterClick) {
                Icon(
                    Icons.Filled.FilterList,
                    contentDescription = "Filter",
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    filterLabel,
                    style = MaterialTheme.typography.labelLarge,
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        // === Sort dropdown — shows all 8 user-facing sort options ===
        // Trend days are baked into the chosen Sort enum (Year/Month/Week/Today).
        Box {
            TextButton(
                onClick = {
                    WepWawetLogger.workshop("sort_menu open")
                    onSortMenuOpenChange(true)
                },
                enabled = sortEnabled,
            ) {
                Text(
                    "Sort: ${sort.displayName}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = "Sort",
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = sortMenuOpen,
                onDismissRequest = { onSortMenuOpenChange(false) },
            ) {
                // Show all sort options EXCEPT MOST_RELEVANT (search-only).
                // The order matches WE's screenshot:
                //   1. Top Rated
                //   2. Most Popular (Year) — DEFAULT
                //   3. Most Popular (Month)
                //   4. Most Popular (Week)
                //   5. Most Popular (Today)
                //   6. Most Recent
                //   7. Most Up Votes
                //   8. Most Subscriptions
                val orderedSorts = listOf(
                    SteamApiService.Sort.TOP_RATED,
                    SteamApiService.Sort.MOST_POPULAR_YEAR,
                    SteamApiService.Sort.MOST_POPULAR_MONTH,
                    SteamApiService.Sort.MOST_POPULAR_WEEK,
                    SteamApiService.Sort.MOST_POPULAR_TODAY,
                    SteamApiService.Sort.MOST_RECENT,
                    SteamApiService.Sort.MOST_UP_VOTES,
                    SteamApiService.Sort.MOST_SUBSCRIPTIONS,
                )
                orderedSorts.forEach { s ->
                    DropdownMenuItem(
                        text = {
                            Text(s.displayName)
                        },
                        onClick = {
                            WepWawetLogger.workshop(
                                "sort_changed",
                                "sort" to s.name,
                                "trend_days" to s.trendDays,
                            )
                            onSortChange(s)
                            onSortMenuOpenChange(false)
                        },
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        // === Tile size dropdown ===
        Box {
            TextButton(onClick = {
                WepWawetLogger.workshop("tile_size_menu open")
                onTileMenuOpenChange(true)
            }) {
                Icon(
                    Icons.Filled.ViewModule,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "Tile: ${tileSize.displayName}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = "Tile size",
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = tileMenuOpen,
                onDismissRequest = { onTileMenuOpenChange(false) },
            ) {
                TileSize.entries.forEach { ts ->
                    DropdownMenuItem(
                        text = { Text(ts.displayName) },
                        onClick = {
                            WepWawetLogger.workshop(
                                "tile_size_changed",
                                "size" to ts.name,
                            )
                            onTileSizeChange(ts)
                            onTileMenuOpenChange(false)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The grid of [WallpaperCard]s with a footer that shows loading-more / end-of-results.
 *
 * Uses [BoxWithConstraints] to detect phone-vs-tablet width buckets and adjust
 * the column count via [TileSize.columnsFor].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WallpaperGrid(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    items: List<WorkshopItemTyped>,
    tileSize: TileSize,
    onItemClick: (String) -> Unit,
    onCreatorClick: (String) -> Unit,
    creatorNames: Map<String, String>,
    footerState: WorkshopRepository.BrowseState,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < 600.dp
        val columnCount = tileSize.columnsFor(isCompact)

        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            state = gridState,
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.id }) { item ->
                val resolvedName = item.creatorSteamId?.let { creatorNames[it] }
                WallpaperCard(
                    item = item,
                    tileSize = tileSize,
                    creatorName = resolvedName,
                    onClick = {
                        WepWawetLogger.workshop(
                            "wallpaper_card_tap",
                            "id" to item.id,
                            "title" to item.title.take(80),
                        )
                        onItemClick(item.id)
                    },
                    onCreatorClick = onCreatorClick,
                )
            }

            // Footer spans all columns
            item(span = { GridItemSpan(columnCount) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when (footerState) {
                        is WorkshopRepository.BrowseState.Loading -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Loading more…",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        is WorkshopRepository.BrowseState.Done,
                        is WorkshopRepository.BrowseState.Loaded -> {
                            val isEnd = when (footerState) {
                                is WorkshopRepository.BrowseState.Done -> true
                                is WorkshopRepository.BrowseState.Loaded -> !footerState.hasMore
                                else -> false
                            }
                            if (isEnd) {
                                Text(
                                    "— End of results —",
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Spacer(Modifier.size(1.dp))
                            }
                        }

                        else -> {
                            Spacer(Modifier.size(1.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator()
            Text(
                "Loading workshop…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                Icons.Filled.AccountBox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ErrorState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(
                    "⚠️ Failed to load",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
