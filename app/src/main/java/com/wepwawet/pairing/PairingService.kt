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
import java.io.File

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
 *
 * == Static access from UI (per Task 9) ==
 *
 * The [Companion.pairingServer] field exposes the singleton [WePairingServer]
 * instance for the lifetime of the service. UI screens can call:
 *   - [isPaired] to check if a phone is currently paired
 *   - [queueWallpaper] to push a .mpkg file to the paired phone
 *   - [pairedDeviceName] to display the connected phone's name
 *
 * All three are safe to call from any thread (atomic / volatile reads).
 */
class PairingService : Service() {

    companion object {
        const val CHANNEL_ID = "wepwawet_pairing"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_PIN = "pin"

        // ====== Static UI accessors (per Task 9) ======

        /**
         * Singleton reference to the active [WePairingServer], or `null` if the
         * pairing service isn't running. Set in [onStartCommand], cleared in
         * [onDestroy]. Read by [isPaired] and [queueWallpaper].
         */
        @Volatile
        var pairingServer: WePairingServer? = null
            private set

        /**
         * The name of the currently-paired phone, or `null` if no phone is paired.
         * Updated by the [WePairingServer.onPairingSuccess] / [onPairingFailure]
         * callbacks registered in [onStartCommand].
         */
        @Volatile
        var pairedDeviceName: String? = null
            private set

        /**
         * Quick check whether a phone is currently paired and ready to receive
         * wallpaper transfers.
         *
         * Returns `true` iff [pairingServer] is non-null AND [pairedDeviceName]
         * is non-null. The underlying [WePairingServer] command loop is what
         * actually delivers queued files — this flag just tells the UI whether
         * a transfer will be picked up immediately or will sit in the queue
         * until pairing completes.
         */
        fun isPaired(): Boolean {
            // pairedDeviceName is the authoritative signal — it is set only in the
            // WePairingServer.onPairingSuccess callback (which fires after the AES
            // handshake completes) and cleared in onPairingFailure / stopServers.
            // We deliberately don't reflect into WePairingServer's private
            // currentClient field; the name flag is the source of truth.
            return pairingServer != null && pairedDeviceName != null
        }

        /**
         * Queue a wallpaper .mpkg file for transfer to the paired phone.
         *
         * If a phone is currently paired, the file is sent during the next
         * iteration of the [WePairingServer] command loop (typically immediate).
         * If no phone is paired yet, the file sits in the queue and is sent
         * as soon as pairing completes.
         *
         * @param file the .mpkg file to send. Must be readable. Should be a stable
         *   path (not a temp file) because the queue holds a [File] reference, not
         *   the bytes — the file must still exist when the loop picks it up.
         * @param title human-readable wallpaper title, shown in the WE app's
         *   "Open wallpaper" dialog. Must be ≤ 256 chars.
         */
        fun queueWallpaper(file: File, title: String) {
            val server = pairingServer
            if (server == null) {
                WepWawetLogger.proxyWarn("queueWallpaper called but pairing server not running",
                    "file" to file.name, "title" to title)
                return
            }
            if (!file.exists() || !file.canRead()) {
                WepWawetLogger.proxyWarn("queueWallpaper file not readable",
                    "path" to file.absolutePath,
                    "exists" to file.exists(),
                    "readable" to file.canRead())
                return
            }
            WepWawetLogger.proxy("queueWallpaper",
                "file" to file.name,
                "title" to title,
                "size" to file.length(),
                "paired" to isPaired())
            server.sendWallpaper(file, title)
        }

        // ====== Service start/stop helpers (existing) ======

        fun start(context: Context, pin: String) {
            val intent = Intent(context, PairingService::class.java)
            intent.putExtra(EXTRA_PIN, pin)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PairingService::class.java))
        }
    }

    private var pairingServerLocal: WePairingServer? = null
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
            pairingServerLocal = WePairingServer().also { server ->
                server.onPairingSuccess = { deviceName ->
                    // Update the static pairedDeviceName so UI can query isPaired()
                    pairedDeviceName = deviceName
                    WepWawetLogger.proxy("Pairing SUCCESS!",
                        "device" to deviceName,
                        "queued_wallpapers" to "n/a")
                    updateNotification("Paired: $deviceName")
                }
                server.onPairingFailure = { error ->
                    pairedDeviceName = null
                    WepWawetLogger.proxyError("Pairing FAILED", "error" to error)
                }
                server.start(pin)
                // Expose via companion
                pairingServer = server
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
            pairingServerLocal?.stop()
        } catch (e: Exception) {
            WepWawetLogger.proxyError("Error stopping pairing server",
                "exception" to e.javaClass.name)
        }
        pairingServerLocal = null
        pairingServer = null
        pairedDeviceName = null

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
