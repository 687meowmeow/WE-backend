package com.wepwawet

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.Configuration
import com.wepwawet.log.CrashHandler
import com.wepwawet.log.WepWawetLogger

/**
 * WepWawet Application entry point.
 *
 * Responsibilities:
 *  - Initialize the global logger and the native-crash handler BEFORE any other code runs
 *    (the user explicitly asked for "extensive debugging and logging" — this is non-negotiable)
 *  - Create the two foreground service notification channels:
 *      - PROXY_CHANNEL  — for PairingProxyService status (idle/paired/streaming)
 *      - WINE_CHANNEL   — for WineProcessService status (starting/running/error)
 *  - Provide a WorkManager Configuration that uses our explicit min-shred-logging
 *    initializer (we don't want WorkManager's default to spam logcat)
 */
class WepWawetApplication : Application(), Configuration.Provider {

    override fun onCreate() {
        // Install crash handlers FIRST so we never miss a crash from the rest of onCreate
        CrashHandler.install(this)
        super.onCreate()

        // Boot the logger
        WepWawetLogger.init(this)

        // Notification channels for foreground services
        createNotificationChannels()

        WepWawetLogger.appInfo("WepWawetApplication.onCreate",
            "app_version" to BuildConfig.VERSION_NAME,
            "git_sha" to BuildConfig.GIT_SHA,
            "build_time" to BuildConfig.BUILD_TIME,
            "device_model" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "android_version" to Build.VERSION.SDK_INT.toString(),
            "abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROXY,
                getString(R.string.notif_proxy_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_proxy_channel_desc)
                setShowBadge(false)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WINE,
                getString(R.string.notif_wine_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_wine_channel_desc)
                setShowBadge(false)
            }
        )
    }

    /**
     * WorkManager Configuration — use our own CoroutineWorker initializer that logs via WepWawetLogger
     * instead of the default (which logs via android.Log with its own tag scheme).
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    companion object {
        const val CHANNEL_PROXY = "wepwawet.proxy"
        const val CHANNEL_WINE = "wepwawet.wine"
    }
}
