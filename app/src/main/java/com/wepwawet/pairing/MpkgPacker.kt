package com.wepwawet.pairing

import com.wepwawet.data.model.WorkshopItemTyped
import com.wepwawet.log.WepWawetLogger
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Packs a Wallpaper Engine .mpkg file using the REAL binary format verified
 * from C++ source + two real samples + the decompiled libscenejni.so.
 *
 * === FORMAT (verified from /home/z/my-project/scripts/toolkit/mpkg_packer.py
 *     and from decompiled io.wallpaperengine.weutil.Util.getMPKGVideoReader) ===
 *
 *   [u32 LE header_marker = 8]               (4 bytes — length of the magic)
 *   [8-byte ASCII magic]                       ("PKGM" + 4-digit version, e.g. "PKGM0020")
 *   [u32 LE entry_count]                       (4 bytes)
 *   For each entry:
 *     [u32 LE name_len]
 *     [name_len bytes UTF-8 name]              (no null terminator)
 *     [u32 LE file_offset]                     (RELATIVE to data_section_start)
 *     [u32 LE file_size]
 *   [data section: file blobs packed back-to-back]
 *
 * === project.json schema (verified from libscenejni.so string literals:
 *     description, file, general, image, preview, title, type, website,
 *     background, height, orientation, package, width, plus capitalized
 *     "Scene" and "Video". Android only supports "Scene", "Video", "image"
 *     — lowercase "image" for image type, capitalized for others.) ===
 *
 * The .mpkg is a VFS (virtual filesystem). The native library looks up files
 * by name within it via `SceneLib.getWallpaperFileVirtualOffset(filePath)`.
 *
 * For Image-type wallpapers, the `file` field of project.json points to the
 * actual image file (e.g. "preview.jpg"), and that file is embedded as a
 * VFS entry.
 *
 * The .mpkg is rejected by `SceneLib.isWallpaperVersionValid` if:
 *   - Magic is wrong (must be "PKGM" + 4 digits)
 *   - Version is unsupported (we use "0020" = MPKG_SUPPORT_VERSION)
 *   - VFS structure is corrupt
 *   - project.json is missing or has wrong field types
 */
object MpkgPacker {

    /**
     * Magic = "PKGM" + 4-digit version.
     *
     * CRITICAL: We use "PKGM0014" (NOT "PKGM0020") because the user uploaded
     * a real .mpkg from the WE Android app and it uses magic "PKGM0014".
     * The phone's EncAuthRequest.mpkgVersion field says "PKGM0020", but that's
     * the MAX supported version — actual files on disk use the older PKGM0014
     * format. Matching the real file's magic maximizes the chance that the
     * native SceneLib.isWallpaperVersionValid() accepts our file.
     */
    const val MAGIC = "PKGM0014"

    /** u32 LE = 8 (length of the magic, also serves as a sanity check). */
    const val HEADER_MARKER = 8

