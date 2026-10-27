package com.wepwawet.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wepwawet.BuildConfig
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.pairing.SteamCredentialsStore
import com.wepwawet.pairing.WorkshopBrowseSession
import com.wepwawet.pairing.WorkshopDownloader
import kotlinx.coroutines.launch

/**
 * SharedPreferences file used by the Settings screen for persisting user
 * preferences that don't belong to any particular subsystem.
 *
 * Currently hosts:
 *   - The verbose-logging toggle ([KEY_VERBOSE_LOG])
 *   - The Steam Web API key ([KEY_STEAM_API_KEY]) — required for the
 *     IPublishedFileService/QueryFiles server-side sort options (Most Up
 *     Votes, Most Subscriptions) used by [com.wepwawet.data.SteamApiService].
 *     WepWawetNavHost loads this key into the shared [SteamApiService]
 *     instance at startup so server-side sorting is available as soon as
 *     the user re-enters the workshop browser.
 *
 * Subsystem-specific state (Steam credentials, refresh tokens, etc.) lives
 * in its own dedicated SharedPreferences files — see [SteamCredentialsStore]
 * and [WorkshopDownloader] for those.
 *
 * v0.5.2: the Pairing section has been removed entirely from this screen
 * (a Bluetooth button was added to the WorkshopBrowserScreen's top bar
 * instead, since the pairing proxy always auto-starts in
 * [com.wepwawet.MainActivity] onCreate — there's no longer any user-facing
 * decision to make here).
 */
private const val PREFS_NAME = "wepwawet_settings"
private const val KEY_VERBOSE_LOG = "verbose_log"
private const val KEY_STEAM_API_KEY = "steam_api_key"

