package com.wepwawet.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wepwawet.data.model.WorkshopItemTyped

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperDetailScreen(
    itemId: String,
    onBack: () -> Unit,
) {
    var item by remember { mutableStateOf<WorkshopItemTyped?>(null) }
    var loading by remember { mutableStateOf(true) }
    var subscribed by remember { mutableStateOf(false) }

    LaunchedEffect(itemId) {
        loading = true
        // TODO: get from real WorkshopRepository
        item = null
        subscribed = false
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.title ?: "Loading…") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Loading…")
            }
            return@Scaffold
        }
        val current = item ?: run {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Wallpaper not found.")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                AsyncImage(
                    model = current.previewUrl,
                    contentDescription = current.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                )
            }
            item {
                Text(current.title, style = MaterialTheme.typography.headlineSmall)
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("by ${current.creator}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Type: ${current.type.displayName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Subscriptions: ${current.subscriptions}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Size: ${current.fileSizeFormatted}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Text(current.item.description,
                    style = MaterialTheme.typography.bodyMedium)
            }
            item {
                Button(onClick = {
                    subscribed = !subscribed
                    // TODO: tell WineProcessService to subscribe + download
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (subscribed) "Subscribed" else "Subscribe")
                }
            }
            item {
                Button(
                    onClick = { /* TODO: trigger wallpaper32.exe -control openWallpaper + .mpkg push */ },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = subscribed,
                ) {
                    Icon(Icons.Filled.Send, contentDescription = null)
                    Text("Send to phone")
                }
            }
        }
    }
}
