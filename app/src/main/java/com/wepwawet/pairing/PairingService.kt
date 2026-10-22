package com.wepwawet.pairing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wepwawet.log.WepWawetLogger

/**
 * PairingService — foreground service that runs the WE pairing servers.
 *
 * Starts:
 * - WeDiscoveryBroadcaster (UDP 7884 — WallpaperEngine/Ping broadcast)
 * - WePairingServer (TCP 7889 — RSA + AES pairing handshake)
 *
 * The service runs as a foreground service with a sticky notification so
 * Android doesn't kill it when the user switches to the WE Android app.
 *
 * Usage from PairingScreen:
 *   val intent = Intent(context, PairingService::class.java)
 *   intent.putExtra("pin", pin)
 *   ContextCompat.startForegroundService(context, intent)
 *   // To stop:
 *   context.stopService(Intent(context, PairingService::class.java))
 */
class PairingService : Service() {

    companion object {
        const val CHANNEL_ID = "wepwawet_pairing"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_PIN = "pin"

        fun start(context: Context, pin: String) {
            val intent = Intent(context, PairingService::class.java)
            intent.putExtra(EXTRA_PIN, pin)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PairingService::class.java))
        }
    }

    private var pairingServer: WePairingServer? = null
    private var discoveryBroadcaster: WeDiscoveryBroadcaster? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        WepWawetLogger.proxy("PairingService.onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        WepWawetLogger.proxy("PairingService.onStartCommand", "flags" to flags)

        // Start as foreground service immediately (Android 12+ requirement)
        val notification = createNotification("WepWawet pairing proxy running")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Stop any existing servers
        stopServers()

        // Get PIN from intent
        val pin = intent?.getStringExtra(EXTRA_PIN) ?: "1234"
        WepWawetLogger.proxy("Starting pairing servers", "pin" to pin)

        // Start the pairing server (TCP 7889)
        try {
            pairingServer = WePairingServer().also { server ->
                server.onPairingSuccess = { deviceName ->
                    WepWawetLogger.proxy("Pairing SUCCESS!", "device" to deviceName)
                    updateNotification("Paired: $deviceName")
                }
                server.onPairingFailure = { error ->
                    WepWawetLogger.proxyError("Pairing FAILED", "error" to error)
                }
                server.start(pin)
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyError("Failed to start pairing server",
                "exception" to e.javaClass.name, "message" to (e.message ?: ""))
        }

        // Start the discovery broadcaster (UDP 7884)
        try {
            discoveryBroadcaster = WeDiscoveryBroadcaster(this).also { broadcaster ->
                broadcaster.start()
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyError("Failed to start discovery broadcaster",
                "exception" to e.javaClass.name, "message" to (e.message ?: ""))
        }

        return START_STICKY
    }

    override fun onDestroy() {
        WepWawetLogger.proxy("PairingService.onDestroy")
        stopServers()
        super.onDestroy()
    }

    private fun stopServers() {
        try {
            pairingServer?.stop()
        } catch (e: Exception) {
            WepWawetLogger.proxyError("Error stopping pairing server",
                "exception" to e.javaClass.name)
        }
        pairingServer = null

        try {
            discoveryBroadcaster?.stop()
        } catch (e: Exception) {
            WepWawetLogger.proxyError("Error stopping discovery broadcaster",
                "exception" to e.javaClass.name)
        }
        discoveryBroadcaster = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "WepWawet Pairing",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Pairing proxy service for Wallpaper Engine"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WepWawet")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