/**
 * The WepWawet Settings screen.
 *
 * v0.5.3 — a new "Steam Web API" card has been added. It lets the user
 * enter and save a Steam Web API key, which is required for the
 * IPublishedFileService/QueryFiles server-side sort options (Most Up Votes,
 * Most Subscriptions). The key is persisted to [PREFS_NAME] SharedPreferences
 * and loaded into the shared [com.wepwawet.data.SteamApiService] instance
 * by [com.wepwawet.ui.WepWawetNavHost] at app startup.
 *
 * v0.5.2 — the Pairing section has been removed (a dedicated Bluetooth button
 * on the WorkshopBrowserScreen's top bar now opens the Pairing screen, since
 * the pairing proxy always auto-starts in [com.wepwawet.MainActivity] onCreate
 * and there's no user-facing decision to make here).
 *
 * Renders four cards inside a scrollable [LazyColumn]:
 *
 *  1. **Steam Account** — username/password fields, Save / Test Login / Clear
 *  2. **Steam Web API** — API key field + Save
 *  3. **Display** — verbose logging switch
 *  4. **About** — app name, version, git SHA, build time
 *
 * The verbose-log toggle is persisted immediately to [PREFS_NAME]
 * SharedPreferences so it survives screen exits and app restarts. The saved
 * value is loaded once on screen entry via a [LaunchedEffect].
 *
 * @param onBack Called when the user taps the back arrow in the
 *               TopAppBar — typically `navController.popBackStack()`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // ---- Persisted toggle state (verbose log) ----
    var verboseLog by remember { mutableStateOf(true) }

    // ---- Steam Web API key state ----
    var steamApiKey by remember { mutableStateOf("") }
    var steamApiKeySaved by remember { mutableStateOf(false) }

    // Load saved preference values once on screen entry. We use Unit as the
    // key so this runs a single time per composition (not on every recompose).
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        verboseLog = prefs.getBoolean(KEY_VERBOSE_LOG, true)
        val savedKey = prefs.getString(KEY_STEAM_API_KEY, "") ?: ""
        steamApiKey = savedKey
        steamApiKeySaved = savedKey.isNotBlank()
    }

    // ---- Steam credentials state ----
    var steamUsername by remember {
        mutableStateOf(SteamCredentialsStore.getUsername(context) ?: "")
    }
    var steamPassword by remember { mutableStateOf("") }
    var testLoginResult by remember { mutableStateOf<String?>(null) }
    var testLoginInProgress by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ===== 1. Steam Account =====
            item {
                SteamAccountCard(
                    savedUsername = SteamCredentialsStore.getUsername(context),
                    hasCredentials = SteamCredentialsStore.hasCredentials(context),
                    username = steamUsername,
                    password = steamPassword,
                    onUsernameChange = { steamUsername = it },
                    onPasswordChange = { steamPassword = it },
                    onSave = {
                        if (steamUsername.isBlank() || steamPassword.isBlank()) return@SteamAccountCard
                        SteamCredentialsStore.saveCredentials(
                            context, steamUsername, steamPassword,
                        )
                        WepWawetLogger.appInfo(
                            "settings_steam_credentials_saved",
                            "username" to steamUsername,
                        )
                        // Clear the in-memory password field for safety; the
                        // username is left populated since it's already shown.
                        steamPassword = ""
                    },
                    onTestLogin = {
                        if (!SteamCredentialsStore.hasCredentials(context)) {
                            testLoginResult = "Save credentials first"
                            return@SteamAccountCard
                        }
                        testLoginInProgress = true
                        testLoginResult = null
                        WepWawetLogger.appInfo("settings_test_login_clicked")
                        coroutineScope.launch {
                            val result = WorkshopDownloader.testLogin(context)
                            testLoginInProgress = false
                            testLoginResult = result
                        }
                    },
                    onClear = {
                        SteamCredentialsStore.clearCredentials(context)
                        // v0.5.32 (audit L5): also disconnect the browse
                        // session singleton so its cached WorkshopDownloader
                        // (which holds an open TCP connection to a Steam CM
                        // and the user's now-invalidated refresh token) is
                        // cleaned up. Without this, the singleton downloader
                        // stays alive for the rest of the app process,
                        // holding a stale Steam connection + stale auth
                        // state. The next browse query would either fail
                        // (token invalid) or worse, dispatch a late
                        // onLoggedOn callback that mutates `authenticated`
                        // mid-query. The disconnect() docstring already
                        // specified this intent ("Used when the user logs
                        // out of Steam in Settings") — this wires it up.
                        WorkshopBrowseSession.disconnect()
                        WepWawetLogger.appInfo("settings_steam_credentials_cleared")
                        steamUsername = ""
                        steamPassword = ""
                        testLoginResult = null
                    },
                    testLoginInProgress = testLoginInProgress,
                    testLoginResult = testLoginResult,
                )
            }

            // ===== 2. Steam Web API =====
            item {
                SteamWebApiCard(
                    apiKey = steamApiKey,
                    apiKeySaved = steamApiKeySaved,
                    onApiKeyChange = { steamApiKey = it },
                    onSave = {
                        val trimmed = steamApiKey.trim()
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putString(KEY_STEAM_API_KEY, trimmed)
                            .apply()
                        steamApiKey = trimmed
                        steamApiKeySaved = trimmed.isNotBlank()
                        WepWawetLogger.appInfo(
                            "settings_steam_api_key_saved",
                            "length" to trimmed.length,
                            "is_set" to trimmed.isNotBlank(),
                        )
                    },
                )
            }

            // ===== 3. Display =====
            item {
                DisplayCard(
                    verboseLog = verboseLog,
                    onVerboseLogChange = { enabled ->
                        verboseLog = enabled
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(KEY_VERBOSE_LOG, enabled)
                            .apply()
                        WepWawetLogger.appInfo(
                            "settings_verbose_log_changed",
                            "enabled" to enabled,
                        )
                    },
                )
            }

            // ===== 4. About =====
            item {
                AboutCard()
            }

            // ===== 5. Cache =====
            item {
                CacheCard(context = context)
            }
        }
    }
}

/**
 * Section 1 — Steam Account.
 *
 * Lets the user enter, save, test and clear Steam credentials used by
 * JavaSteam for direct workshop downloads (no PC required). Shows the
 * currently saved username, if any.
 *
 * The card deliberately contains only minimal helper text — the
 * previous "first login" push-notification explanation has been removed.
 *
 * @param savedUsername       The username persisted by [SteamCredentialsStore], or null.
 * @param hasCredentials      Whether [SteamCredentialsStore] currently has both
 *                            username and password saved.
 * @param username            The text currently in the username field.
 * @param password            The text currently in the password field.
 * @param onUsernameChange    Called when the username field changes.
 * @param onPasswordChange    Called when the password field changes.
 * @param onSave              Called when the user taps "Save Steam credentials".
 * @param onTestLogin         Called when the user taps "Test Steam Login".
 * @param onClear             Called when the user taps "Clear saved credentials".
 * @param testLoginInProgress Whether a test-login request is currently running.
 * @param testLoginResult     The most recent test-login result string, if any.
 */
@Composable
private fun SteamAccountCard(
    savedUsername: String?,
    hasCredentials: Boolean,
    username: String,
    password: String,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSave: () -> Unit,
    onTestLogin: () -> Unit,
    onClear: () -> Unit,
    testLoginInProgress: Boolean,
    testLoginResult: String?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Steam account", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            Text(
                "Enter your Steam credentials to download wallpapers.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))

            // Show saved status (only username — never display the password)
            Text(
                if (hasCredentials) {
                    "Credentials saved for: ${savedUsername ?: "unknown"}"
                } else {
                    "No Steam credentials saved"
                },
                color = if (hasCredentials)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text("Steam username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("Steam password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onSave,
                enabled = username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save Steam credentials")
            }
            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = onTestLogin,
                enabled = !testLoginInProgress && hasCredentials,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (testLoginInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Testing login...")
                } else {
                    Text("Test Steam Login")
                }
            }

            testLoginResult?.let { result ->
                Spacer(Modifier.height(8.dp))
                Text(
                    result,
                    color = if (result.contains("✓"))
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (hasCredentials) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Clear saved credentials")
                }
            }
        }
    }
}

