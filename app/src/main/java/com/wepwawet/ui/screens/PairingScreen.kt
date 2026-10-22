package com.wepwawet.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wepwawet.pairing.PairingService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("1234") }
    var running by remember { mutableStateOf(false) }

    // Auto-start proxy on screen appear
    DisposableEffect(Unit) {
        if (!running) {
            running = true
            PairingService.start(context, pin)
        }
        onDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pairing") },
                navigationIcon = {
                    IconButton(onClick = {
                        PairingService.stop(context)
                        running = false
                        onBack()
                    }) {
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
                "Pair your Wallpaper Engine Android app",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                if (running) {
                    "Servers are running on UDP 7884 + TCP 7889.\n\n" +
                    "Open the Wallpaper Engine Android app.\n" +
                    "Either:\n" +
                    "  1. It should auto-discover 'WepWawet' — tap it\n" +
                    "  2. Or enter manual IP: 127.0.0.1\n\n" +
                    "Enter the PIN shown below when prompted."
                } else {
                    "Tap 'Start proxy' to begin.\n\n" +
                    "This starts:\n" +
                    "  • UDP 7884 discovery broadcast\n" +
                    "  • TCP 7889 pairing server\n\n" +
                    "Then open the Wallpaper Engine Android app and pair."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            // PIN display
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (running)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (running) pin else "----",
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (running)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!running) {
                    Button(onClick = {
                        running = true
                        PairingService.start(context, pin)
                    }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start proxy")
                    }
                } else {
                    Button(onClick = {
                        PairingService.stop(context)
                        running = false
                    }) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Stop proxy")
                    }
                }
                OutlinedButton(onClick = {
                    // Stop old service, generate new PIN, start new service
                    PairingService.stop(context)
                    pin = (1000..9999).random().toString()
                    if (running) {
                        PairingService.start(context, pin)
                    }
                }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    Spacer(Modifier.width(8.dp))
                    Text("Refresh PIN")
                }
            }

            Spacer(Modifier.height(24.dp))

            // Status info
            if (running) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Server Status",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("• UDP 7884: Broadcasting WallpaperEngine/Ping")
                        Text("• TCP 7889: Listening for pairing connections")
                        Text("• RSA-1024 keypair generated (OAEP padding)")
                        Text("• AES-256-CBC + RSA/ECB/OAEPPadding")
                        Text("• PIN: $pin")
                        Text("• Discovery: protocolVersion=4 (CONNECTION_PROTOCOL_VERSION)")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Check the Logs tab for real-time protocol messages.\n" +
                            "Use the copy button to copy logs for debugging.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
