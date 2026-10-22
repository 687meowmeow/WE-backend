package com.wepwawet.wine

import android.content.Context
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * WeControlClient — talks to the emulated Wallpaper Engine via its documented CLI:
 *   wallpaper32.exe -control <command> [args...]
 *
 * Per research Task 1-C, the documented commands are:
 *   pause                 - pause the current wallpaper
 *   stop                  - stop the current wallpaper
 *   play <wallpaper_id>   - play a specific wallpaper
 *   mute / unmute         - audio control
 *   openWallpaper <id>    - open a new wallpaper (this is what triggers .mpkg creation)
 *   closeWallpaper        - close the current wallpaper
 *   nextWallpaper         - skip to next in playlist
 *   openPlaylist <name>   - load a playlist
 *   applyPreset <name>    - apply a saved preset
 *   settings              - open the WE settings
 *   getCurrentWallpaper   - returns the current wallpaper's workshop id (printed to stdout)
 *
 * !! Important — this is the OPPOSITE direction from the Android app's control protocol !!
 * The Android WE app speaks an undocumented wire protocol over TCP 7889. WE's CLI is the
 * documented way for any local process to control WE. We use the CLI for our emulated WE,
 * and we implement the wire protocol separately in [com.wepwawet.proxy.WeControlServer]
 * for the official Android WE app to talk to US.
 *
 * The CLI is invoked via `wallpaper32.exe -control <args>` inside the Wine prefix, which
 * requires Box64 to launch Wine which runs the .exe. There's a ~1-2 second overhead per
 * invocation due to Wine prefix startup; for batch operations we should consider keeping
 * a long-running WE RPC server process alive.
 */
class WeControlClient(
    private val context: Context,
) {

    private val box64Runner: Box64Runner? by lazy { Box64Runner(context).takeIf { true } }

    /**
     * Invoke `wallpaper32.exe -control <args>` inside the Wine prefix.
     * Returns the stdout of the command (for `getCurrentWallpaper`-style queries).
     */
    suspend fun invoke(vararg args: String): String? {
        val cmd = listOf("wallpaper32.exe", "-control") + args.toList()
        WepWawetLogger.wine("WeControlClient.invoke", "cmd" to cmd.joinToString(" "))
        // TODO: actually exec via Box64Runner. For now: stub and log.
        // When implemented, the call will be:
        //   box64Runner.execInPrefix(cmd)
        // and we'll return the stdout string.
        delay(1500)  // simulate Wine launch latency
        return null
    }

    /**
     * Convenience: open a wallpaper by workshop id. This is what triggers the emulated
     * WE to render the wallpaper + produce the .mpkg file that [MpkgTransporter] will push.
     */
    suspend fun openWallpaper(workshopId: String): Boolean {
        val result = invoke("openWallpaper", workshopId)
        return result != null
    }

    /**
     * Convenience: query the current wallpaper's workshop id.
     */
    suspend fun getCurrentWallpaper(): String? = invoke("getCurrentWallpaper")

    /**
     * Convenience: pause rendering.
     */
    suspend fun pause(): Boolean = invoke("pause") != null

    /**
     * Convenience: stop rendering.
     */
    suspend fun stop(): Boolean = invoke("stop") != null
}
