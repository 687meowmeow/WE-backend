package com.wepwawet.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wepwawet.data.SteamApiService
import com.wepwawet.data.WorkshopRepository
import com.wepwawet.data.model.WallpaperType
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.ui.components.WallpaperCard
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Workshop browser screen — the default landing screen for WepWawet.
 *
 * Layout:
 *   - TopAppBar with Pairing / Settings / Logs action icons
 *   - Search bar (debounced text → triggers [WorkshopRepository.startSearch])
 *   - Sort dropdown (Most Recent / Most Subscribed / Most Favorited / Trending)
 *   - Type filter chips (All / Scene / Video / App / Image / Web)
 *   - Paginated LazyColumn of [WallpaperCard]
 *       - Pull-to-refresh via M3 PullToRefreshBox
 *       - Infinite scroll: when the last visible item is within 3 of the end,
 *         load the next page (snapshotFlow + derivedStateOf)
 *       - End-of-results indicator when no more pages
 *   - Empty / Error / Loading states
 *
 * Every user interaction (filter change, sort change, search submit, card tap,
 * pull-to-refresh) is logged via [WepWawetLogger.workshop].
 *
 * @param repo shared workshop repository (created in [com.wepwawet.ui.WepWawetNavHost])
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun WorkshopBrowserScreen(
    onItemClick: (String) -> Unit,
    onOpenPairing: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    repo: WorkshopRepository,
) {
    val items by repo.browseItems.collectAsState()
    val searchItems by repo.searchItems.collectAsState()
    val browseState by repo.browseState.collectAsState()
    val searchState by repo.searchState.collectAsState()
    val isRefreshing by repo.isRefreshing.collectAsState()
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var activeType by remember { mutableStateOf<WallpaperType?>(null) }
    var sort by remember { mutableStateOf(SteamApiService.Sort.MOST_SUBSCRIBED) }

    // === Sort dropdown state ===
    var sortMenuOpen by remember { mutableStateOf(false) }

    // === Lazy list state for infinite scroll ===
    val listState = rememberLazyListState()

    // === Auto-start a fresh browse session on first composition ===
    LaunchedEffect(Unit) {
        try {
            WepWawetLogger.workshop("WorkshopBrowserScreen first composition — startBrowse")
            repo.startBrowse(
                WorkshopRepository.Filter(
                    query = null,
                    tag = activeType?.let { typeTag(it) },
                    sort = sort,
                )
            )
        } catch (e: Exception) {
            WepWawetLogger.workshop("startBrowse failed on first composition",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
        }
    }

    // === Re-browse when sort or tag changes (not on query — query is debounced below) ===
    LaunchedEffect(sort, activeType) {
        try {
            WepWawetLogger.workshop("filter/sort changed — restarting browse",
                "sort" to sort.browseParam,
                "tag" to (activeType?.let { typeTag(it) } ?: "all"))
            repo.clearSearch()  // exit search mode if active
            query = ""           // clear the search bar
            repo.startBrowse(
                WorkshopRepository.Filter(
                    query = null,
                    tag = activeType?.let { typeTag(it) },
                    sort = sort,
                )
            )
        } catch (e: Exception) {
            WepWawetLogger.workshop("re-browse failed",
                "exception" to e.javaClass.name)
        }
    }

    // === Debounced search: when the user types, after 500ms of quiet, fire startSearch ===
    LaunchedEffect(Unit) {
        snapshotFlow { query }
            .debounce(500)
            .distinctUntilChanged()
            .collect { q ->
                if (q.isBlank()) {
                    if (repo.searchItems.value.isNotEmpty()) {
                        WepWawetLogger.workshop("query cleared — clearSearch")
                        repo.clearSearch()
                    }
                } else {
                    WepWawetLogger.workshop("debounced query — startSearch",
                        "query" to q.take(80))
                    repo.startSearch(q)
                }
            }
    }

    // === Infinite scroll: detect when last visible item is within 3 of the end ===
    val shouldLoadMore by remember(listState, browseState, searchState, items.size, searchItems.size) {
        derivedStateOf {
            val total = if (repo.searchItems.value.isNotEmpty())
                repo.searchItems.value.size else items.size
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && lastVisible >= total - 3
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            WepWawetLogger.workshop("infinite-scroll triggered — loadNextPage",
                "last_visible" to listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index,
                "total" to if (repo.searchItems.value.isNotEmpty())
                    repo.searchItems.value.size else items.size)
            repo.loadNextPage()
        }
    }

    // === Pull-to-refresh ===
    val onRefresh: () -> Unit = {
        WepWawetLogger.workshop("pull-to-refresh triggered")
        scope.launch { repo.refresh() }
    }

    // === Active state for display ===
    val inSearchMode = repo.searchItems.value.isNotEmpty() || (query.isNotBlank() && searchState !is WorkshopRepository.BrowseState.Idle)
    val displayState = if (inSearchMode) searchState else browseState
    val displayItems = if (inSearchMode) searchItems else items

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WepWawet • Workshop") },
                actions = {
                    // Pull-to-refresh shortcut button
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenPairing) {
                        Icon(Icons.Filled.Bluetooth, contentDescription = "Pairing")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = onOpenLogs) {
                        Icon(Icons.Filled.Terminal, contentDescription = "Logs")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding),
        ) {
            // === Search bar ===
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search wallpapers…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        WepWawetLogger.workshop("IME search action — startSearch",
                            "query" to query.take(80))
                        if (query.isBlank()) repo.clearSearch()
                        else scope.launch { repo.startSearch(query) }
                    }
                ),
                singleLine = true,
            )

            // === Sort dropdown + filter chips ===
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text(
                            text = sort.displayName,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        IconButton(onClick = {
                            WepWawetLogger.workshop("sort menu open")
                            sortMenuOpen = true
                        }) {
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Sort")
                        }
                    }
                    DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false },
                    ) {
                        SteamApiService.Sort.entries
                            .filter { it != SteamApiService.Sort.MOST_RELEVANT }
                            .forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.displayName) },
                                    onClick = {
                                        WepWawetLogger.workshop("sort selected",
                                            "sort" to s.browseParam)
                                        sort = s
                                        sortMenuOpen = false
                                    },
                                )
                            }
                    }
                }
            }

            // === Type filter chips ===
            LazyTypeFilterRow(
                activeType = activeType,
                onSelect = { type ->
                    WepWawetLogger.workshop("filter chip selected",
                        "type" to (type?.displayName ?: "All"))
                    activeType = type
                },
            )

            // === List (with pull-to-refresh) ===
            Box(modifier = Modifier.fillMaxSize()) {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    when (displayState) {
                        WorkshopRepository.BrowseState.Idle -> EmptyState(
                            text = "Tap a filter to start browsing.",
                        )
                        is WorkshopRepository.BrowseState.Loading -> {
                            if (displayItems.isEmpty()) {
                                LoadingState()
                            } else {
                                WallpaperList(
                                    listState = listState,
                                    items = displayItems,
                                    onItemClick = onItemClick,
                                    footerState = displayState,
                                )
                            }
                        }
                        is WorkshopRepository.BrowseState.Error -> ErrorState(
                            message = (displayState as WorkshopRepository.BrowseState.Error).message,
                        )
                        else -> {
                            if (displayItems.isEmpty()) {
                                EmptyState(
                                    text = if (inSearchMode)
                                        "No wallpapers match your search."
                                    else "No wallpapers found.",
                                )
                            } else {
                                WallpaperList(
                                    listState = listState,
                                    items = displayItems,
                                    onItemClick = onItemClick,
                                    footerState = displayState,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ====== Sub-composables (kept in this file for locality) ======

@Composable
private fun LazyTypeFilterRow(
    activeType: WallpaperType?,
    onSelect: (WallpaperType?) -> Unit,
) {
    val chips: List<Pair<String, WallpaperType?>> = listOf(
        "All" to null,
        "Scene" to WallpaperType.SCENE,
        "Video" to WallpaperType.VIDEO,
        "App" to WallpaperType.APPLICATION,
        "Image" to WallpaperType.IMAGE,
        "Web" to WallpaperType.WEB,
    )

    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { (label, type) ->
            FilterChip(
                selected = activeType == type,
                onClick = { onSelect(type) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun WallpaperList(
    listState: LazyListState,
    items: List<com.wepwawet.data.model.WorkshopItemTyped>,
    onItemClick: (String) -> Unit,
    footerState: WorkshopRepository.BrowseState,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }) { item ->
            WallpaperCard(item = item, onClick = {
                WepWawetLogger.workshop("workshop card tap",
                    "id" to item.id, "title" to item.title.take(80))
                onItemClick(item.id)
            })
        }
        item {
            // Footer / end-of-results / loading indicator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    footerState is WorkshopRepository.BrowseState.Loading -> {
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
                    footerState is WorkshopRepository.BrowseState.Done ||
                        (footerState is WorkshopRepository.BrowseState.Loaded &&
                            !(footerState as WorkshopRepository.BrowseState.Loaded).hasMore) -> {
                        Text(
                            "— End of results —",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        // Hidden by zero-size Box to avoid jumping
                        Spacer(Modifier.height(1.dp))
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
            Text(
                "⚠️ Failed to load",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun typeTag(type: WallpaperType): String = when (type) {
    WallpaperType.SCENE -> "Scene"
    WallpaperType.VIDEO -> "Video"
    WallpaperType.WEB -> "Web"
    WallpaperType.APPLICATION -> "Application"
    WallpaperType.IMAGE -> "Image"
    WallpaperType.PLAYLIST -> "Playlist"
    WallpaperType.UNKNOWN -> ""
}
