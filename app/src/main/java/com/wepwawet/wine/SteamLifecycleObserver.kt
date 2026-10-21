package com.wepwawet.wine

import android.content.Context
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException

/**
 * SteamLifecycleObserver — watches the emulated Steam process state.
 *
 * Responsibilities:
 *   - Detect when Steam is "ready" (the steam.exe process is past its bootstrap and
 *     the steamwebhelper child is up)
 *   - Detect Steam crashes (process exited unexpectedly) and trigger restart
 *   - Detect Steam idle (no workshop downloads, no UI activity) and trigger shutdown
 *     to save battery (per user: "i dont want it running constantly, only in use")
 *
 * Implementation: polls the Wine prefix's Steam IPC socket file presence + Steam's
 * `<prefix>/drive_c/Program Files (x86)/Steam/logs/` content for state transitions.
 * Once we have Winlator vendored, we can hook into its existing XClient connection
 * state for cleaner signal.
 */
class SteamLifecycleObserver(
    private val context: Context,
) {

    private val prefixDir = File(context.filesDir, "wine-prefix")
    private val steamLogsDir = prefixDir.resolve("drive_c/Program Files (x86)/Steam/logs")

    /**
     * Block until Steam is "ready" or until timeoutMs elapses.
     * "Ready" = the steam.exe process is past its bootstrap AND the steamwebhelper
     * child process is up (best detected by reading the content of bootstrap_log.txt).
     */
    suspend fun awaitSteamReady(timeoutMs: Long = 90_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            val ready = checkSteamReady()
            if (ready) {
                WepWawetLogger.steam("SteamLifecycleObserver: Steam ready",
                    "attempts" to attempt, "elapsed_ms" to (System.currentTimeMillis() - (deadline - timeoutMs)))
                return true
            }
            delay(1000)
        }
        WepWawetLogger.steam("SteamLifecycleObserver: Steam not ready within timeout",
            "timeout_ms" to timeoutMs, "attempts" to attempt)
        return false
    }

    /**
     * Best-effort "is Steam ready" check.
     */
    private fun checkSteamReady(): Boolean {
        val bootstrapLog = File(steamLogsDir, "bootstrap_log.txt")
        if (!bootstrapLog.exists()) return false
        return try {
            val content = bootstrapLog.readText()
            // The bootstrap log contains "Startup - Steam Client initialized"
            // (or similar — we need to capture an actual log to confirm the exact string)
            content.contains("initialized", ignoreCase = true) ||
                content.contains("SteamUI", ignoreCase = true)
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Periodic check that Steam is still alive (process didn't crash).
     */
    suspend fun observeForever(intervalMs: Long = 5000) {
        while (true) {
            try {
                val alive = checkSteamAlive()
                WepWawetLogger.steamDebug("SteamLifecycleObserver.observe", "alive" to alive)
                if (!alive) {
                    WepWawetLogger.steam("SteamLifecycleObserver: Steam died — TODO restart")
                    // TODO: trigger WineProcessService.startWine() retry
                }
                delay(intervalMs)
            } catch (_: InterruptedException) { break }
        }
    }

    private fun checkSteamAlive(): Boolean {
        // TODO: check the Box64 process state via /proc/<pid>/status
        // For now: heuristic — check if Steam's IPC socket file in the prefix is recently touched
        val sock = prefixDir.resolve("drive_c/Program Files (x86)/Steam/steam.exe.sock")
        if (!sock.exists()) return false
        val ageMs = System.currentTimeMillis() - sock.lastModified()
        return ageMs < 30_000  // touched in the last 30s
    }
}
