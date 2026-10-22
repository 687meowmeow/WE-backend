package com.wepwawet.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wepwawet.auth.SteamLoginActivity
import com.wepwawet.data.SteamApiService
import com.wepwawet.data.WorkshopRepository
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.ui.screens.LogsScreen
import com.wepwawet.ui.screens.PairingScreen
import com.wepwawet.ui.screens.SettingsScreen
import com.wepwawet.ui.screens.WallpaperDetailScreen
import com.wepwawet.ui.screens.WorkshopBrowserScreen

/**
 * WepWawet navigation host. Six destinations:
 *
 *   - workshop       (default landing — WorkshopBrowserScreen)
 *   - detail/{itemId} (WallpaperDetailScreen — workshop item detail)
 *   - pairing        (PairingScreen)
 *   - settings       (SettingsScreen)
 *   - logs           (LogsScreen)
 *   - steam_login    (SteamLoginScreen — launches [SteamLoginActivity] WebView)
 *
 * A single [WorkshopRepository] is created at the NavHost level (via [remember])
 * so that the WorkshopBrowserScreen and WallpaperDetailScreen share the same
 * in-memory cache, browse session, and search session. If they each created
 * their own instance, navigation between them would lose all loaded items +
 * force a fresh fetch.
 *
 * == Routes added per Task 7 ==
 *
 *   - workshop       (pre-existing, kept)
 *   - detail/{itemId} (pre-existing, kept — now passes shared [repo])
 *   - steam_login     (NEW — wraps [SteamLoginActivity] launch + logout)
 */
@Composable
fun WepWawetNavHost() {
    val nav = rememberNavController()
    // Shared repository instance — see class doc
    val repo = remember { WorkshopRepository(SteamApiService()) }

    NavHost(navController = nav, startDestination = "pairing") {

        composable("workshop") {
            WorkshopBrowserScreen(
                onItemClick = { id -> nav.navigate("detail/$id") },
                onOpenPairing = { nav.navigate("pairing") },
                onOpenSettings = { nav.navigate("settings") },
                onOpenLogs = { nav.navigate("logs") },
                repo = repo,
            )
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
            )
        }

        composable("steam_login") {
            SteamLoginScreen(onBack = { nav.popBackStack() })
        }

        composable("pairing") {
            PairingScreen(onBack = { nav.popBackStack() })
        }

        composable("settings") {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenSteamLogin = { nav.navigate("steam_login") },
            )
        }

        composable("logs") {
            LogsScreen(onBack = { nav.popBackStack() })
        }
    }
}

/**
 * Steam login screen — wraps the [SteamLoginActivity] WebView login flow.
 *
 * Shows:
 *   - "Sign in to Steam" button → launches [SteamLoginActivity] via
 *     [rememberLauncherForActivityResult]
 *   - The current Steam login status (logged in as <steamid> / not logged in)
 *   - "Log out" button → wipes the stored cookies via [SteamLoginActivity.logout]
 *
 * After a successful login, the result Intent carries the cookie header string,
 * which we display in the UI for the user's reference (and so they can verify
 * it was captured correctly).
 *
 * This screen is reachable from [SettingsScreen] via the new
 * [SettingsScreen.onOpenSteamLogin] callback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SteamLoginScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var loggedIn by remember { mutableStateOf(SteamLoginActivity.isLoggedIn(context)) }
    var steamId by remember { mutableStateOf(SteamLoginActivity.getSteamId(context)) }
    var cookiesPreview by remember { mutableStateOf(SteamLoginActivity.getCookies(context)) }

    // The launcher for the WebView activity. On RESULT_OK we read the cookies
    // from the result Intent + re-fetch from SharedPreferences for persistence
    // verification.
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        WepWawetLogger.lifecycle("SteamLoginScreen result",
            "result_code" to result.resultCode)
        // Re-read from the canonical store (the activity writes to the same prefs)
        loggedIn = SteamLoginActivity.isLoggedIn(context)
        steamId = SteamLoginActivity.getSteamId(context)
        cookiesPreview = SteamLoginActivity.getCookies(context)
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.getStringExtra(SteamLoginActivity.EXTRA_RESULT_COOKIES)?.let {
                cookiesPreview = it
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Steam Login") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))

            Text(
                text = "Sign in to Steam",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "WepWawet needs your Steam cookies to subscribe to and " +
                    "download workshop items. We open the official Steam login " +
                    "page in a secure WebView — your password never leaves " +
                    "Steam's servers.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (loggedIn) {
                Text(
                    text = "Logged in as ${steamId ?: "unknown Steam ID"}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(
                    onClick = {
                        WepWawetLogger.lifecycle("SteamLoginScreen logout tapped")
                        SteamLoginActivity.logout(context)
                        loggedIn = false
                        steamId = null
                        cookiesPreview = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Logout, contentDescription = null)
                    Spacer(Modifier.height(0.dp))
                    Text("  Log out")
                }
            } else {
                Button(
                    onClick = {
                        WepWawetLogger.lifecycle("SteamLoginScreen sign-in tapped")
                        launcher.launch(
                            android.content.Intent(context, SteamLoginActivity::class.java)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Login, contentDescription = null)
                    Text("  Sign in to Steam")
                }
            }

            cookiesPreview?.let { cookies ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Captured cookies (truncated):",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = cookies.take(120) + if (cookies.length > 120) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
