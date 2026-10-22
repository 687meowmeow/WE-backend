package com.wepwawet.pairing

import com.google.gson.Gson
import com.google.gson.GsonBuilder
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

    /** Magic = "PKGM" + 4-digit version. Matches TransferService.MPKG_SUPPORT_VERSION. */
    const val MAGIC = "PKGM0020"

    /** u32 LE = 8 (length of the magic, also serves as a sanity check). */
    const val HEADER_MARKER = 8

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

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
            "output" to outputFile.absolutePath)

        // VFS entries: (name, bytes) pairs
        // Order matters: project.json MUST be first (it's looked up by name,
        // and the .so may rely on the first entry being the project manifest).
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
     * Build a valid project.json for an Image-type wallpaper.
     *
     * Schema verified from libscenejni.so string literals:
     *   - description, file, general, image, preview, title, type, website
     *   - general block: width, height, orientation, background, website
     *   - type: "Scene", "Video", or "image" (lowercase for image type)
     */
    private fun buildProjectJson(
        item: WorkshopItemTyped,
        file: String,
        preview: String,
    ): String {
        // Guess resolution from tags (e.g. "1920x1080", "3840x2160")
        val (width, height) = guessResolution(item) ?: Pair(1920, 1080)

        // Build the JSON object manually with Gson to ensure proper escaping
        // of CJK characters and special chars in the title/description.
        val project = ProjectJson(
            title = item.title,
            description = item.description.ifBlank { "Sent from WepWawet" },
            type = "image",  // lowercase per .so
            file = file,
            preview = preview,
            general = GeneralSettings(
                width = width,
                height = height,
                orientation = if (width > height) "landscape" else "portrait",
                background = "#000000",
                website = "https://steamcommunity.com/sharedfiles/filedetails/?id=${item.id}",
            ),
        )
        return gson.toJson(project)
    }

    /**
     * Try to guess the wallpaper resolution from the workshop tags. WE workshop
     * items usually include a tag like "1920x1080" or "3840x2160".
     */
    private fun guessResolution(item: WorkshopItemTyped): Pair<Int, Int>? {
        val pattern = Regex("(\\d{3,5})\\s*[xX×]\\s*(\\d{3,5})")
        for (tag in item.tags) {
            val m = pattern.find(tag) ?: continue
            val w = m.groupValues[1].toIntOrNull() ?: continue
            val h = m.groupValues[2].toIntOrNull() ?: continue
            if (w in 100..8192 && h in 100..8192) return Pair(w, h)
        }
        return null
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
            val headerMarker = buf.int
            val magic = String(data, 4, 8, Charsets.US_ASCII)
            val entryCount = buf.int
            WepWawetLogger.proxy("MpkgPacker verified",
                "file" to file.name,
                "file_size" to data.size,
                "header_marker" to headerMarker,
                "magic" to magic,
                "entry_count" to entryCount,
                "matches_expected" to (headerMarker == HEADER_MARKER && magic == MAGIC && entryCount == expectedEntries.size))

            // Read VFS entries
            for (i in 0 until entryCount) {
                val nameLen = buf.int
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

    /**
     * project.json schema — verified from libscenejni.so string literals
     * (description, file, general, image, preview, title, type, website,
     *  background, height, orientation, width).
     */
    data class ProjectJson(
        val title: String,
        val description: String,
        val type: String,
        val file: String,
        val preview: String,
        val general: GeneralSettings,
    )

    data class GeneralSettings(
        val width: Int,
        val height: Int,
        val orientation: String,
        val background: String,
        val website: String,
    )
}
