package com.wepwawet.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wepwawet.ui.screens.LogsScreen
import com.wepwawet.ui.screens.PairingScreen
import com.wepwawet.ui.screens.SettingsScreen
import com.wepwawet.ui.screens.WallpaperDetailScreen
import com.wepwawet.ui.screens.WorkshopBrowserScreen

/**
 * WepWawet navigation host. Four top-level destinations + one detail screen.
 *
 * Routes:
 *   - workshop      (default landing)
 *   - pairing
 *   - settings
 *   - logs
 *   - detail/{itemId}  (workshop item detail)
 */
@Composable
fun WepWawetNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "workshop") {

        composable("workshop") {
            WorkshopBrowserScreen(
                onItemClick = { id -> nav.navigate("detail/$id") },
                onOpenPairing = { nav.navigate("pairing") },
                onOpenSettings = { nav.navigate("settings") },
                onOpenLogs = { nav.navigate("logs") },
            )
        }

        composable(
            route = "detail/{itemId}",
            arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
        ) { entry ->
            val itemId = entry.arguments?.getString("itemId").orEmpty()
            WallpaperDetailScreen(
                itemId = itemId,
                onBack = { nav.popBackStack() },
            )
        }

        composable("pairing") {
            PairingScreen(onBack = { nav.popBackStack() })
        }

        composable("settings") {
            SettingsScreen(onBack = { nav.popBackStack() })
        }

        composable("logs") {
            LogsScreen(onBack = { nav.popBackStack() })
        }
    }
}
