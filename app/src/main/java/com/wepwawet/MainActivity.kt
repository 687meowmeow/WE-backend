package com.wepwawet

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.pairing.PairingService
import com.wepwawet.ui.WepWawetNavHost
import com.wepwawet.ui.theme.WepWawetTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WepWawetLogger.lifecycle("MainActivity.onCreate", "intent" to (intent?.dataString ?: "null"))

        // Auto-start the pairing proxy on app launch
        try {
            PairingService.start(this, "1234")
            WepWawetLogger.lifecycle("Auto-started pairing proxy")
        } catch (e: Exception) {
            WepWawetLogger.lifecycle("Failed to auto-start proxy: ${e.message}")
        }

        enableEdgeToEdge()
        setContent {
            WepWawetTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    WepWawetNavHost()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WepWawetLogger.lifecycle("MainActivity.onResume")
    }

    override fun onPause() {
        super.onPause()
        WepWawetLogger.lifecycle("MainActivity.onPause")
    }

    override fun onDestroy() {
        WepWawetLogger.lifecycle("MainActivity.onDestroy")
        super.onDestroy()
    }
}
