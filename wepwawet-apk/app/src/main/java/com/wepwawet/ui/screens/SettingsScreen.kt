package com.wepwawet.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wepwawet.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var rootMode by remember { mutableStateOf(true) }
    var autoStart by remember { mutableStateOf(true) }
    var verboseLog by remember { mutableStateOf(false) }

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
            // Steam account
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Steam account", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Not logged in",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Log in to Steam")
                        Text("→", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // Root mode
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Root mode", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Use chroot instead of PRoot for ~15% faster Wine launches. " +
                            "Requires a rooted phone (you have one).",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(if (rootMode) "Enabled" else "Disabled")
                        Switch(checked = rootMode, onCheckedChange = { rootMode = it })
                    }
                }
            }

            // Auto-start
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Auto-start proxy", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Start the pairing proxy when WepWawet launches.",
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

            // Verbose logging
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Verbose logging", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Capture TRACE and DEBUG log levels. Disables when battery low.",
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

            // Reset Wine prefix
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Reset Wine prefix", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Wipes the Wine prefix. Steam + WE will need to be reinstalled. " +
                            "Use only if the prefix is corrupted.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // About
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("About", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
                    Text("Git: ${BuildConfig.GIT_SHA}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall)
                    Text("Built: ${BuildConfig.BUILD_TIME}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
