package com.wepwawet.wine

import android.content.Context
import com.wepwawet.log.WepWawetLogger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Box64Runner — wraps invocation of the Box64 binary inside our Wine prefix.
 *
 * Architecture (per research Task 1-A):
 *   - Box64 binary lives at <app>/jniLibs/arm64-v8a/libbox64.so (exec'd via /proc/self/exe trick
 *     because Android only allows binaries in the lib dir to be exec'd)
 *   - Patchelf'd to use the Winlator rootfs ld-linux-aarch64.so.1 as its dynamic linker
 *     (same trick as Box64's own .wcp archive per their CI)
 *   - Run env vars: WINLATOR_GLIBC=1, BOX64_STEAM_VULKAN=1, WINEDLLOVERRIDES for any custom
 *     overrides (e.g. bcrypt shim if needed), WINEPREFIX=<prefix-path>
 *   - Built with `-DBOX32=ON` so the Steam webhelper's 32-bit components work
 *
 * This class is a stub — the actual exec() call requires Winlator's ContainerManager and
 * XEnvironment Java classes (which we'll vendor into this repo from a fork of winlator-app).
 * Until vendored, this class provides:
 *   - The env-var composition
 *   - The pre/post hooks for logging
 *   - The ProcessBuilder invocation pattern (real implementation TBD)
 */
class Box64Runner(
    private val context: Context,
) {

    /**
     * Box64 binary path. We place it under jniLibs so Android's package installer treats it
     * as a native library and exec-permissions are correctly set.
     */
    private val box64Binary: File by lazy {
        File(context.applicationInfo.nativeLibraryDir, "libbox64.so")
    }

    /**
     * Compose the Box64 environment variables.
     */
    fun buildEnv(prefixPath: File, rootfsPath: File): Map<String, String> {
        val env = mutableMapOf<String, String>()
        // Standard Android env (inherited)
        env.putAll(System.getenv())

        // Box64-specific
        env["WINLATOR_GLIBC"] = "1"            // required for Steam under Winlator (per ivonblog tutorial)
        env["BOX64_STEAM_VULKAN"] = "1"        // inject Vulkan renderer into steamwebhelper
        env["BOX64_DYNAREC"] = "1"             // enable DynaRec (5-10x speedup over interpreter)
        env["BOX64_DYNACACHE"] = "1"           // cache translated blocks across launches
        env["BOX64_DYNACACHE_PATH"] = context.cacheDir.resolve("box64-dynacache").absolutePath
        env["BOX64_LOG_LEVEL"] = "1"           // info+ from Box64's own logger

        // Wine-specific
        env["WINEPREFIX"] = prefixPath.absolutePath
        env["WINEDEBUG"] = "-all"              // silence Wine's verbose logs (we get them via our logger)
        env["WINEDLLOVERRIDES"] = buildDllOverrides()

        // Rootfs-specific
        env["LD_LIBRARY_PATH"] = listOf(
            rootfsPath.resolve("usr/lib").absolutePath,
            rootfsPath.resolve("usr/lib/aarch64-linux-gnu").absolutePath,
            // Mesa Turnip driver location
            rootfsPath.resolve("usr/lib/turnip").absolutePath,
            context.applicationInfo.nativeLibraryDir,
        ).joinToString(":")

        // PATH for shell helpers
        env["PATH"] = listOf(
            rootfsPath.resolve("usr/bin").absolutePath,
            rootfsPath.resolve("usr/local/bin").absolutePath,
            "/system/bin",
            "/system/xbin",
        ).joinToString(":")

        return env
    }

    private fun buildDllOverrides(): String {
        val overrides = mutableListOf<String>()
        // Use built-in Wine versions of these (do NOT use Windows-native)
        overrides += "winemenubuilder.exe="
        overrides += "mscoree="
        overrides += "mshtml="
        // DXVK forces d3d9/d3d10/d3d10core/d3d11 to native
        overrides += "d3d9=native,builtin"
        overrides += "d3d10=native,builtin"
        overrides += "d3d10core=native,builtin"
        overrides += "d3d11=native,builtin"
        overrides += "dxgi=native,builtin"
        // TODO: bcrypt override if Wine's bcrypt proves insufficient for pairing
        // (the native proxy architecture sidesteps this, but if we ever want the emulated
        // WE itself to pair, we'd need this)
        // overrides += "bcrypt=native"  // would require shipping a Windows bcrypt.dll (ToS issue)
        return overrides.joinToString(";")
    }

    /**
     * Launch Steam under Wine+Box64.
     *
     * Steam flags chosen for low-overhead background operation:
     *   -steam_chrome_disabled (no embedded Chrome)
     *   -dev   (slightly more verbose logging)
     *   -skipinitialbootstrap (skip first-launch wizard if prefix is pre-baked)
     *   -noverifyfiles (skip the file integrity check that takes 30-60s)
     *   -nofriendsui (no friends chat UI)
     *   -no-browser (no Steam browser)
     *   -console (so we can pipe commands via stdin)
     */
    fun launchSteam(): Process? {
        val prefixPath = File(context.filesDir, "wine-prefix")
        val rootfsPath = File(context.filesDir, "rootfs")
        val steamExe = prefixPath.resolve("drive_c/Program Files (x86)/Steam/steam.exe")
        if (!steamExe.exists()) {
            WepWawetLogger.wineError("launchSteam: steam.exe missing", "path" to steamExe.absolutePath)
            return null
        }
        val args = listOf(
            box64Binary.absolutePath,
            steamExe.absolutePath,
            "-steam_chrome_disabled",
            "-dev",
            "-skipinitialbootstrap",
            "-noverifyfiles",
            "-nofriendsui",
            "-no-browser",
            "-console",
            "-nofriendsui",
        )
        return exec(args, buildEnv(prefixPath, rootfsPath))
    }

    /**
     * Launch Wallpaper Engine in headless mode.
     *
     * The `-control windowstate hidden` flags tell WE to start without a visible window —
     * it renders into a texture sink that we can extract .mpkg from.
     */
    fun launchWallpaperEngineHeadless(): Process? {
        val prefixPath = File(context.filesDir, "wine-prefix")
        val rootfsPath = File(context.filesDir, "rootfs")
        val weExe = prefixPath.resolve("drive_c/Program Files (x86)/Steam/steamapps/common/wallpaper_engine/wallpaper64.exe")
        if (!weExe.exists()) {
            WepWawetLogger.wineError("launchWallpaperEngineHeadless: wallpaper64.exe missing",
                "path" to weExe.absolutePath)
            return null
        }
        val args = listOf(
            box64Binary.absolutePath,
            weExe.absolutePath,
            "-control", "windowstate", "hidden",
            "-noaudio",  // we're not playing audio on the phone's PC emulation; the .mpkg carries audio
        )
        return exec(args, buildEnv(prefixPath, rootfsPath))
    }

    /**
     * Invoke a Steam console command (e.g. workshop_download_item).
     * Steam's console is accessible via stdin pipe when launched with -console.
     */
    fun invokeSteamCmd(vararg args: String): Boolean {
        // TODO: pipe to Steam's stdin once Steam is running
        WepWawetLogger.wine("invokeSteamCmd (stub)", "args" to args.joinToString(" "))
        return true
    }

    /**
     * Kill all Box64+Wine processes gracefully.
     */
    fun shutdown() {
        WepWawetLogger.wine("Box64Runner.shutdown — sending wineboot -k")
        val prefixPath = File(context.filesDir, "wine-prefix")
        val rootfsPath = File(context.filesDir, "rootfs")
        val winebootPath = prefixPath.resolve("drive_c/.../wineboot")  // TODO resolve real path
        // Best-effort: wineboot -k signals Wine to gracefully shutdown
        try {
            val args = listOf(box64Binary.absolutePath, "wineboot", "-k")
            exec(args, buildEnv(prefixPath, rootfsPath))?.waitFor(10_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            WepWawetLogger.wineWarn("Box64Runner.shutdown: wineboot failed",
                "exception" to e.javaClass.name)
        }
    }

    // ====== Internals ======

    private fun exec(args: List<String>, env: Map<String, String>): Process? {
        WepWawetLogger.wine("exec", "cmd" to args.joinToString(" "), "env_count" to env.size)
        return try {
            val pb = ProcessBuilder(args).apply {
                environment().clear()
                environment().putAll(env)
                redirectErrorStream(true)
            }
            val process = pb.start()
            // Pump stdout/stderr into our logger so Wine/Box64 logs appear in the app
            Thread {
                BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                    lines.forEach { line ->
                        WepWawetLogger.wineDebug("box64:stdout", "line" to line)
                    }
                }
            }.also { it.isDaemon = true; it.start() }
            process
        } catch (e: Exception) {
            WepWawetLogger.wineError("exec failed",
                "cmd" to args.joinToString(" "),
                "exception" to e.javaClass.name,
                exception = e)
            null
        }
    }
}
