package com.wepwawet.wine

import android.content.Context
import com.wepwawet.log.WepWawetLogger
import java.io.File

/**
 * WinePrefixManager — extracts and configures the bundled Wine prefix on first boot.
 *
 * The prefix ships as a tar.zst archive in `assets/prefix/wine-10.x-arm64-prefix.tar.zst`.
 * On first launch we extract it to `<filesDir>/wine-prefix/`. The prefix contains:
 *   - drive_c/ (Wine's fake C: drive, with Steam pre-installed at first login prompt)
 *   - system.reg / user.reg / userdef.reg (Wine's registry hives)
 *   - dosdevices/ (symlinks for Z: -> rootfs root, etc.)
 *
 * The first time the user logs into Steam, the Steam client downloads the actual
 * Steam runtime libraries + creates the Steam user folder structure inside drive_c.
 *
 * The bundled Wine prefix doesn't contain user credentials — the user logs in via
 * the in-app Steam login WebView, which sets a Steam_login cookie that Wine's
 * built-in browser shares via shared cookie store.
 *
 * Reset: the user can wipe the prefix via Settings → Reset Wine prefix. This deletes
 * `<filesDir>/wine-prefix/` and re-extracts from the bundled tarball.
 */
class WinePrefixManager(
    private val context: Context,
) {

    private val prefixDir = File(context.filesDir, "wine-prefix")
    private val rootfsDir = File(context.filesDir, "rootfs")

    /**
     * Extract the Wine prefix from the bundled assets if not already done.
     * Idempotent — checks the existence of a sentinel file.
     */
    fun ensurePrefixExtracted() {
        val sentinel = File(prefixDir, ".wepwawet_prefix_extracted_v$PREFIX_VERSION")
        if (sentinel.exists()) {
            WepWawetLogger.wineDebug("WinePrefixManager.ensurePrefixExtracted: already extracted")
            return
        }
        if (!prefixDir.exists()) prefixDir.mkdirs()
        try {
            // Locate the bundled tarball in assets
            val assetName = "prefix/wine-${WINE_VERSION}-arm64-prefix.tar.zst"
            val asset = context.assets.open(assetName).use { input ->
                // Stream-extract to a temp file (the asset is ~500 MB — can't fit in memory)
                val tempFile = File(context.cacheDir, "wine-prefix.tar.zst")
                tempFile.outputStream().use { output -> input.copyTo(output) }
                tempFile
            }
            WepWawetLogger.wine("WinePrefixManager.extracting prefix",
                "size_bytes" to asset.length(), "dest" to prefixDir.absolutePath)

            // Extract via `tar --zstd -xf` (uses the system tar binary on Android —
            // confirmed available on Android 10+)
            val process = ProcessBuilder("tar", "--zstd", "-xf", asset.absolutePath,
                "-C", prefixDir.absolutePath).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                WepWawetLogger.wineError("WinePrefixManager: tar extract failed",
                    "exit" to exitCode, "output" to output)
                return
            }
            asset.delete()
            sentinel.writeText(java.time.Instant.now().toString())
            WepWawetLogger.wine("WinePrefixManager: prefix extracted")
        } catch (e: Exception) {
            WepWawetLogger.wineError("WinePrefixManager: extract failed",
                "exception" to e.javaClass.name, exception = e)
        }
    }

    /**
     * Mount the Ubuntu Focal rootfs.
     * - If root is available: use chroot (faster, no PRoot overhead)
     * - Otherwise: use PRoot (slower but works without root)
     */
    fun ensureRootfsMounted() {
        val sentinel = File(rootfsDir, ".wepwawet_rootfs_extracted_v$ROOTFS_VERSION")
        if (!sentinel.exists()) {
            // Extract rootfs tarball (similar to prefix extraction)
            // TODO: implement — same tar --zstd -xf pattern
            WepWawetLogger.wine("WinePrefixManager.ensureRootfsMounted: extracting rootfs (stub)")
            rootfsDir.mkdirs()
            sentinel.writeText(java.time.Instant.now().toString())
        } else {
            WepWawetLogger.wineDebug("WinePrefixManager.ensureRootfsMounted: already extracted")
        }

        // For chroot mode: bind-mount dev, proc, sys into the rootfs
        if (isRootAvailable()) {
            mountBind("/dev", File(rootfsDir, "dev"))
            mountBind("/proc", File(rootfsDir, "proc"))
            mountBind("/sys", File(rootfsDir, "sys"))
            mountBind("/sdcard", File(rootfsDir, "sdcard"))
            WepWawetLogger.wine("WinePrefixManager: chroot bind mounts done")
        } else {
            WepWawetLogger.wineWarn("WinePrefixManager: root unavailable, using PRoot (slower)")
            // PRoot doesn't need bind mounts — it uses ptrace-based syscall translation
        }
    }

    fun unmountRootfs() {
        if (isRootAvailable()) {
            unmountBind(File(rootfsDir, "dev"))
            unmountBind(File(rootfsDir, "proc"))
            unmountBind(File(rootfsDir, "sys"))
            unmountBind(File(rootfsDir, "sdcard"))
        }
    }

    fun isSteamInstalled(): Boolean {
        val steamExe = prefixDir.resolve("drive_c/Program Files (x86)/Steam/steam.exe")
        return steamExe.exists()
    }

    fun isWallpaperEngineInstalled(): Boolean {
        val weExe = prefixDir.resolve("drive_c/Program Files (x86)/Steam/steamapps/common/wallpaper_engine/wallpaper64.exe")
        return weExe.exists()
    }

    fun resetPrefix() {
        prefixDir.deleteRecursively()
        rootfsDir.deleteRecursively()
        WepWawetLogger.wine("WinePrefixManager.resetPrefix: deleted, will re-extract on next start")
    }

    // ====== Internals ======

    private fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    private fun mountBind(source: String, target: File) {
        if (!target.exists()) target.mkdirs()
        try {
            val process = Runtime.getRuntime().exec(arrayOf(
                "su", "-c", "mount --bind $source ${target.absolutePath}"
            ))
            val exit = process.waitFor()
            WepWawetLogger.wineDebug("mountBind $source -> ${target.absolutePath}",
                "exit" to exit)
        } catch (e: Exception) {
            WepWawetLogger.wineWarn("mountBind failed",
                "src" to source, "tgt" to target.absolutePath, "exception" to e.javaClass.name)
        }
    }

    private fun unmountBind(target: File) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf(
                "su", "-c", "umount ${target.absolutePath}"
            ))
            process.waitFor()
        } catch (_: Exception) { /* best-effort */ }
    }

    companion object {
        const val WINE_VERSION = "10.10"  // matches Winlator v11.2
        const val PREFIX_VERSION = 1
        const val ROOTFS_VERSION = 1
    }
}
