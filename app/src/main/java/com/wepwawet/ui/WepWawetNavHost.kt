package com.wepwawet.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wepwawet.data.SteamApiService
import com.wepwawet.data.WorkshopRepository
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.ui.screens.DownloadManagerScreen
import com.wepwawet.ui.screens.LogsScreen
import com.wepwawet.ui.screens.PairingScreen
import com.wepwawet.ui.screens.SettingsScreen
import com.wepwawet.ui.screens.WallpaperDetailScreen
import com.wepwawet.ui.screens.WorkshopBrowserScreen

/**
 * WepWawet navigation host. Seven destinations:
 *
 *   - workshop             (default landing — WorkshopBrowserScreen, global browse)
 *   - creator/{steamId}    (WorkshopBrowserScreen scoped to a single creator)
 *   - downloads            (DownloadManagerScreen showing active + completed downloads)
 *   - detail/{itemId}     (WallpaperDetailScreen — workshop item detail)
 *   - pairing             (PairingScreen)
 *   - settings            (SettingsScreen)
 *   - logs                (LogsScreen)
 *
 * A single [WorkshopRepository] is created at the NavHost level (via [remember])
 * so that the WorkshopBrowserScreen and WallpaperDetailScreen share the same
 * in-memory cache, browse session, and search session. If they each created
 * their own instance, navigation between them would lose all loaded items +
 * force a fresh fetch.
 *
 * The `creator/{steamId}` route opens a new [WorkshopBrowserScreen] with
 * `creatorSteamId` set — the repo's `creatorSteamId` filter scopes the
 * fetch to that creator's /profiles/<steamId>/myworkshop/ page. A back arrow
 * is shown so the user can return to the global browse.
 *
 * v0.5.3 — the [SteamApiService] instance is now created separately (rather
 * than anonymously inside the [WorkshopRepository] constructor) so that the
 * saved Steam Web API key can be loaded from SharedPreferences (written by
 * the Settings screen under the `steam_api_key` key in the
 * `wepwawet_settings` prefs file) and set on `steamApi.apiKey` via a
 * [LaunchedEffect]. This makes the server-side sort options (Most Up Votes,
 * Most Subscriptions) available as soon as the user re-enters the workshop
 * browser, without requiring an app restart.
 *
 * v0.5.2 — the "Open Pairing Screen" button was removed from the Settings
 * screen entirely. Instead, a Bluetooth (pairing) icon was added to the
 * WorkshopBrowserScreen's top bar — both the global and creator routes wire
 * up `onOpenPairing` here to navigate to the `pairing` destination.
 *
 * The previous `steam_login` route and its wrapping `SteamLoginScreen`
 * composable have been removed — Steam credentials are now managed directly
 * on the Settings screen (see [SettingsScreen] / [com.wepwawet.pairing.SteamCredentialsStore]).
 */
@Composable
fun WepWawetNavHost() {
    val nav = rememberNavController()
    val context = LocalContext.current

    // Shared [SteamApiService] + [WorkshopRepository] instances — see class doc.
    //
    // v0.5.3 — we create the [SteamApiService] separately so we can load the
    // Steam Web API key from SharedPreferences (stored by the Settings screen
    // under the `wepwawet_settings` file via the `steam_api_key` key) and set
    // it on the service BEFORE the repository starts issuing browse requests.
    // The key is required for the IPublishedFileService/QueryFiles server-side
    // sort options (Most Up Votes, Most Subscriptions); without it, those sorts
    // fall back to HTML scraping (which doesn't support them).
    val steamApi = remember { SteamApiService(appContext = context.applicationContext) }
    val repo = remember { WorkshopRepository(steamApi) }

    // Reload the Steam Web API key every time the user returns to the NavHost
    // (e.g. after saving a new key in Settings). Uses a lifecycle observer so
    // the key is always current.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                val prefs = context.getSharedPreferences("wepwawet_settings", Context.MODE_PRIVATE)
                val savedKey = prefs.getString("steam_api_key", null)
                steamApi.apiKey = savedKey
                WepWawetLogger.steam(
                    "nav_host_steam_api_key_loaded",
                    "is_set" to (savedKey != null),
                    "length" to (savedKey?.length ?: 0),
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    NavHost(navController = nav, startDestination = "workshop") {

        composable("workshop") {
            WorkshopBrowserScreen(
                onItemClick = { id -> nav.navigate("detail/$id") },
                onOpenDownloads = { nav.navigate("downloads") },
                onOpenSettings = { nav.navigate("settings") },
                onOpenLogs = { nav.navigate("logs") },
                onOpenPairing = { nav.navigate("pairing") },
                onCreatorClick = { steamId -> nav.navigate("creator/$steamId") },
                repo = repo,
            )
        }

        composable(
            route = "creator/{steamId}",
            arguments = listOf(navArgument("steamId") { type = NavType.StringType }),
        ) { entry ->
            val steamId = entry.arguments?.getString("steamId").orEmpty()
            WepWawetLogger.lifecycle("NavHost navigate to creator",
                "steam_id" to steamId)
            WorkshopBrowserScreen(
                onItemClick = { id -> nav.navigate("detail/$id") },
                onOpenDownloads = { nav.navigate("downloads") },
                onOpenSettings = { nav.navigate("settings") },
                onOpenLogs = { nav.navigate("logs") },
                onOpenPairing = { nav.navigate("pairing") },
                onCreatorClick = { /* no nested creator navigation */ },
                onBack = { nav.popBackStack() },
                creatorSteamId = steamId,
                repo = repo,
            )
        }

        composable("downloads") {
            WepWawetLogger.lifecycle("NavHost navigate to downloads")
            DownloadManagerScreen(onBack = { nav.popBackStack() })
        }

        composable(
            route = "detail/{itemId}",
            arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
        ) { entry ->
            val itemId = entry.arguments?.getString("itemId").orEmpty()
            WepWawetLogger.lifecycle("NavHost navigate to detail",
                "item_id" to itemId)
            WallpaperDetailScreen(
                itemId = itemId,
                onBack = { nav.popBackStack() },
                repo = repo,
                onCreatorClick = { steamId -> nav.navigate("creator/$steamId") },
            )
        }

        composable("pairing") {
            PairingScreen(onBack = { nav.popBackStack() })
        }

        composable("settings") {
            SettingsScreen(
                onBack = { nav.popBackStack() },
            )
        }

        composable("logs") {
            LogsScreen(onBack = { nav.popBackStack() })
        }
    }
}
