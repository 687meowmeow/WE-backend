package com.wepwawet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.wepwawet.data.SteamApiService
import com.wepwawet.data.WorkshopRepository
import com.wepwawet.data.model.WallpaperType
import com.wepwawet.data.model.WorkshopItemTyped
import com.wepwawet.ui.components.WallpaperCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkshopBrowserScreen(
    onItemClick: (String) -> Unit,
    onOpenPairing: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    repo: WorkshopRepository = viewModel { WorkshopRepository(SteamApiService()) },
) {
    val items by repo.browseItems.collectAsState()
    val state by repo.browseState.collectAsState()
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var activeType by remember { mutableStateOf<WallpaperType?>(null) }

    LaunchedEffect(activeType) {
        repo.startBrowse(
            WorkshopRepository.Filter(
                query = query.takeIf { it.isNotBlank() },
                tag = activeType?.let { typeTag(it) },
                sort = SteamApiService.Sort.MOST_SUBSCRIBED,
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WepWawet • Workshop") },
                actions = {
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
            // Search bar
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search wallpapers…") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search,
                ),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = {
                        scope.launch {
                            repo.startBrowse(
                                WorkshopRepository.Filter(
                                    query = query.takeIf { it.isNotBlank() },
                                    tag = activeType?.let { typeTag(it) },
                                )
                            )
                        }
                    }) { Icon(Icons.Filled.Subscriptions, contentDescription = "Search") }
                },
            )

            // Type filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = activeType == null,
                    onClick = { activeType = null },
                    label = { Text("All") },
                )
                FilterChip(
                    selected = activeType == WallpaperType.SCENE,
                    onClick = { activeType = WallpaperType.SCENE },
                    label = { Text("Scene") },
                )
                FilterChip(
                    selected = activeType == WallpaperType.VIDEO,
                    onClick = { activeType = WallpaperType.VIDEO },
                    label = { Text("Video") },
                )
                FilterChip(
                    selected = activeType == WallpaperType.WEB,
                    onClick = { activeType = WallpaperType.WEB },
                    label = { Text("Web") },
                )
                FilterChip(
                    selected = activeType == WallpaperType.APPLICATION,
                    onClick = { activeType = WallpaperType.APPLICATION },
                    label = { Text("App") },
                )
                FilterChip(
                    selected = activeType == WallpaperType.IMAGE,
                    onClick = { activeType = WallpaperType.IMAGE },
                    label = { Text("Image") },
                )
            }

            // List
            Box(modifier = Modifier.fillMaxSize()) {
                when (state) {
                    WorkshopRepository.BrowseState.Idle -> Text(
                        "Tap a filter to start browsing.",
                        modifier = Modifier.padding(16.dp),
                    )
                    is WorkshopRepository.BrowseState.Loading -> CircularProgressIndicator(
                        modifier = Modifier.padding(16.dp),
                    )
                    is WorkshopRepository.BrowseState.Error -> Text(
                        (state as WorkshopRepository.BrowseState.Error).message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                    else -> {
                        if (items.isEmpty()) {
                            Text("No wallpapers found.", modifier = Modifier.padding(16.dp))
                        }
                        LazyColumn(
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(items, key = { it.id }) { item ->
                                WallpaperCard(item = item, onClick = { onItemClick(item.id) })
                            }
                            item {
                                if ((state as? WorkshopRepository.BrowseState.Loaded)?.hasMore == true) {
                                    LaunchedEffect(items.size) {
                                        // Trigger next page when scrolled to bottom
                                        scope.launch { repo.loadNextPage() }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) { CircularProgressIndicator() }
                                } else {
                                    Text(
                                        "— End of results —",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                }
            }
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
