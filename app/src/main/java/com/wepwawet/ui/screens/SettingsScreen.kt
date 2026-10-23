package com.wepwawet.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Login
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wepwawet.BuildConfig
import com.wepwawet.auth.SteamLoginActivity
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSteamLogin: () -> Unit = {},
) {
    val context = LocalContext.current
    var autoStart by remember { mutableStateOf(false) }
    var verboseLog by remember { mutableStateOf(true) }
    // Re-check login state on every recomposition (cheap SharedPreferences read)
    var steamLoggedIn by remember { mutableStateOf(SteamLoginActivity.isLoggedIn(context)) }
    var steamId by remember { mutableStateOf(SteamLoginActivity.getSteamId(context)) }

    // GGNetwork login state
    val ggntwScope = rememberCoroutineScope()
    var ggntwEmail by remember { mutableStateOf(com.wepwawet.pairing.GgntwApiClient.getEmail(context) ?: "") }
    var ggntwPassword by remember { mutableStateOf("") }
    var ggntwLoggedIn by remember { mutableStateOf(com.wepwawet.pairing.GgntwApiClient.hasCredentials(context)) }
    var ggntwLoggingIn by remember { mutableStateOf(false) }
    var ggntwError by remember { mutableStateOf<String?>(null) }
    var ggntwSuccessMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Auto-start
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Auto-start proxy", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Start the pairing proxy automatically when WepWawet launches.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(if (autoStart) "Enabled" else "Disabled")
                        Switch(checked = autoStart, onCheckedChange = { autoStart = it })
                    }
                }
            }

            // Steam account
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Steam account", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Sign in to Steam to subscribe to and download workshop items. " +
                            "Your password stays on Steam's servers — WepWawet only " +
                            "stores the resulting session cookies.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (steamLoggedIn)
                            "Logged in as ${steamId ?: "unknown Steam ID"}"
                        else "Not logged in",
                        color = if (steamLoggedIn)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            WepWawetLogger.lifecycle("SettingsScreen: open Steam login")
                            onOpenSteamLogin()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Login, contentDescription = null)
                        Spacer(Modifier.height(0.dp))
                        Text("  ${if (steamLoggedIn) "Re-authenticate" else "Log in to Steam"}")
                    }
                    if (steamLoggedIn) {
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                WepWawetLogger.lifecycle("SettingsScreen: Steam logout")
                                SteamLoginActivity.logout(context)
                                steamLoggedIn = false
                                steamId = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Log out")
                        }
                    }
                }
            }

            // GGNetwork account (required for downloading actual workshop content)
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("GGNetwork account", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Required for downloading ACTUAL Steam Workshop .pkg content " +
                        "(not just preview thumbnails). Create a free account at ggntw.com. " +
                        "WepWawet stores only the access token — your password is not persisted.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (ggntwLoggedIn)
                            "Logged in as ${ggntwEmail.ifBlank { "unknown" }}"
                        else "Not logged in",
                        color = if (ggntwLoggedIn)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (!ggntwLoggedIn) {
                        OutlinedTextField(
                            value = ggntwEmail,
                            onValueChange = { ggntwEmail = it; ggntwError = null; ggntwSuccessMessage = null },
                            label = { Text("Email") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = ggntwPassword,
                            onValueChange = { ggntwPassword = it; ggntwError = null; ggntwSuccessMessage = null },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (ggntwEmail.isBlank() || ggntwPassword.isBlank()) {
                                    ggntwError = "Email and password are required"
                                    return@Button
                                }
                                ggntwLoggingIn = true
                                ggntwError = null
                                ggntwSuccessMessage = null
                                WepWawetLogger.lifecycle("SettingsScreen: GGNetwork login attempt",
                                    "email" to ggntwEmail)
                                ggntwScope.launch {
                                    val client = com.wepwawet.pairing.GgntwApiClient()
                                    val result = client.login(context, ggntwEmail, ggntwPassword)
                                    ggntwLoggingIn = false
                                    when (result) {
                                        is com.wepwawet.pairing.GgntwApiClient.LoginResult.Success -> {
                                            WepWawetLogger.lifecycle("SettingsScreen: GGNetwork login SUCCESS",
                                                "email" to result.email)
                                            ggntwLoggedIn = true
                                            ggntwSuccessMessage = "Logged in as ${result.email}"
                                            ggntwPassword = ""  // clear password from memory
                                        }
                                        is com.wepwawet.pairing.GgntwApiClient.LoginResult.Failure -> {
                                            WepWawetLogger.workshopWarn("SettingsScreen: GGNetwork login failed",
                                                "message" to result.message, "status_code" to result.statusCode)
                                            ggntwError = result.message
                                        }
                                        is com.wepwawet.pairing.GgntwApiClient.LoginResult.NetworkError -> {
                                            WepWawetLogger.workshopWarn("SettingsScreen: GGNetwork network error",
                                                "exception" to result.exception.javaClass.name)
                                            ggntwError = "Network error: ${result.exception.message}"
                                        }
                                    }
                                }
                            },
                            enabled = !ggntwLoggingIn && ggntwEmail.isNotBlank() && ggntwPassword.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (ggntwLoggingIn) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.height(0.dp))
                                Text("  Logging in…")
                            } else {
                                Icon(Icons.Filled.Login, contentDescription = null)
                                Spacer(Modifier.height(0.dp))
                                Text("  Log in to GGNetwork")
                            }
                        }
                    }
                    // Error / success messages
                    ggntwError?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Error: $err",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    ggntwSuccessMessage?.let { msg ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            msg,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    // Logout button if logged in
                    if (ggntwLoggedIn) {
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                WepWawetLogger.lifecycle("SettingsScreen: GGNetwork logout")
                                com.wepwawet.pairing.GgntwApiClient.logout(context)
                                ggntwLoggedIn = false
                                ggntwEmail = ""
                                ggntwPassword = ""
                                ggntwSuccessMessage = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Log out")
                        }
                    }
                }
            }

            // Verbose logging
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Verbose logging", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Capture all protocol details including RSA keys, AES keys, " +
                            "and raw JSON. Check the Logs tab for real-time messages.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(if (verboseLog) "Enabled" else "Disabled")
                        Switch(checked = verboseLog, onCheckedChange = { verboseLog = it })
                    }
                }
            }

            // About
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("About", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("WepWawet — Wallpaper Engine companion")
                    Spacer(Modifier.height(4.dp))
                    Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
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
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Protocol: RSA-1024 OAEP + AES-256-CBC\n" +
                            "Discovery: UDP 7884 multicast\n" +
                            "Pairing: TCP 7889\n" +
                            "mpkg: PKGM0020 (VFS verified)\n" +
                            "Status: ${if (verboseLog) "PKGM0020" else ""}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
