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

    // Steam credentials (for JavaSteam direct download — no PC needed)
    var steamUsername by remember { mutableStateOf(com.wepwawet.pairing.SteamCredentialsStore.getUsername(context) ?: "") }
    var steamPassword by remember { mutableStateOf("") }
    var steamGuardCode by remember { mutableStateOf("") }

    // Backend URL state (for the self-hosted workshop content backend)
    var backendUrl by remember { mutableStateOf(com.wepwawet.pairing.WorkshopBackendClient.getBackendUrl(context) ?: "") }
    var backendUrlEdited by remember { mutableStateOf(backendUrl) }
    var backendHealthStatus by remember { mutableStateOf<String?>(null) }
    var backendChecking by remember { mutableStateOf(false) }
    val backendScope = rememberCoroutineScope()


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

            // Steam account (for JavaSteam direct download — no PC needed)
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Steam account", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Enter your Steam username and password to download workshop " +
                        "content directly on your phone via JavaSteam (no PC needed). " +
                        "Your Steam account must own Wallpaper Engine. Credentials are " +
                        "stored only on this device.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    // Show saved status
                    val hasCreds = com.wepwawet.pairing.SteamCredentialsStore.hasCredentials(context)
                    Text(
                        if (hasCreds) {
                            "Credentials saved for: ${com.wepwawet.pairing.SteamCredentialsStore.getUsername(context) ?: "unknown"}"
                        } else "No Steam credentials saved",
                        color = if (hasCreds)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = steamUsername,
                        onValueChange = { steamUsername = it },
                        label = { Text("Steam username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = steamPassword,
                        onValueChange = { steamPassword = it },
                        label = { Text("Steam password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = steamGuardCode,
                        onValueChange = { steamGuardCode = it },
                        label = { Text("Steam Guard code (2FA — optional, only needed first time)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "If your Steam account has 2FA (Steam Guard Mobile Authenticator), " +
                        "enter a fresh 2FA code here. It's only needed for the first download — " +
                        "after that, a refresh token is saved for automatic future logins. " +
                        "Get a fresh code from your Steam mobile app → Steam Guard → View code.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (steamUsername.isBlank() || steamPassword.isBlank()) return@Button
                            com.wepwawet.pairing.SteamCredentialsStore.saveCredentials(
                                context, steamUsername, steamPassword)
                            if (steamGuardCode.isNotBlank()) {
                                com.wepwawet.pairing.SteamCredentialsStore.saveGuardCode(
                                    context, steamGuardCode)
                            }
                            WepWawetLogger.lifecycle("SettingsScreen: Steam credentials saved",
                                "username" to steamUsername,
                                "has_guard" to steamGuardCode.isNotBlank())
                            steamPassword = ""
                            steamGuardCode = ""
                        },
                        enabled = steamUsername.isNotBlank() && steamPassword.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Save Steam credentials")
                    }
                    Spacer(Modifier.height(8.dp))
                    // Test Login button
                    val testScope = rememberCoroutineScope()
                    var testLoginResult by remember { mutableStateOf<String?>(null) }
                    var testLoginInProgress by remember { mutableStateOf(false) }
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            if (!com.wepwawet.pairing.SteamCredentialsStore.hasCredentials(context)) {
                                testLoginResult = "Save credentials first"
                                return@OutlinedButton
                            }
                            testLoginInProgress = true
                            testLoginResult = null
                            WepWawetLogger.lifecycle("SettingsScreen: testing Steam login...")
                            testScope.launch {
                                val result = com.wepwawet.pairing.WorkshopDownloader.testLogin(context)
                                testLoginInProgress = false
                                testLoginResult = result
                                WepWawetLogger.lifecycle("SettingsScreen: test login result",
                                    "result" to result)
                            }
                        },
                        enabled = !testLoginInProgress && com.wepwawet.pairing.SteamCredentialsStore.hasCredentials(context),
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
                    if (hasCreds) {
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                com.wepwawet.pairing.SteamCredentialsStore.clearCredentials(context)
                                steamUsername = ""
                                steamPassword = ""
                                WepWawetLogger.lifecycle("SettingsScreen: Steam credentials cleared")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Clear saved credentials")
                        }
                    }
                }
            }

            // Backend URL (self-hosted workshop content downloader)
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Workshop content backend", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "URL of your self-hosted SteamCMD backend that downloads real " +
                        "Steam Workshop .pkg files. Deploy the backend on your PC or a " +
                        "VPS — see the backend/README.md in the WepWawet GitHub repo for " +
                        "Docker Compose deploy instructions (one command).",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = backendUrlEdited,
                        onValueChange = {
                            backendUrlEdited = it
                            backendHealthStatus = null
                        },
                        label = { Text("Backend URL") },
                        placeholder = { Text("http://192.168.1.100:8080") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                val url = backendUrlEdited.trim()
                                if (url.isBlank()) {
                                    backendHealthStatus = "URL is empty"
                                    return@Button
                                }
                                com.wepwawet.pairing.WorkshopBackendClient.setBackendUrl(context, url)
                                backendUrl = url
                                backendHealthStatus = null
                                backendChecking = true
                                WepWawetLogger.lifecycle("SettingsScreen: save+test backend URL",
                                    "url" to url)
                                backendScope.launch {
                                    val client = com.wepwawet.pairing.WorkshopBackendClient()
                                    val health = client.checkHealth(context)
                                    backendChecking = false
                                    backendHealthStatus = if (health != null) {
                                        "Backend reachable ✓"
                                    } else {
                                        "Cannot reach backend at $url — is it running?"
                                    }
                                }
                            },
                            enabled = !backendChecking,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (backendChecking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text("Save & Test")
                            }
                        }
                        if (backendUrl.isNotEmpty()) {
                            androidx.compose.material3.OutlinedButton(
                                onClick = {
                                    com.wepwawet.pairing.WorkshopBackendClient.clearBackendUrl(context)
                                    backendUrl = ""
                                    backendUrlEdited = ""
                                    backendHealthStatus = null
                                    WepWawetLogger.lifecycle("SettingsScreen: cleared backend URL")
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Clear")
                            }
                        }
                    }
                    backendHealthStatus?.let { status ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            status,
                            color = if (status.contains("✓"))
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
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