    /**
     * Pack the contents of [imageFile] (a JPG/PNG/etc. image) into a .mpkg at
     * [outputFile] as an Image-type wallpaper.
     *
     * @param item workshop item metadata (title, description, etc.)
     * @param imageFile the actual image file (preview JPG works for Image type)
     * @param imageVPath the VFS path inside the .mpkg (e.g. "preview.jpg")
     * @param outputFile the output .mpkg file path
     * @return the size of the .mpkg in bytes
     */
    fun packImageWallpaper(
        item: WorkshopItemTyped,
        imageFile: File,
        imageVPath: String = "preview.jpg",
        outputFile: File,
    ): Long {
        require(imageFile.exists()) { "imageFile not found: ${imageFile.absolutePath}" }
        require(imageFile.canRead()) { "imageFile not readable: ${imageFile.absolutePath}" }

        val previewBytes = imageFile.readBytes()
        val projectJson = buildProjectJson(item, imageVPath, imageVPath)
        val projectJsonBytes = projectJson.toByteArray(Charsets.UTF_8)

        WepWawetLogger.proxy("MpkgPacker.packImageWallpaper",
            "item_id" to item.id,
            "title" to item.title.take(80),
            "image_vpath" to imageVPath,
            "image_bytes" to previewBytes.size,
            "project_json_bytes" to projectJsonBytes.size,
            "output" to outputFile.absolutePath,
            "magic" to MAGIC,
            "project_json_preview" to projectJson.take(200))

        // VFS entries: (name, bytes) pairs.
        // project.json MUST be present (native code looks it up by name).
        // The image file (referenced by project.json's "file" field) MUST also
        // be present — the native SceneLib.getWallpaperFileVirtualOffset()
        // looks up files by name within the .mpkg VFS.
        val entries = listOf(
            "project.json" to projectJsonBytes,
            imageVPath to previewBytes,
        )

        val totalSize = pack(entries, outputFile)

        WepWawetLogger.proxy("MpkgPacker.packImageWallpaper done",
            "output" to outputFile.name,
            "mpkg_size" to totalSize,
            "vfs_entries" to entries.size)

        // Verify by re-reading the header
        verifyMpkg(outputFile, entries)

        return totalSize
    }

