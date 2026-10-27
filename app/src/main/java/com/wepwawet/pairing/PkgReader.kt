package com.wepwawet.pairing

import com.wepwawet.log.WepWawetLogger
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads Wallpaper Engine `.pkg` files (the workshop content format, NOT to be
 * confused with `.mpkg` which is the mobile transfer format).
 *
 * === PKG FORMAT (verified from repkg source + wallpaperui.exe strings) ===
 *
 *   [u32 LE magic_len] [magic bytes]                (variable-length header)
 *   [u32 LE entry_count]
 *   For each entry:
 *     [u32 LE path_len] [path bytes UTF-8]
 *     [u32 LE file_offset]   (RELATIVE to data_section_start)
 *     [u32 LE file_size]
 *   [data section: file blobs packed back-to-back]
 *
 * Magic string is "PKGV0024" (8 bytes) per wallpaperui.exe strings table.
 *
 * This format is DIFFERENT from .mpkg:
 *   - .pkg uses path string + size pairs (no name_len prefix on the magic)
 *   - .mpkg uses header_marker(8) + magic(8) + entry_count + name/offset/size
 *
 * === Source of .pkg files ===
 *
 * Steam Workshop content for app 431960 (Wallpaper Engine) is stored as .pkg
 * files. They are typically downloaded by:
 *   1. Steam client subscribing to a workshop item → downloads .pkg to
 *      `Steam/steamapps/workshop/content/431960/<id>/` on PC
 *   2. SteamCMD via `workshop_download_item 431960 <id>` (requires Steam login)
 *   3. Third-party tools like DepotDownloaderMod (without owning the game)
 *
 * The WepWawet app uses approach #1 by having the user manually copy the .pkg
 * file from their PC's Steam folder to their phone.
 *
 * === Content inside a .pkg ===
 *
 * A typical .pkg for a Scene wallpaper contains:
 *   - project.json   (wallpaper metadata — type, file, etc.)
 *   - scene.json     (scene graph — camera, objects, materials)
 *   - materials/{name}.json (material definitions referencing shaders + textures)
 *   - materials/{name}.tex (binary texture files — RGBA8888, DXT, or ETC2 formats)
 *   - models/{name}.json  (model definitions)
 *   - shaders/{name}.frag, {name}.vert (custom shader source code)
 *   - effects/{name}.json (post-processing effects)
 *   - preview.jpg    (thumbnail shown in WE Android app's browse list)
 *
 * A Video wallpaper .pkg typically contains:
 *   - project.json (type: "video")
 *   - wallpaper.mp4 (the actual video file)
 *   - preview.jpg
 *
 * === Output of this reader ===
 *
 * The reader produces a list of (path, bytes) pairs. The caller (MpkgPacker)
 * takes this list and packs them into a .mpkg file for transfer to the WE
 * Android app.
 */
object PkgReader {

    private const val TAG = "PkgReader"
    private val SUPPORTED_MAGICS = setOf("PKGV0024", "PKGV0023", "PKGV0022", "PKGV0021", "PKGV0020", "PKGV0015", "PKGV0014", "PKGV0013", "PKGV0012", "PKGV0011", "PKGV0010", "PKGV0009", "PKGV0008", "PKGV0007", "PKGV0006", "PKGV0005", "PKGV0004", "PKGV0003", "PKGV0002", "PKGV0001")
    private const val MAX_MAGIC_LEN = 32
    private const val MAX_PATH_LEN = 512
    private const val MAX_ENTRY_COUNT = 4096

    /**
     * Read a .pkg file and return the list of (path, bytes) entries.
     *
     * @param pkgFile the .pkg file to read
     * @return list of (path, bytes) pairs, or empty list on error
     */
    fun read(pkgFile: File): List<Pair<String, ByteArray>> {
        WepWawetLogger.proxy("$TAG.read START",
            "file" to pkgFile.name,
            "file_size" to pkgFile.length(),
            "absolute_path" to pkgFile.absolutePath)

        if (!pkgFile.exists() || !pkgFile.canRead()) {
            WepWawetLogger.proxyError("$TAG file not readable",
                "exists" to pkgFile.exists(),
                "readable" to pkgFile.canRead(),
                "path" to pkgFile.absolutePath)
            return emptyList()
        }

        if (pkgFile.length() < 16) {
            WepWawetLogger.proxyError("$TAG file too small",
                "size" to pkgFile.length(),
                "minimum" to 16)
            return emptyList()
        }

        val raf = RandomAccessFile(pkgFile, "r")
        try {
            // Step 1: Read magic (length-prefixed string)
            val magicLen = readU32Le(raf)
            WepWawetLogger.proxy("$TAG read magic_len",
                "magic_len" to magicLen)
            if (magicLen !in 1..MAX_MAGIC_LEN) {
                WepWawetLogger.proxyError("$TAG implausible magic_len",
                    "magic_len" to magicLen,
                    "expected_range" to "1..$MAX_MAGIC_LEN")
                return emptyList()
            }
            val magicBytes = ByteArray(magicLen.toInt())
            raf.readFully(magicBytes)
            val magic = String(magicBytes, Charsets.US_ASCII)
            val isSupported = magic in SUPPORTED_MAGICS
            WepWawetLogger.proxy("$TAG read magic",
                "magic" to magic,
                "supported" to isSupported,
                "supported_versions" to SUPPORTED_MAGICS.joinToString(", "))
            if (!isSupported) {
                WepWawetLogger.proxyError("$TAG unexpected magic",
                    "magic" to magic,
                    "supported_versions" to SUPPORTED_MAGICS.joinToString(", "))
                return emptyList()
            }

            // Step 2: Read entry count
            val entryCount = readU32Le(raf)
            WepWawetLogger.proxy("$TAG read entry_count",
                "entry_count" to entryCount)
            if (entryCount !in 1..MAX_ENTRY_COUNT) {
                WepWawetLogger.proxyError("$TAG implausible entry_count",
                    "entry_count" to entryCount,
                    "expected_range" to "1..$MAX_ENTRY_COUNT")
                return emptyList()
            }

            // Step 3: Read VFS table (path + offset + size for each entry)
            val entries = mutableListOf<Triple<String, Long, Long>>()
            var totalPathBytes = 0L
            for (i in 0 until entryCount) {
                val pathLen = readU32Le(raf).toInt()
                if (pathLen !in 1..MAX_PATH_LEN) {
                    WepWawetLogger.proxyError("$TAG implausible path_len",
                        "index" to i, "path_len" to pathLen)
                    return emptyList()
                }
                val pathBytes = ByteArray(pathLen)
                raf.readFully(pathBytes)
                val path = String(pathBytes, Charsets.UTF_8)
                // v0.5.32 (audit M2): use L suffix on the mask literal.
                // Previously `.and(0xFFFFFFFF)` (Int) is functionally a
                // no-op for in-range u32 values (which readU32Le always
                // returns), but the intent is clearly to mask to 32 bits.
                // Using `.and(0xFFFFFFFFL)` (Long) makes the intent explicit
                // and would actually mask if readU32Le ever returned an
                // out-of-range Long.
                val offset = readU32Le(raf).and(0xFFFFFFFFL)
                val size = readU32Le(raf).and(0xFFFFFFFFL)
                entries.add(Triple(path, offset, size))
                totalPathBytes += pathLen
                if (i < 5 || i == entryCount - 1) {
                    WepWawetLogger.proxy("$TAG entry",
                        "index" to i,
                        "path" to path,
                        "offset" to offset,
                        "size" to size)
                } else if (i == 5L) {
                    WepWawetLogger.proxy("$TAG ...suppressing further entries...",
                        "total" to entryCount)
                }
            }

            // Step 4: Determine data section start = current file position
            val dataSectionStart = raf.filePointer
            WepWawetLogger.proxy("$TAG data section start",
                "data_section_start" to dataSectionStart,
                "total_path_bytes" to totalPathBytes,
                "header_size" to dataSectionStart)

            // Step 5: Read each entry's data
            val result = mutableListOf<Pair<String, ByteArray>>()
            for ((index, entry) in entries.withIndex()) {
                val (path, offset, size) = entry
                try {
                    raf.seek(dataSectionStart + offset)
                    val data = ByteArray(size.toInt())
                    raf.readFully(data)
                    result.add(path to data)
                    if (index < 5 || index == entries.size - 1) {
                        WepWawetLogger.proxy("$TAG extracted",
                            "index" to index,
                            "path" to path,
                            "size" to size,
                            "first_16_bytes" to data.take(16).joinToString("") { "%02x".format(it) })
                    }
                } catch (e: Exception) {
                    WepWawetLogger.proxyError("$TAG failed to extract entry",
                        "index" to index, "path" to path,
                        "exception" to e.javaClass.name, "message" to (e.message ?: ""))
                }
            }

            WepWawetLogger.proxy("$TAG.read COMPLETE",
                "entries" to result.size,
                "total_bytes" to result.sumOf { it.second.size },
                "largest_entry" to result.maxByOrNull { it.second.size }?.let { (p, b) -> "${p}=${b.size}" })

            return result
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG.read FAILED",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            return emptyList()
        } finally {
            try { raf.close() } catch (_: Exception) {}
        }
    }

    /**
     * Data class representing a VFS table entry from a .pkg file.
     * Contains the path, offset (relative to data section start), and size.
     * Does NOT contain the actual file data — use [readEntryData] to load it.
     */
    data class VfsEntry(val path: String, val offset: Long, val size: Long)

    /**
     * Read ONLY the VFS table from a .pkg file — does NOT load any entry data.
     * This is used by [MpkgPacker.packFromPkg] to stream large entries from
     * .pkg to .mpkg without loading them into memory (avoids OOM on large
     * .pkg files, e.g., 256MB wallpapers with 163MB model files).
     *
     * @return list of [VfsEntry] (path, offset, size) + the data section start offset,
     *         or null on error.
     */
    fun readVfsTable(pkgFile: File): Pair<List<VfsEntry>, Long>? {
        if (!pkgFile.exists() || !pkgFile.canRead() || pkgFile.length() < 16) return null
        val raf = RandomAccessFile(pkgFile, "r")
        try {
            val magicLen = readU32Le(raf)
            if (magicLen !in 1..MAX_MAGIC_LEN) return null
            val magicBytes = ByteArray(magicLen.toInt())
            raf.readFully(magicBytes)
            val magic = String(magicBytes, Charsets.US_ASCII)
            if (magic !in SUPPORTED_MAGICS) return null

            val entryCount = readU32Le(raf)
            if (entryCount !in 1..MAX_ENTRY_COUNT) return null

            val entries = mutableListOf<VfsEntry>()
            for (i in 0 until entryCount) {
                val pathLen = readU32Le(raf).toInt()
                if (pathLen !in 1..MAX_PATH_LEN) return null
                val pathBytes = ByteArray(pathLen)
                raf.readFully(pathBytes)
                val path = String(pathBytes, Charsets.UTF_8)
                val offset = readU32Le(raf).and(0xFFFFFFFFL)
                val size = readU32Le(raf).and(0xFFFFFFFFL)
                entries.add(VfsEntry(path, offset, size))
            }
            val dataSectionStart = raf.filePointer
            return entries to dataSectionStart
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG.readVfsTable FAILED", "exception" to e.javaClass.name)
            return null
        } finally {
            try { raf.close() } catch (_: Exception) {}
        }
    }

    /**
     * Read a single entry's data from the .pkg file.
     * Used for loading ONLY entries that need modification (shaders, .tex, JSONs)
     * while streaming the rest directly to the .mpkg output.
     *
     * @param pkgFile the .pkg file
     * @param dataSectionStart the offset where the data section begins (from [readVfsTable])
     * @param offset the entry's offset within the data section
     * @param size the entry's size in bytes
     * @return the entry's data as a ByteArray, or null on error
     */
    fun readEntryData(pkgFile: File, dataSectionStart: Long, offset: Long, size: Long): ByteArray? {
        return try {
            val raf = RandomAccessFile(pkgFile, "r")
            try {
                raf.seek(dataSectionStart + offset)
                val data = ByteArray(size.toInt())
                raf.readFully(data)
                data
            } finally {
                raf.close()
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG.readEntryData FAILED",
                "offset" to offset, "size" to size, "exception" to e.javaClass.name)
            null
        }
    }

    /**
     * Stream a single entry's data from the .pkg file directly to an OutputStream.
     * Does NOT load the data into memory — uses a small (64KB) buffer.
     *
     * @param pkgFile the .pkg file
     * @param dataSectionStart the offset where the data section begins
     * @param offset the entry's offset within the data section
     * @param size the entry's size in bytes
     * @param out the destination OutputStream
     */
    fun streamEntryData(pkgFile: File, dataSectionStart: Long, offset: Long, size: Long, out: java.io.OutputStream) {
        val raf = RandomAccessFile(pkgFile, "r")
        try {
            raf.seek(dataSectionStart + offset)
            val buffer = ByteArray(64 * 1024) // 64KB buffer
            var remaining = size
            while (remaining > 0) {
                val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                raf.readFully(buffer, 0, toRead)
                out.write(buffer, 0, toRead)
                remaining -= toRead
            }
        } finally {
            raf.close()
        }
    }
    private fun readU32Le(raf: RandomAccessFile): Long {
        val b = ByteArray(4)
        raf.readFully(b)
        return (b[0].toLong() and 0xFF) or
               ((b[1].toLong() and 0xFF) shl 8) or
               ((b[2].toLong() and 0xFF) shl 16) or
               ((b[3].toLong() and 0xFF) shl 24)
    }

    /**
     * Detect the wallpaper type from the extracted .pkg entries.
     * Reads project.json's "type" field.
     *
     * @return "scene", "video", "image", or "unknown"
     */
    fun detectWallpaperType(entries: List<Pair<String, ByteArray>>): String {
        val projectJson = entries.firstOrNull { it.first == "project.json" }?.second
            ?: return "unknown"
        val json = String(projectJson, Charsets.UTF_8)
        WepWawetLogger.proxy("$TAG.detectWallpaperType",
            "project_json_size" to projectJson.size,
            "project_json_preview" to json.take(300))
        // Use proper JSON parsing instead of regex. The regex approach was broken:
        // it matched the FIRST occurrence of `"type":"<value>"` anywhere in the
        // project.json text — including inside string fields like "description"
        // that contain JS code (e.g., the Saturn wallpaper's description contains
        // `"type":"bool"` which caused type detection to return "bool" instead
        // of "scene").
        val type = try {
            val root = com.google.gson.JsonParser.parseString(json).asJsonObject
            val typeField = root.get("type")
            if (typeField != null && typeField.isJsonPrimitive) {
                typeField.asString.lowercase()
            } else {
                "unknown"
            }
        } catch (e: Exception) {
            // Fall back to regex if JSON parsing fails (malformed JSON)
            WepWawetLogger.proxyWarn("$TAG.detectWallpaperType: JSON parse failed, falling back to regex",
                "exception" to e.javaClass.name)
            val typeRegex = Regex("\"type\"\\s*:\\s*\"([a-zA-Z]+)\"")
            val match = typeRegex.find(json)
            match?.groupValues?.get(1)?.lowercase() ?: "unknown"
        }
        WepWawetLogger.proxy("$TAG.detectWallpaperType done",
            "type" to type)
        return type
    }

    /**
     * Find the .mp4 video file inside the .pkg entries.
     * WE Video wallpapers store the video as "wallpaper.mp4" by convention.
     *
     * @return the (path, bytes) of the MP4 file, or null if not found
     */
    fun findVideoFile(entries: List<Pair<String, ByteArray>>): Pair<String, ByteArray>? {
        // First try the conventional "wallpaper.mp4" name
        entries.firstOrNull { it.first.endsWith(".mp4", ignoreCase = true) }?.let {
            WepWawetLogger.proxy("$TAG.findVideoFile",
                "found" to it.first,
                "size" to it.second.size)
            return it
        }
        // Also check for .webm (some wallpapers use WebM)
        entries.firstOrNull { it.first.endsWith(".webm", ignoreCase = true) }?.let {
            WepWawetLogger.proxy("$TAG.findVideoFile",
                "found" to it.first,
                "size" to it.second.size,
                "format" to "webm")
            return it
        }
        WepWawetLogger.proxyWarn("$TAG.findVideoFile no video file found",
            "checked_extensions" to ".mp4, .webm",
            "entries" to entries.map { it.first })
        return null
    }

    /**
     * Extract the .tex file(s) from the .pkg entries.
     * These are the binary texture files used by Scene wallpapers.
     *
     * @return list of (path, bytes) for each .tex file
     */
    fun findTexFiles(entries: List<Pair<String, ByteArray>>): List<Pair<String, ByteArray>> {
        val texFiles = entries.filter { it.first.endsWith(".tex", ignoreCase = true) }
        WepWawetLogger.proxy("$TAG.findTexFiles",
            "count" to texFiles.size,
            "files" to texFiles.map { "${it.first}=${it.second.size}" })
        return texFiles
    }

    /**
     * Find the preview image file (preview.jpg or preview.png) inside .pkg entries.
     */
    fun findPreviewFile(entries: List<Pair<String, ByteArray>>): Pair<String, ByteArray>? {
        return entries.firstOrNull { it.first.equals("preview.jpg", ignoreCase = true) }
            ?: entries.firstOrNull { it.first.equals("preview.png", ignoreCase = true) }
            ?: entries.firstOrNull { it.first.endsWith(".jpg", ignoreCase = true) && it.first.contains("preview", ignoreCase = true) }
    }
}
