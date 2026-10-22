package com.wepwawet.proxy

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.wepwawet.MainActivity
import com.wepwawet.R
import com.wepwawet.WepWawetApplication
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * PairingProxyService — the heart of WepWawet.
 *
 * This is a [LifecycleService] that runs as a foreground service and owns:
 *   - [WeDiscoveryServer] — UDP 7884 listener, replies to WE Android app's
 *     `WallpaperEngine/Ping:NNN:host:N:iface` discovery broadcasts.
 *   - [WeControlServer] — TCP 7889 listener, implements the pairing handshake
 *     (4-digit PIN + PIN-derived key) + control protocol + .mpkg transfer.
 *
 * Why native Android (not emulated Wine):
 *   Per research Task 1-B, Wine's `bcrypt` library has a known gap that breaks WE's
 *   pairing handshake. The 2022 community report from WE's developer "Biohazard"
 *   flagged it, and Wine 9-10 has improved but it's unverified whether the specific
 *   primitive WE uses is now covered. Rather than betting on Wine catching up, we
 *   implement the protocol natively in Kotlin. The emulated WE inside Wine is invoked
 *   ONLY for `wallpaper32.exe -control` (which doesn't involve bcrypt).
 *
 * Lifecycle:
 *   - The user starts this service when they want to pair (via the PairingScreen "Start"
 *     button) or automatically when WepWawet launches if "auto-start" is on (default).
 *   - The service stays in the foreground with a sticky notification until the user
 *     explicitly stops it OR the system kills it under memory pressure.
 *   - Wake lock is held only during active streaming (saves battery otherwise).
 *
 * Pairing flow:
 *   1. Service starts → spawns [WeDiscoveryServer] + [WeControlServer]
 *   2. DiscoveryServer broadcasts `WallpaperEngine/Ping:<pid>:wepwawet:1:lo` on
 *      127.0.0.1 loopback AND on the Wi-Fi subnet (for the manual LAN-pair fallback).
 *   3. The official WE Android app, when the user enters the manual IP `127.0.0.1`
 *      and the 4-digit PIN shown in our UI, sends a pairing handshake to TCP 7889.
 *   4. [WeControlServer] accepts the connection, validates the PIN-derived key,
 *      stores the pairing token, marks the device as "paired".
 *   5. Subsequent control commands ("switch wallpaper", "pause", "next") are
 *      dispatched to [com.wepwawet.wine.WeControlClient] which invokes
 *      `wallpaper32.exe -control <cmd>` in the Wine prefix.
 *   6. When a new .mpkg appears in the outbox (set by [com.wepwawet.wine.WineProcessService]),
 *      the [MpkgTransporter] pushes it to the paired device over TCP 7889.
 *
 * Open work:
 *   - The exact TCP 7889 wire format is unverified (need Wireshark + jadx decompile).
 *   - The PIN-derived key algorithm is hypothesized as PBKDF2-HMAC-SHA256 -> AES-256-GCM
 *     but needs confirmation from the WE Android APK decompile.
 *   - The .mpkg container format is proprietary and undocumented; for v1 we let emulated
 *     WE produce .mpkg and our proxy just serves the bytes.
 */
class PairingProxyService : LifecycleService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    private var discoveryServer: WeDiscoveryServer? = null
    private var controlServer: WeControlServer? = null
    private var mpkgTransporter: MpkgTransporter? = null

    private val _state = MutableStateFlow<ProxyState>(ProxyState.Stopped)
    val state: StateFlow<ProxyState> = _state.asStateFlow()

    private val _pairingPin = MutableStateFlow<String?>(null)
    val pairingPin: StateFlow<String?> = _pairingPin.asStateFlow()

    // Local binder for the UI to bind to this service
    inner class ProxyBinder : Binder() {
        val service: PairingProxyService get() = this@PairingProxyService
    }
    private val binder = ProxyBinder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        WepWawetLogger.lifecycle("PairingProxyService.onCreate")
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_proxy_text_idle)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startProxy()
            ACTION_STOP -> { stopProxy(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            ACTION_REFRESH_PIN -> refreshPin()
            ACTION_PUSH_MPKG -> {
                val mpkgPath = intent.getStringExtra(EXTRA_MPKG_PATH) ?: return@START_STICKY
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                serviceScope.launch { mpkgTransporter?.enqueue(mpkgPath, deviceId) }
            }
        }
        return START_STICKY
    }

    private fun startProxy() {
        if (_state.value is ProxyState.Starting || _state.value is ProxyState.Running) {
            WepWawetLogger.proxyWarn("startProxy called but already starting/running")
            return
        }
        _state.value = ProxyState.Starting
        WepWawetLogger.proxy("PairingProxyService.startProxy", "pid" to android.os.Process.myPid())

        try {
            // Generate a fresh 4-digit PIN
            refreshPin()

            // Acquire a partial wake lock so the broadcast receiver doesn't sleep mid-handshake
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "wepwawet:proxy",
            ).apply { setReferenceCounted(false); acquire(WAKE_LOCK_TIMEOUT_MS) }

            // Start discovery server (UDP 7884)
            discoveryServer = WeDiscoveryServer(this).also { it.start() }

            // Start control server (TCP 7889)
            controlServer = WeControlServer(this).also { it.start() }

            // Start mpkg transporter
            mpkgTransporter = MpkgTransporter(this).also { it.start() }

            _state.value = ProxyState.Running
            updateNotification(getString(R.string.notif_proxy_text_idle))
            WepWawetLogger.proxy("startProxy complete", "udp" to WeDiscoveryServer.PORT, "tcp" to WeControlServer.PORT)
        } catch (e: Exception) {
            _state.value = ProxyState.Error(e.message ?: "Unknown error")
            WepWawetLogger.proxyError("startProxy failed", "exception" to e.javaClass.name, exception = e)
        }
    }

    private fun stopProxy() {
        WepWawetLogger.proxy("PairingProxyService.stopProxy")
        runCatching { discoveryServer?.stop() }
        runCatching { controlServer?.stop() }
        runCatching { mpkgTransporter?.stop() }
        runCatching { wakeLock?.release() }
        discoveryServer = null
        controlServer = null
        mpkgTransporter = null
        _state.value = ProxyState.Stopped
        _pairingPin.value = null
    }

    fun refreshPin() {
        val pin = (1000..9999).random().toString()
        _pairingPin.value = pin
        WepWawetLogger.proxy("refreshPin", "pin_len" to pin.length)
    }

    private fun buildNotification(text: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pi = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, WepWawetApplication.CHANNEL_PROXY)
            .setContentTitle(getString(R.string.notif_proxy_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        WepWawetLogger.lifecycle("PairingProxyService.onDestroy")
        stopProxy()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ====== State ======

    sealed class ProxyState {
        data object Stopped : ProxyState()
        data object Starting : ProxyState()
        data object Running : ProxyState()
        data class Error(val message: String) : ProxyState()
    }

    companion object {
        const val NOTIFICATION_ID = 0xCAFE
        const val ACTION_START = "com.wepwawet.proxy.START"
        const val ACTION_STOP = "com.wepwawet.proxy.STOP"
        const val ACTION_REFRESH_PIN = "com.wepwawet.proxy.REFRESH_PIN"
        const val ACTION_PUSH_MPKG = "com.wepwawet.proxy.PUSH_MPKG"
        const val EXTRA_MPKG_PATH = "mpkg_path"
        const val EXTRA_DEVICE_ID = "device_id"
        const val WAKE_LOCK_TIMEOUT_MS = 60_000L * 5  // 5 min — enough for a pairing session

        fun start(context: Context) {
            val intent = Intent(context, PairingProxyService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }
        fun stop(context: Context) {
            val intent = Intent(context, PairingProxyService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
