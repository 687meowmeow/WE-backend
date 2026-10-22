package com.wepwawet.wine

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
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
 * WineProcessService — owns the lifecycle of the emulated Steam + Wallpaper Engine
 * running under Wine + Box64.
 *
 * This service is a *replacement* for Winlator's [com.winlator.services.ForegroundService]
 * — we reuse the same architectural role (WakeLock, screen-on/off receiver, ProcessLifecycle
 * observer) but specialize it for our use case (running a single Wine prefix that hosts
 * Steam + WE, with the user's official WE Android app consuming via our PairingProxyService).
 *
 * Lifecycle states:
 *   - STOPPED:    No Wine process running
 *   - STARTING:   Wine prefix extracted, rootfs mounted, Steam client starting
 *   - RUNNING:    Steam client logged in, WE subscribed, ready for -control commands
 *   - BUSY:       Currently downloading or rendering a wallpaper (sub-state of RUNNING)
 *   - ERROR:      Wine process died or failed to start; needs retry or user intervention
 *
 * The service is foregrounded whenever the Wine process is alive. When the user kills
 * WepWawet (back-press to exit), the service stays alive for 5 minutes so the user can
 * switch back if they didn't mean to exit; after that the service self-stops.
 *
 * Inputs:
 *   - ACTION_START: start the Wine process if not running
 *   - ACTION_STOP:  gracefully stop Wine (wineboot -k)
 *   - ACTION_INVOKE_CONTROL: invoke wallpaper32.exe -control <args>
 *   - ACTION_DOWNLOAD_WORKSHOP_ITEM: tell Steam to subscribe to <publishedfileid>
 *
 * !! Major code reused (will be) from Winlator !!
 *   The actual Wine process spawning, Box64 invocation, rootfs mounting, Turnip loading,
 *   and prefix extraction are all done by Winlator's existing classes. This service is a
 *   thin orchestration layer that calls into (a fork of) Winlator's `xenvironment`,
 *   `container`, and `box64` packages.
 *
 *   Until we have that fork vendored into this repo (TODO), this service provides:
 *     - The orchestration skeleton (lifecycle, state, logging, notifications)
 *     - Stubbed Box64Runner/WinePrefixManager calls (each logs "TODO" and returns
 *       a placeholder state)
 *
 *   Once Winlator is vendored, the stubs become real calls into the Winlator codebase.
 */
class WineProcessService : LifecycleService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    private var box64Runner: Box64Runner? = null
    private var prefixManager: WinePrefixManager? = null
    private var weControlClient: WeControlClient? = null
    private var steamObserver: SteamLifecycleObserver? = null

    private val _state = MutableStateFlow<WineState>(WineState.Stopped)
    val state: StateFlow<WineState> = _state.asStateFlow()

    inner class WineBinder : Binder() {
        val service: WineProcessService get() = this@WineProcessService
    }
    private val binder = WineBinder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        WepWawetLogger.lifecycle("WineProcessService.onCreate")
        startForeground(NOTIFICATION_ID, buildNotification(
            getString(R.string.notif_wine_title_stopped),
            getString(R.string.notif_wine_text_idle),
        ))
        box64Runner = Box64Runner(this)
        prefixManager = WinePrefixManager(this)
        weControlClient = WeControlClient(this)
        steamObserver = SteamLifecycleObserver(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startWine()
            ACTION_STOP -> stopWine()
            ACTION_INVOKE_CONTROL -> {
                val args = intent.getStringArrayExtra(EXTRA_CONTROL_ARGS) ?: return@START_STICKY
                serviceScope.launch {
                    weControlClient?.invoke(*args)
                }
            }
            ACTION_DOWNLOAD_WORKSHOP_ITEM -> {
                val publishedFileId = intent.getStringExtra(EXTRA_PUBLISHED_FILE_ID)
                    ?: return@START_STICKY
                serviceScope.launch {
                    downloadWorkshopItem(publishedFileId)
                }
            }
        }
        return START_STICKY
    }

    private fun startWine() {
        if (_state.value is WineState.Starting || _state.value is WineState.Running) {
            WepWawetLogger.wineWarn("startWine called but already starting/running")
            return
        }
        _state.value = WineState.Starting
        updateNotification(getString(R.string.notif_wine_title_starting), "")

        serviceScope.launch {
            try {
                // 1. Extract the Wine prefix if not already done (idempotent)
                prefixManager?.ensurePrefixExtracted()

                // 2. Ensure rootfs is mounted (chroot when rooted, PRoot otherwise)
                prefixManager?.ensureRootfsMounted()

                // 3. Pre-flight: check Steam + WE are installed in the prefix
                val steamInstalled = prefixManager?.isSteamInstalled() ?: false
                if (!steamInstalled) {
                    _state.value = WineState.Error("Steam not installed in prefix — see Settings → Steam account")
                    updateNotification(getString(R.string.notif_wine_title_stopped), "Steam missing")
                    return@launch
                }
                val weInstalled = prefixManager?.isWallpaperEngineInstalled() ?: false
                if (!weInstalled) {
                    _state.value = WineState.Error("Wallpaper Engine not installed in prefix — subscribe via Workshop")
                    updateNotification(getString(R.string.notif_wine_title_stopped), "WE missing")
                    return@launch
                }

                // 4. Start Steam in headless mode (background, no UI)
                box64Runner?.launchSteam()

                // 5. Wait for Steam to be ready (polls Steam's IPC socket in prefix)
                steamObserver?.awaitSteamReady()

                // 6. Launch WE in headless mode (so it's running but no visible window)
                box64Runner?.launchWallpaperEngineHeadless()

                _state.value = WineState.Running
                updateNotification(getString(R.string.notif_wine_title_running),
                    getString(R.string.notif_wine_text_idle))

                WepWawetLogger.wine("Wine stack started")
            } catch (e: Exception) {
                _state.value = WineState.Error(e.message ?: "Unknown error")
                updateNotification(getString(R.string.notif_wine_title_stopped),
                    getString(R.string.notif_wine_text_error, e.message ?: "Unknown"))
                WepWawetLogger.wineError("startWine failed", "exception" to e.javaClass.name, exception = e)
            }
        }
    }

    private fun stopWine() {
        WepWawetLogger.wine("stopWine: gracefully terminating Wine processes")
        serviceScope.launch {
            try {
                weControlClient?.invoke("stop")  // WE: stop rendering
                box64Runner?.shutdown()
                prefixManager?.unmountRootfs()
                _state.value = WineState.Stopped
                updateNotification(getString(R.string.notif_wine_title_stopped),
                    getString(R.string.notif_wine_text_idle))
                stopForeground(STOP_FOREGROUND_DETACH)
            } catch (e: Exception) {
                WepWawetLogger.wineError("stopWine failed", "exception" to e.javaClass.name, exception = e)
            }
        }
    }

    private suspend fun downloadWorkshopItem(publishedFileId: String) {
        WepWawetLogger.wine("downloadWorkshopItem", "id" to publishedFileId)
        if (_state.value !is WineState.Running) {
            WepWawetLogger.wineWarn("downloadWorkshopItem: Wine not running — starting first")
            startWine()
            // Wait briefly for state to settle
            // TODO: better — await state transition
        }
        updateNotification(getString(R.string.notif_wine_title_running),
            getString(R.string.notif_wine_text_downloading, publishedFileId))
        try {
            // Steam's URL handler: steam://subscribe/<appid>/<workshopid>
            // For workshop items, it's: steam://rungameid/ + workshop subscription via Steam API
            // The most reliable way is via the Steam console command:
            //   steam.sh +workshop_download_item 431960 <id>
            // Or via WE's own "Subscribe" IPC (undocumented).
            box64Runner?.invokeSteamCmd("workshop_download_item", "431960", publishedFileId)
            WepWawetLogger.wine("downloadWorkshopItem complete", "id" to publishedFileId)
            updateNotification(getString(R.string.notif_wine_title_running),
                getString(R.string.notif_wine_text_idle))
        } catch (e: Exception) {
            WepWawetLogger.wineError("downloadWorkshopItem failed",
                "id" to publishedFileId, "exception" to e.javaClass.name, exception = e)
            updateNotification(getString(R.string.notif_wine_title_running),
                getString(R.string.notif_wine_text_error, e.message ?: "Unknown"))
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pi = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, WepWawetApplication.CHANNEL_WINE)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun updateNotification(title: String, text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    override fun onDestroy() {
        WepWawetLogger.lifecycle("WineProcessService.onDestroy")
        runCatching { box64Runner?.shutdown() }
        runCatching { prefixManager?.unmountRootfs() }
        serviceScope.cancel()
        super.onDestroy()
    }

    sealed class WineState {
        data object Stopped : WineState()
        data object Starting : WineState()
        data object Running : WineState()
        data class Busy(val operation: String) : WineState()
        data class Error(val message: String) : WineState()
    }

    companion object {
        const val NOTIFICATION_ID = 0xBEEF
        const val ACTION_START = "com.wepwawet.wine.START"
        const val ACTION_STOP = "com.wepwawet.wine.STOP"
        const val ACTION_INVOKE_CONTROL = "com.wepwawet.wine.INVOKE_CONTROL"
        const val ACTION_DOWNLOAD_WORKSHOP_ITEM = "com.wepwawet.wine.DOWNLOAD_WORKSHOP_ITEM"
        const val EXTRA_CONTROL_ARGS = "control_args"
        const val EXTRA_PUBLISHED_FILE_ID = "published_file_id"

        fun start(context: Context) {
            val intent = Intent(context, WineProcessService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }
        fun stop(context: Context) {
            val intent = Intent(context, WineProcessService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
