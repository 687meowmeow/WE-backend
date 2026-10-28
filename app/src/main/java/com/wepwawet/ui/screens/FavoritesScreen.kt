package com.wepwawet.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wepwawet.log.WepWawetLogger

/**
 * Favorites screen — list of saved creator Steam IDs.
 *
 * v0.7.1: Replaces the previous "Favorites coming soon" placeholder. Reads
 * the persisted set of saved-creator Steam IDs (stored under the
 * `saved_creators` StringSet key in the `wepwawet_settings` SharedPreferences
 * file — see [WallpaperDetailScreen]'s favorite-heart toggle) and renders
 * each as a Card row.
 *
 * Each row shows:
 *   - The creator's Steam ID (resolved display name resolution is a
 *     follow-up — for now we just show the raw ID).
 *   - A filled heart IconButton to unfavorite the creator (removes the ID
 *     from the StringSet).
 *   - The row itself is clickable to navigate to the creator's workshop
 *     page (`creator/{steamId}` route in [com.wepwawet.ui.WepWawetNavHost]).
 *
 * When the saved set is empty, shows a centered empty-state message.
 *
 * @param onBack invoked when the user taps the top-bar back arrow.
 * @param onCreatorClick invoked with a Steam ID64 when the user taps a row.
 *   Typically wires up to `nav.navigate("creator/$steamId")` in the NavHost.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onCreatorClick: (String) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("wepwawet_settings", Context.MODE_PRIVATE)
    }
    var savedCreators by remember {
        mutableStateOf(
            prefs.getStringSet("saved_creators", emptySet()) ?: emptySet()
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Favorites") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (savedCreators.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No favorites yet. Tap the heart icon on a wallpaper to save creators.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Sort so the order is stable across recompositions
                // (SharedPreferences StringSet iteration order is undefined).
                items(savedCreators.toSortedSet().toList()) { steamId ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                WepWawetLogger.workshop(
                                    "favorites_creator_tap",
                                    "steam_id" to steamId,
                                )
                                onCreatorClick(steamId)
                            },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                steamId,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    val newSet = savedCreators.toMutableSet()
                                    newSet.remove(steamId)
                                    savedCreators = newSet
                                    prefs.edit()
                                        .putStringSet("saved_creators", newSet)
                                        .apply()
                                    WepWawetLogger.workshop(
                                        "favorites_creator_removed",
                                        "steam_id" to steamId,
                                    )
                                },
                            ) {
                                Icon(
                                    Icons.Filled.Favorite,
                                    contentDescription = "Remove from favorites",
                                    tint = Color.Red,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
