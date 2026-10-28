package com.wepwawet.pairing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.wepwawet.log.WepWawetLogger

/**
 * BootReceiver — auto-restart the pairing foreground service after boot
 * or after an app update.
 *
 * v0.7.1 (round 6 — pairing persistence):
 *
 * PROBLEM: previously the [PairingService] foreground service was only
 * started from MainActivity.onCreate and from the PairingScreen. After a
 * device reboot OR after an app update (Play Store or sideload), the
 * service wasn't running until the user manually opened the app. So
 * the phone couldn't pair with the computer until the user re-opened
 * WepWawet.
 *
 * FIX: this BroadcastReceiver listens for:
 *   - [Intent.ACTION_BOOT_COMPLETED] — fired once after the device
 *     finishes booting.
 *   - [Intent.ACTION_MY_PACKAGE_REPLACED] — fired after the app is
 *     updated to a new version (Play Store auto-update or sideload).
 *
 * When either broadcast fires, we set [PairingService.appContext] (so
 * the static UI accessors work even before the user opens the app) and
 * call [PairingService.start] which calls
 * `Context.startForegroundService(Intent(...))`.
 *
 * Android explicitly EXEMPTS BOOT_COMPLETED and MY_PACKAGE_REPLACED from
 * the Android 12+ background-foreground-service-start restriction —
 * these two broadcasts are allowed to start foreground services.
 * (Verified: our foreground service type is `specialUse`, which is NOT
 * in the Android 15+ restricted-types list (dataSync, camera,
 * mediaPlayback, phoneCall, microphone). So BOOT_COMPLETED can start
 * our service on Android 15+ too.)
 *
 * The receiver also wraps the start in a try/catch — on Android 12+,
 * if the system has decided we're not allowed to start the foreground
 * service for any reason (e.g. the user has revoked foreground service
 * privileges), we log instead of crashing. Android will retry the
 * service start when the user opens the app (which puts us in the
 * foreground and lets startForeground succeed).
 *
 * Manifest declaration:
 *   <receiver
 *       android:name=".pairing.BootReceiver"
 *       android:exported="true">
 *       <intent-filter>
 *           <action android:name="android.intent.action.BOOT_COMPLETED" />
 *           <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
 *       </intent-filter>
 *   </receiver>
 *
 * Permission required (declared in manifest):
 *   <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        WepWawetLogger.proxy("$TAG: broadcast received",
            "action" to (action ?: "null"),
            "android_version" to Build.VERSION.SDK_INT)

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                startPairingService(context)
            }
            else -> {
                WepWawetLogger.proxyWarn("$TAG: unexpected action — ignoring",
                    "action" to (action ?: "null"))
            }
        }
    }

    /**
     * Set [PairingService.appContext] and start the pairing foreground
     * service with the persisted PIN.
     *
     * Wrapped in try/catch — on Android 12+ the system may disallow
     * starting a foreground service from the background even for
     * BOOT_COMPLETED/MY_PACKAGE_REPLACED if the user has revoked the
     * FOREGROUND_SERVICE_SPECIAL_USE permission or if other system
     * restrictions apply. We log instead of crashing.
     */
    private fun startPairingService(context: Context) {
        try {
            // Set appContext first so the static UI accessors (isPaired,
            // queueWallpaper, pairedDeviceName) work even before the
            // user opens the app. Without this, calls to
            // loadPairedDevice(appContext) inside isPaired() would NPE
            // because appContext is null until MainActivity.onCreate.
            PairingService.appContext = context.applicationContext

            val pin = PairingService.getPairingPin(context)
            WepWawetLogger.proxy("$TAG: starting PairingService after boot/update",
                "pin_set" to pin.isNotBlank(),
                "pin_length" to pin.length)

            // This calls Context.startForegroundService(Intent(...))
            // which goes through PairingService.onStartCommand.
            // onStartCommand wraps startForeground in try/catch — if
            // Android disallows the foreground start (e.g. on Android
            // 12+ if the system has revoked the permission), it logs
            // and returns START_STICKY without crashing.
            PairingService.start(context, pin)
        } catch (e: Exception) {
            // Catch any throwable — BroadcastReceiver crashes are fatal
            // (Android marks the receiver as broken and won't deliver
            // future broadcasts to it). Log and continue.
            WepWawetLogger.proxyError("$TAG: failed to start PairingService — will retry on app launch",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
        }
    }
}