/**
 * Section 2 — Steam Web API.
 *
 * Lets the user enter and save a Steam Web API key. The key is required for
 * the IPublishedFileService/QueryFiles server-side sort options (Most Up
 * Votes, Most Subscriptions) that [com.wepwawet.data.SteamApiService] uses
 * when the user picks those sorts in the workshop browser.
 *
 * The key is persisted to [PREFS_NAME] SharedPreferences under the
 * [KEY_STEAM_API_KEY] entry. [com.wepwawet.ui.WepWawetNavHost] loads it
 * into the shared [com.wepwawet.data.SteamApiService] instance at app
 * startup, so the user does not need to restart the app after entering
 * the key — exiting and re-entering the workshop browser is sufficient.
 *
 * The field uses [PasswordVisualTransformation] so the key is masked while
 * typing (Steam Web API keys are 32-char hex strings and look like a
 * password).
 *
 * @param apiKey        The text currently in the API key field.
 * @param apiKeySaved   Whether a non-blank API key is currently persisted.
 *                      Used to show a "key is set" status line.
 * @param onApiKeyChange Called when the text in the API key field changes.
 * @param onSave         Called when the user taps "Save". The caller is
 *                       responsible for persisting the new value to
 *                       SharedPreferences and updating `apiKeySaved`.
 */
@Composable
private fun SteamWebApiCard(
    apiKey: String,
    apiKeySaved: Boolean,
    onApiKeyChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Steam Web API", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            // Helper text — explains WHY the key is needed and WHERE to
            // obtain one. Mentioned specifically because Most Up Votes and
            // Most Subscriptions are otherwise silently unavailable.
            Text(
                "Required for server-side sorting (Most Up Votes, Most " +
                    "Subscriptions). Get your key at " +
                    "steamcommunity.com/dev/apikey",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))

            // Show saved status so the user knows whether a key is currently
            // active in the running [SteamApiService] instance.
            Text(
                if (apiKeySaved) "API key is set"
                else "No API key saved — server-side sorts will fall back to HTML scraping",
                color = if (apiKeySaved)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("Steam Web API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onSave,
                enabled = apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
    }
}

/**
 * Section 3 — Display.
 *
 * Currently only hosts the verbose-logging toggle.
 *
 * @param verboseLog         Current value of the verbose-log toggle.
 * @param onVerboseLogChange Called with the new value when the user toggles
 *                           the switch. The caller is responsible for
 *                           persisting the new value.
 */
@Composable
private fun DisplayCard(
    verboseLog: Boolean,
    onVerboseLogChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Display", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            Text(
                "Verbose logging",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Capture detailed protocol messages for debugging.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (verboseLog) "Enabled" else "Disabled")
                Switch(checked = verboseLog, onCheckedChange = onVerboseLogChange)
            }
        }
    }
}

/**
 * Section 4 — About.
 *
 * Minimal info card showing the app name, version, git SHA and build time.
 * The previous debug strings ("mpkg: PKGM0020 (VFS verified)" and
 * "Status: PKGM0020") have been removed.
 */
@Composable
private fun AboutCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("About", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("WepWawet — Wallpaper Engine companion")
            Spacer(Modifier.height(4.dp))
            Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
            Spacer(Modifier.height(4.dp))
            Text(
                "Git: ${BuildConfig.GIT_SHA}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                "Built: ${BuildConfig.BUILD_TIME}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * Section 5 — Cache.
 *
 * Shows the current cache size and provides a "Clear cache" button that
 * deletes all files in the app's cache directory (downloaded .pkg files,
 * .mpkg files, texture conversion cache, etc.).
 */
@Composable
private fun CacheCard(context: Context) {
    var cacheSize by remember { mutableStateOf(0L) }
    var cleared by remember { mutableStateOf(false) }

    // Compute cache size
    LaunchedEffect(cleared) {
        cacheSize = try {
            var size = 0L
            context.cacheDir.walkTopDown().forEach { f ->
                if (f.isFile) size += f.length()
            }
            size
        } catch (_: Exception) { 0L }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Cache", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Cache size: ${formatCacheSize(cacheSize)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Clears downloaded .pkg files, .mpkg files, and texture conversion cache.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    WepWawetLogger.appInfo("settings_clear_cache_clicked")
                    try {
                        context.cacheDir.walkTopDown().forEach { f ->
                            if (f.isFile) f.delete()
                        }
                        // Also clear the external cache dir if it exists
                        context.externalCacheDir?.walkTopDown()?.forEach { f ->
                            if (f.isFile) f.delete()
                        }
                        cleared = !cleared
                        WepWawetLogger.appInfo("settings_clear_cache_done")
                    } catch (e: Exception) {
                        WepWawetLogger.appInfo("settings_clear_cache_failed",
                            "exception" to e.javaClass.name)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Clear Cache")
            }
        }
    }
}

private fun formatCacheSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> "%.2f GB".format(gb)
        mb >= 1 -> "%.1f MB".format(mb)
        kb >= 1 -> "%.0f KB".format(kb)
        else -> "$bytes B"
    }
}
