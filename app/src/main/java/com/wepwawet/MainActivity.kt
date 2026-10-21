package com.wepwawet

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
import com.wepwawet.ui.WepWawetNavHost
import com.wepwawet.ui.theme.WepWawetTheme

/**
 * The single Activity for the app. All screens live inside a Compose NavHost.
 *
 * We do NOT use the navigation-compose singular routes per-screen pattern — the WE Android
 * pairing flow has many entry points (deep-link from notification, deep-link from WE app,
 * deep-link from Workshop browser), so we use a single NavHost with route arguments.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WepWawetLogger.lifecycle("MainActivity.onCreate", "intent" to (intent?.dataString ?: "null"))

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