    /**
     * Build a valid project.json for an Image-type wallpaper, using the EXACT
     * schema verified from a real .mpkg file extracted from the WE Android app
     * (uploaded by the user as 2279314480.mpkg).
     *
     * === REAL project.json schema (from 2279314480.mpkg) ===
     * {
     *     "contentrating" : "Mature",          <- we add: "Everyone" (safe default)
     *     "description" : "...",                <- from workshop item
     *     "file" : "scene.json",                <- we use: imageVPath (e.g. "preview.jpg")
     *     "general" : {
     *         "properties" : {
     *             "schemecolor" : {
     *                 "order" : 0,
     *                 "text" : "ui_browse_properties_scheme_color",
     *                 "type" : "color",
     *                 "value" : "0.000 0.000 0.000"
     *             }
     *         }
     *     },
     *     "preview" : "preview.jpg",
     *     "tags" : [ "Anime" ],                <- from workshop item
     *     "title" : "...",                      <- from workshop item
     *     "type" : "scene",                     <- we use: "image" (lowercase, for image type)
     *     "version" : 0,                        <- always 0
     *     "visibility" : "private",            <- always "private" (user-uploaded, not public)
     *     "workshopurl" : "https://..."         <- Steam workshop URL
     * }
     *
     * CRITICAL FINDINGS from the real .mpkg:
     *   1. Magic is "PKGM0014", NOT "PKGM0020" — the phone's mpkgVersion field
     *      in EncAuthRequest is the MAX supported version, but actual files use
     *      the older PKGM0014 format. We match the real file's magic.
     *   2. type is lowercase "scene" / "video" / "image" — NOT "Scene" or "Image"
     *   3. The "general" block has a "properties.schemecolor" sub-object,
     *      NOT "width"/"height"/"orientation"/"background" — those .so string
     *      literals were red herrings (they're used elsewhere in the native code,
     *      not in project.json's general block).
     *   4. Required fields: contentrating, description, file, general,
     *      preview, tags, title, type, version, visibility, workshopurl
     *   5. Uses TAB indentation and " : " separator (cosmetic — Gson's
     *      default formatting works too, but we match the real format for
     *      byte-for-byte compatibility).
     */
    private fun buildProjectJson(
        item: WorkshopItemTyped,
        file: String,
        preview: String,
    ): String {
        // Build the JSON manually with proper TAB indentation and " : " separator
        // to match the real WE .mpkg format byte-for-byte (in case the native
        // parser is whitespace-sensitive — unlikely but safe).
        val title = escapeJsonString(item.title)
        val description = escapeJsonString(item.description.ifBlank { "Sent from WepWawet" })
        val tags = item.tags.joinToString(", ") { "\"${escapeJsonString(it)}\"" }
        val schemecolor = "0.000 0.000 0.000"  // black, safe default

        return buildString {
            append("{\r\n")
            append("\t\"contentrating\" : \"Everyone\",\r\n")
            append("\t\"description\" : \"$description\",\r\n")
            append("\t\"file\" : \"$file\",\r\n")
            append("\t\"general\" : \r\n")
            append("\t{\r\n")
            append("\t\t\"properties\" : \r\n")
            append("\t\t{\r\n")
            append("\t\t\t\"schemecolor\" : \r\n")
            append("\t\t\t{\r\n")
            append("\t\t\t\t\"order\" : 0,\r\n")
            append("\t\t\t\t\"text\" : \"ui_browse_properties_scheme_color\",\r\n")
            append("\t\t\t\t\"type\" : \"color\",\r\n")
            append("\t\t\t\t\"value\" : \"$schemecolor\"\r\n")
            append("\t\t\t}\r\n")
            append("\t\t}\r\n")
            append("\t},\r\n")
            append("\t\"preview\" : \"$preview\",\r\n")
            append("\t\"tags\" : [ $tags ],\r\n")
            append("\t\"title\" : \"$title\",\r\n")
            append("\t\"type\" : \"image\",\r\n")
            append("\t\"version\" : 0,\r\n")
            append("\t\"visibility\" : \"private\",\r\n")
            append("\t\"workshopurl\" : \"https://steamcommunity.com/sharedfiles/filedetails/?id=${item.id}\"\r\n")
            append("}")
    }

    /**
     * Escape a string for JSON string literal (handles \, ", \n, \r, \t, and
     * control chars). CJK characters pass through as UTF-8 bytes.
     */
    private fun escapeJsonString(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u").append("%04x".format(c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    /**
     * Pack a list of (name, bytes) entries into a .mpkg file.
     */
    private fun pack(entries: List<Pair<String, ByteArray>>, outputFile: File): Long {
        // Compute layout: header_size + vfs_table_size + data_section_size
        val headerSize = 4 + 8 + 4  // header_marker(4) + magic(8) + entry_count(4)
        val vfsTableSize = entries.sumOf { (name, _) ->
            4 + name.toByteArray(Charsets.UTF_8).size + 4 + 4  // name_len(4) + name + offset(4) + size(4)
        }
        val dataSectionStart = headerSize + vfsTableSize

        // Compute offsets (relative to data_section_start) and sizes
        val vfsEntries: MutableList<Triple<String, Int, Int>> = mutableListOf()  // (name, offset, size)
        var cursor = 0
        for ((name, blob) in entries) {
            vfsEntries.add(Triple(name, cursor, blob.size))
            cursor += blob.size
        }

        WepWawetLogger.proxyDebug("MpkgPacker layout",
            "header_size" to headerSize,
            "vfs_table_size" to vfsTableSize,
            "data_section_start" to dataSectionStart,
            "total_size" to (dataSectionStart + cursor),
            "entry_count" to entries.size)

        // Assemble the .mpkg
        val out = outputFile.outputStream().buffered()
        try {
            writeLeInt(out, HEADER_MARKER)
            out.write(MAGIC.toByteArray(Charsets.US_ASCII))
            if (MAGIC.length != 8) error("magic must be 8 bytes: '$MAGIC'")
            writeLeInt(out, entries.size)

            // VFS table
            for ((name, offset, size) in vfsEntries) {
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                writeLeInt(out, nameBytes.size)
                out.write(nameBytes)
                writeLeInt(out, offset)
                writeLeInt(out, size)
            }

            // Data section
            for ((_, blob) in entries) {
                out.write(blob)
            }

            out.flush()
        } finally {
            out.close()
        }

        return outputFile.length()
    }

    /**
     * Write a 4-byte little-endian integer to the stream.
     */
    private fun writeLeInt(out: OutputStream, value: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        out.write(buf)
    }

    /**
     * Verify the .mpkg file we just wrote by reading the header back.
     * Catches issues like off-by-one in struct packing early.
     *
     * CRITICAL: every read is bounds-checked to prevent OOM if the file is
     * somehow malformed (e.g. on disk corruption or a packing bug). The
     * previous version had a bug where reading the magic via
     * `String(data, 4, 8, ...)` didn't advance the ByteBuffer position,
     * so the subsequent `buf.int` re-read bytes 4-7 ("PKGM") as the
     * entry count = 1,296,517,968. The for loop then tried to allocate
     * a 808 MB ByteArray on the first iteration → OOM crash.
     */
    private fun verifyMpkg(file: File, expectedEntries: List<Pair<String, ByteArray>>) {
        try {
            val data = file.readBytes()
            if (data.size < 16) {
                WepWawetLogger.proxyWarn("MpkgPacker verify: file too small",
                    "size" to data.size)
                return
            }
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val headerMarker = buf.int  // position 0→4
            // Read magic by ADVANCING the buffer position (not by direct array
            // access, which doesn't move the position and would cause the next
            // buf.int to re-read bytes 4-7 as the entry count).
            val magicBytes = ByteArray(8)
            buf.get(magicBytes)  // position 4→12
            val magic = String(magicBytes, Charsets.US_ASCII)
            val entryCount = buf.int  // position 12→16, NOW reads the real count

            // Sanity-check entryCount before the loop. If it's some absurd
            // number (because the file is corrupt or our packing is wrong),
            // bail out instead of trying to iterate billions of times.
            if (entryCount !in 1..4096) {
                WepWawetLogger.proxyWarn("MpkgPacker verify: implausible entry count",
                    "file" to file.name,
                    "header_marker" to headerMarker,
                    "magic" to magic,
                    "entry_count" to entryCount,
                    "expected" to expectedEntries.size)
                return
            }

            WepWawetLogger.proxy("MpkgPacker verified",
                "file" to file.name,
                "file_size" to data.size,
                "header_marker" to headerMarker,
                "magic" to magic,
                "entry_count" to entryCount,
                "matches_expected" to (headerMarker == HEADER_MARKER && magic == MAGIC && entryCount == expectedEntries.size))

            // Read VFS entries — each one bounds-checked to prevent OOM
            for (i in 0 until entryCount) {
                if (buf.remaining() < 4) {
                    WepWawetLogger.proxyWarn("MpkgPacker verify: truncated at name_len",
                        "index" to i)
                    return
                }
                val nameLen = buf.int
                // Sanity-check name_len. VFS entry names are short UTF-8
                // strings ("project.json", "preview.jpg", etc.) — anything
                // over 256 chars is almost certainly a parsing bug.
                if (nameLen !in 1..256) {
                    WepWawetLogger.proxyWarn("MpkgPacker verify: implausible name_len",
                        "index" to i,
                        "name_len" to nameLen)
                    return
                }
                if (buf.remaining() < nameLen + 8) {
                    WepWawetLogger.proxyWarn("MpkgPacker verify: truncated mid-entry",
                        "index" to i,
                        "name_len" to nameLen,
                        "remaining" to buf.remaining())
                    return
                }
                val nameBytes = ByteArray(nameLen)
                buf.get(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)
                val offset = buf.int
                val size = buf.int
                WepWawetLogger.proxyDebug("MpkgPacker VFS entry",
                    "index" to i,
                    "name" to name,
                    "offset" to offset,
                    "size" to size)
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("MpkgPacker verify failed",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
        }
    }

    // Note: project.json is now built manually in buildProjectJson() to match
    // the EXACT byte format of real .mpkg files (TAB indentation, " : " separator,
    // CRLF line endings). The previous data class approach used Gson's default
    // formatting which produced different whitespace — cosmetic but we match
    // the real format for safety.
}
