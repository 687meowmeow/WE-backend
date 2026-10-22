package com.wepwawet

import android.os.Bundle
import android.util.Log
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
        // Log to logcat FIRST — even if WepWawetLogger.init failed in Application.onCreate,
        // we still want a breadcrumb in logcat.
        Log.i(TAG, "MainActivity.onCreate intent=${intent?.dataString ?: "null"}")

        // Best-effort log to WepWawetLogger if it initialized. If it didn't, this
        // call no-ops (initialized=false guard inside the logger).
        runCatching {
            WepWawetLogger.lifecycle("MainActivity.onCreate",
                "intent" to (intent?.dataString ?: "null"))
        }

        // Auto-start the pairing proxy on app launch.
        // Wrapped in try-catch that catches Throwable (not just Exception) so
        // class-loading Errors like NoClassDefFoundError don't bring down the
        // app before we get a chance to render the UI.
        runCatching {
            PairingService.start(this, "1234")
            WepWawetLogger.lifecycle("Auto-started pairing proxy")
        }.onFailure { e ->
            // Log to BOTH WepWawetLogger and logcat — if the logger failed to init,
            // we still get the failure in logcat
            runCatching {
                WepWawetLogger.lifecycle("Failed to auto-start proxy: ${e.message}")
            }
            Log.e(TAG, "PairingService.start failed", e)
        }

        // Render the UI. Wrapped in try-catch so if Compose crashes during
        // first composition, we still surface the error via logcat and the
        // CrashHandler instead of dying silently.
        runCatching {
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
        }.onFailure { e ->
            runCatching {
                WepWawetLogger.appError("setContent failed in MainActivity.onCreate",
                    exception = e)
            }
            Log.e(TAG, "setContent failed", e)
            // Don't rethrow — let the activity continue in a broken state so the
            // user at least sees a black screen instead of an immediate crash
            // dialog. The CrashHandler has already captured the exception via
            // the runCatching block above (it logs via WepWawetLogger.crash
            // through appError, which goes into the ring buffer + logcat).
        }
    }

    override fun onResume() {
        super.onResume()
        runCatching { WepWawetLogger.lifecycle("MainActivity.onResume") }
    }

    override fun onPause() {
        super.onPause()
        runCatching { WepWawetLogger.lifecycle("MainActivity.onPause") }
    }

    override fun onDestroy() {
        runCatching { WepWawetLogger.lifecycle("MainActivity.onDestroy") }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WepWawet-Main"
    }
}
