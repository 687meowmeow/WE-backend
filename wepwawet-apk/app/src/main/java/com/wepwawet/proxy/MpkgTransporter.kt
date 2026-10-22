package com.wepwawet.proxy

import android.content.Context
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.LinkedBlockingDeque
import kotlin.concurrent.thread

/**
 * MpkgTransporter — pushes .mpkg files from the outbox to paired devices.
 *
 * Flow:
 *   1. The emulated WE inside Wine writes a `.mpkg` file to a known outbox path:
 *        `<app-internal>/files/mpkg-outbox/<workshopid>-<timestamp>.mpkg`
 *      This is triggered by our [com.wepwawet.wine.WeControlClient] calling
 *      `wallpaper32.exe -control openWallpaper <id>` — WE renders the wallpaper,
 *      converts to .mpkg, and writes to the configured outbox.
 *   2. This transporter polls the outbox directory (every 1 second) for new files.
 *   3. When a new .mpkg appears, it enqueues a push for each paired device.
 *   4. The push is via [WeControlServer.pushMpkgToDevice] (TCP 7889 — TODO(RE): the
 *      actual wire format is undocumented).
 *
 * Until the WE Android APK is decompiled (jadx) and the .mpkg transfer protocol is
 * reverse-engineered, this transporter is a stub — it detects new .mpkg files and logs
 * the intent to push them, but the actual push is not yet implemented.
 */
class MpkgTransporter(
    private val context: Context,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outboxDir = File(context.filesDir, "mpkg-outbox").apply { mkdirs() }
    private val pushQueue = LinkedBlockingDeque<PushJob>()
    @Volatile private var running = false
    private var pollerThread: Thread? = null

    fun start() {
        if (running) {
            WepWawetLogger.proxyWarn("MpkgTransporter.start called but already running")
            return
        }
        running = true
        pollerThread = thread(start = true, name = "wep-mpkg-poller", isDaemon = true) {
            outboxPollLoop()
        }
        scope.launch { pushConsumerLoop() }
        WepWawetLogger.proxy("MpkgTransporter started", "outbox" to outboxDir.absolutePath)
    }

    fun stop() {
        running = false
        runCatching { pollerThread?.interrupt() }
        scope.cancel()
        WepWawetLogger.proxy("MpkgTransporter stopped")
    }

    fun enqueue(mpkgPath: String, deviceId: String?) {
        val file = File(mpkgPath)
        if (!file.exists() || file.length() == 0L) {
            WepWawetLogger.proxyWarn("MpkgTransporter.enqueue: file missing/empty", "path" to mpkgPath)
            return
        }
        pushQueue.offer(PushJob(file, deviceId))
    }

    private fun outboxPollLoop() {
        val seen = mutableSetOf<String>()
        // Initial scan: mark existing files as "seen" so we don't push stale .mpkg on first start
        outboxDir.listFiles { f -> f.isFile && f.name.endsWith(".mpkg") }?.forEach { seen.add(it.absolutePath) }

        while (running) {
            try {
                val files = outboxDir.listFiles { f -> f.isFile && f.name.endsWith(".mpkg") }
                if (files != null) {
                    for (f in files) {
                        val path = f.absolutePath
                        if (path !in seen) {
                            seen.add(path)
                            // Wait for the file to be fully written (close-write detection)
                            // by checking it's stable across two consecutive 500ms polls
                            val initialSize = f.length()
                            Thread.sleep(500)
                            if (f.length() == initialSize && f.length() > 0) {
                                // Stable — enqueue for all paired devices
                                pushQueue.offer(PushJob(f, deviceId = null))
                                WepWawetLogger.proxy("MpkgTransporter: detected new .mpkg",
                                    "path" to f.name, "size" to f.length())
                            }
                        }
                    }
                }
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) { break }
            catch (e: Exception) {
                WepWawetLogger.proxyWarn("MpkgTransporter poll error",
                    "exception" to e.javaClass.name)
                try { Thread.sleep(POLL_INTERVAL_MS * 4) } catch (_: InterruptedException) { break }
            }
        }
    }

    private suspend fun pushConsumerLoop() {
        while (running) {
            val job = try {
                pushQueue.poll() ?: run {
                    try { Thread.sleep(POLL_INTERVAL_MS) } catch (_: InterruptedException) { break }
                    continue
                }
            } catch (_: InterruptedException) { break }
            if (job != null) {
                // TODO(RE): once the wire format is confirmed, actually push the bytes
                // via WeControlServer.pushMpkgToDevice()
                WepWawetLogger.proxyDebug("MpkgTransporter: push job queued (stub)",
                    "file" to job.file.name,
                    "size" to job.file.length(),
                    "target" to (job.deviceId ?: "all"))
            }
        }
    }

    private data class PushJob(val file: File, val deviceId: String?)

    companion object {
        const val POLL_INTERVAL_MS = 1000L
    }
}
