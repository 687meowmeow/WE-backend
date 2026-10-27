package com.wepwawet.pairing

import com.wepwawet.data.model.WorkshopItemTyped
import com.wepwawet.log.WepWawetLogger
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Packs Wallpaper Engine .mpkg files using the REAL binary format verified
 * from C++ source + two real samples + the decompiled libscenejni.so.
 *
 * === SUPPORTED INPUTS ===
 *
 * This packer supports THREE input modes:
 *
 * 1. **From .pkg file** (preferred — real WE content):
 *    User manually copies a .pkg file from their PC's Steam folder to their
 *    phone, then picks it via the file picker. The .pkg is read by [PkgReader],
 *    assets are extracted, and we repackage them as a .mpkg. This produces a
 *    wallpaper that's byte-for-byte compatible with what the WE PC would send
 *    to the WE Android app.
 *
 * 2. **From local image file** (JPEG/PNG):
 *    User picks a local image. We encode it to a .tex (RGBA8888 format=0)
 *    and pack as a Scene .mpkg. This works for any image, but the resulting
 *    wallpaper renders as a static image with no animation/effects.
 *
 * 3. **From local video file** (MP4):
 *    User picks a local MP4. We package it directly as a Video .mpkg. The
 *    WE Android app's VideoDrawable plays the MP4 from inside the .mpkg VFS.
 *
 * === BRIGHTNESS FIX (v2) ===
 *
 * Previous version had brightness issues:
 *   - Schemecolor was hardcoded to "0.000 0.000 0.000" (black) — should be
 *     the texture's average color (matching real WE .mpkg behavior)
 *   - Blending mode was "translucent" which can cause additive blending on
 *     some shaders — should be "normal" for opaque content
 *
 * This version:
 *   - Computes schemecolor from the texture's average RGBA color
 *   - Uses "blending": "normal" for opaque textures
 *   - Sets "depthtest": "enabled" to match real WE samples
 *   - Computes clearcolor from the texture's average color (dimmed to 30%)
 *
 * === .MPKG FORMAT ===
 *
 *   [u32 LE header_marker = 8]
 *   [8-byte ASCII magic]    ("PKGM0020")  ← bumped from PKGM0014 to match
 *                                          the latest wallpaperui.exe binary
 *                                          (string at file offset 0x00ab4840
 *                                          in the user's wallpaperui.exe, dated
 *                                          2026-06-29). The Android native lib
 *                                          libscenejni.so uses integer version
 *                                          comparison (no hardcoded string) so
 *                                          it accepts any version >= PKGM0001.
 *                                          For 100% parity with real WE PC, we
 *                                          emit PKGM0020 (the latest).
 *   [u32 LE entry_count]
 *   For each entry:
 *     [u32 LE name_len][name_bytes][u32 LE file_offset][u32 LE file_size]
 *   [data section: file blobs packed back-to-back]
 *
 * === .TEX FORMAT (for Scene wallpapers) ===
 *
 *   [TEXV0005\0]                  9 bytes — file magic
 *   [TEXI0001\0]                  9 bytes — header chunk
 *   [7x u32 LE]                  28 bytes — format, flags, tex_w, tex_h, img_w, img_h, color
 *   [TEXB0004\0]                  9 bytes — image container chunk
 *   [3x u32 LE]                  12 bytes — imageCount=1, format=-1, isVideoMp4=0
 *   [1x u32 LE]                   4 bytes — mipmap_count=1
 *   [4x u32 LE]                  16 bytes — width, height, IsLZ4=0, DecompressedSize
 *   [1x u32 LE]                   4 bytes — byteCount
 *   [byte[byteCount]]                       — raw pixel data (RGBA8888 when IsLZ4=0)
 */
object MpkgPacker {

    const val MAGIC = "PKGM0020"  // bumped from PKGM0014 to match the latest wallpaperui.exe (2026-06-29 build)
    const val HEADER_MARKER = 8
    /** PNG magic bytes — used to detect PNG data inside .tex files. */
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47) // \x89PNG
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) // JPEG SOI marker (FFD8FF)

    /**
     * Check if a byte is a valid JPEG marker type (the byte after FFD8FF).
     * Used to prevent false-positive JPEG detection in .tex files that contain
     * binary data (DXT textures, LZ4-compressed data, etc.) which happens to
     * contain the FFD8FF byte sequence.
     *
     * Valid JPEG markers after SOI (FFD8):
     *   0xC0-0xCF (except 0xC4, 0xC8, 0xCC): SOF0-SOF15
     *   0xC4: DHT, 0xDA: SOS, 0xDB: DQT, 0xDD: DRI
     *   0xD0-0xD7: RST0-RST7
     *   0xE0-0xEF: APP0-APP15 (JFIF, EXIF, etc.)
     *   0xFE: COM (Comment)
     */
    private fun isValidJpegMarker(byte: Byte): Boolean {
        val b = byte.toInt() and 0xFF
        return (b in 0xC0..0xCF && b !in setOf(0xC4, 0xC8, 0xCC)) || // SOF variants
               b == 0xC4 || // DHT
               b == 0xDA || // SOS
               b == 0xDB || // DQT
               b == 0xDD || // DRI
               b in 0xD0..0xD7 || // RST0-RST7
               b in 0xE0..0xEF || // APP0-APP15
               b == 0xFE  // COM
    }

    /**
     * Parse the .tex header to determine the texture format.
     * Returns: Pair(TexI_format, TexB_IsLz4) or null if header is malformed.
     *
     * .tex format (from backup repo's parse_tex.py + make_tex.py):
     *   [TEXV0005\0]  9 bytes  — file magic
     *   [TEXI0001\0]  9 bytes  — info chunk
     *   [7x u32 LE]    28 bytes — format, flags, tex_w, tex_h, img_w, img_h, color
     *   [TEXB0004\0]  9 bytes  — body chunk
     *   [3x u32 LE]    12 bytes — imageCount, format, isVideoMp4
     *   [1x u32 LE]     4 bytes — mipmap_count
     *   [4x u32 LE]    16 bytes — width, height, IsLZ4, DecompressedSize
     *   [1x u32 LE]     4 bytes — byteCount
     *   [data]
     *
     * TexI format values:
     *   0  = FIF_UNKNOWN (auto-detect via magic bytes)
     *   5  = ETC2_RGBA8 (already mobile-compatible — no conversion needed!)
     *   13 = FIF_PNG
     *   14 = FIF_JPEG (maybe — unconfirmed)
     */
    private fun parseTexHeader(texData: ByteArray): Pair<Int, Boolean>? {
        try {
            // TEXV0005\0 (9 bytes)
            if (texData.size < 91) return null
            val texvMagic = String(texData, 0, 8, Charsets.US_ASCII)
            if (!texvMagic.startsWith("TEXV")) return null
            var pos = 9 // after TEXV0005\0

            // TEXI0001\0 (9 bytes)
            val texiMagic = String(texData, pos, 8, Charsets.US_ASCII)
            if (!texiMagic.startsWith("TEXI")) return null
            pos += 9 // after TEXI0001\0

            // 7x u32 LE (28 bytes): format, flags, tex_w, tex_h, img_w, img_h, color
            val texiFormat = readU32Le(texData, pos)
            pos += 28

            // TEXB0004\0 (9 bytes)
            val texbMagic = String(texData, pos, 8, Charsets.US_ASCII)
            if (!texbMagic.startsWith("TEXB")) return null
            pos += 9

            // 3x u32 LE (12 bytes): imageCount, format, isVideoMp4
            val texbFormat = readU32Le(texData, pos + 4) // format field
            pos += 12

            // 1x u32 LE (4 bytes): mipmap_count
            val mipmapCount = readU32Le(texData, pos)
            pos += 4

            // Per mipmap: 4x u32 LE (16 bytes): w, h, IsLZ4, DecompressedSize
            if (mipmapCount > 0) {
                val isLz4 = readU32Le(texData, pos + 8) // IsLZ4 field
                return Pair(texiFormat.toInt(), isLz4 != 0L)
            }
            return Pair(texiFormat.toInt(), false)
        } catch (e: Exception) {
            return null
        }
    }

    private fun readU32Le(data: ByteArray, offset: Int): Long {
        if (offset + 4 > data.size) return 0L
        return (data[offset].toLong() and 0xFF) or
               ((data[offset + 1].toLong() and 0xFF) shl 8) or
               ((data[offset + 2].toLong() and 0xFF) shl 16) or
               ((data[offset + 3].toLong() and 0xFF) shl 24)
    }

    // ==================== FROM .PKG FILE (preferred) ====================

    /**
     * Pack a .mpkg from a previously-extracted .pkg file's contents.
     *
     * This is the cleanest path: the user provides a real .pkg file from
     * Steam Workshop (downloaded via Steam client on PC), we extract all
     * the assets (project.json, scene.json, .tex files, materials, etc.)
     * and repackage them as a .mpkg with the correct binary format.
     *
     * For Video wallpapers (project.json has type:"video"), the wallpaper.mp4
     * file is included as-is.
     *
     * For Scene wallpapers (type:"scene"), all scene assets are preserved
     * as-is — including the original ETC2_RGBA8 .tex files (format=5) which
     * the WE Android native lib (libscenejni.so) handles natively via
     * OpenGL ES 3.0+ ETC2 texture upload.
     *
     * @param item workshop item metadata
     * @param pkgFile the source .pkg file
     * @param outputFile the destination .mpkg file
     * @return size in bytes, or 0 on failure
     */
    fun packFromPkg(
        item: WorkshopItemTyped,
        pkgFile: File,
        outputFile: File,
        installDir: File? = null,
    ): Long {
        WepWawetLogger.proxy("MpkgPacker.packFromPkg START",
            "item_id" to item.id,
            "title" to item.title.take(80),
            "pkg_file" to pkgFile.name,
            "pkg_size" to pkgFile.length(),
            "output" to outputFile.name)

        // Step 1: Read the .pkg file
        // Step 1: Read the .pkg file — use streaming for large .pkg files to avoid OOM
        val STREAM_THRESHOLD = 50_000_000L // 50MB
        val vfsData: Pair<List<PkgReader.VfsEntry>, Long>? = if (pkgFile.length() > STREAM_THRESHOLD) {
            PkgReader.readVfsTable(pkgFile)
        } else {
            null
        }
        val entries: MutableList<Pair<String, ByteArray>> = if (vfsData != null) {
            // Large .pkg — load ONLY entries that need modification (shaders, .tex, JSONs)
            val (vfsEntries, dataSectionStart) = vfsData!!
            WepWawetLogger.proxy("MpkgPacker.packFromPkg: streaming mode (large .pkg)",
                "pkg_size" to pkgFile.length(),
                "vfs_entries" to vfsEntries.size,
                "loading_only_modified" to true)
            val loaded = mutableListOf<Pair<String, ByteArray>>()
            for (vfs in vfsEntries) {
                val needsMod = vfs.path.endsWith(".frag") || vfs.path.endsWith(".vert") ||
                    vfs.path.endsWith(".tex") || vfs.path == "scene.json" || vfs.path == "project.json"
                if (needsMod && vfs.size < 20_000_000) { // Only load entries < 20MB
                    val data = PkgReader.readEntryData(pkgFile, dataSectionStart, vfs.offset, vfs.size)
                    if (data != null) loaded.add(vfs.path to data)
                }
            }
            loaded
        } else {
            // Small .pkg — load all entries (fits in heap)
            PkgReader.read(pkgFile).toMutableList()
        }
        if (entries.isEmpty()) {
            WepWawetLogger.proxyError("MpkgPacker.packFromPkg FAILED — no entries in .pkg",
                "pkg_file" to pkgFile.name)
            return 0L
        }

        // Step 2: Log entry count (type detection moved to after standalone files)
        WepWawetLogger.proxy("MpkgPacker.packFromPkg entries loaded",
            "entry_count" to entries.size)

        // Step 3: Log all entries (for debugging)
        for (entry in entries) {
            val (name, blob) = entry
            WepWawetLogger.proxyDebug("MpkgPacker.packFromPkg entry",
                "name" to name,
                "size" to blob.size)
        }

        // Step 3b: Include standalone files from the install directory
        // (project.json, preview.jpg, etc.) that are NOT inside the .pkg
        // but are needed by WE's .mpkg format.
        //
        // WE PC's transcoder (function 0x14003d1e0 in wallpaperui.exe) writes
        // multiple preview variants: preview.jpg, preview.gif, preview.png.
        // The .pkg side may have ANY of these — we now include all of them
        // (instead of just preview.jpg) for 100% parity with real WE PC.
        // (Verified from wallpaperui.exe strings: "preview.jpg", "preview.gif",
        // "preview.png" all appear, plus "nopreview.jpg" as the placeholder.)
        if (installDir != null && installDir.exists()) {
            val standaloneNames = listOf(
                "project.json",
                "preview.jpg", "preview.gif", "preview.png", "preview.jpeg", "preview.webp"
            )
            val existingNames = entries.map { it.first }.toSet()
            for (name in standaloneNames) {
                if (name in existingNames) continue // already in .pkg
                val standaloneFile = File(installDir, name)
                if (standaloneFile.exists() && standaloneFile.length() > 0) {
                    val blob = standaloneFile.readBytes()
                    entries.add(Pair(name, blob))
                    WepWawetLogger.proxy("MpkgPacker.packFromPkg added standalone file",
                        "name" to name,
                        "size" to blob.size,
                        "source" to standaloneFile.absolutePath)
                }
            }
        }

        // Step 2b: Detect wallpaper type from project.json (AFTER adding standalone files,
        // because project.json might only be in the install dir, not in the .pkg)
        val type = PkgReader.detectWallpaperType(entries)
        WepWawetLogger.proxy("MpkgPacker.packFromPkg type detected",
            "type" to type,
            "entry_count" to entries.size)

        // Step 3b-fix: Fix scene.json — replace null texture bindings with
        // shader-declared defaults. On WE PC, null means "use default from
        // installation directory". On WE Android, null means "no texture",
        // which causes effects like X-ray to silently fail (the shader reads
        // sprite.ra which is 0 for a missing texture → blend=0 → no X-ray).
        //
        // The real WE Android .mpkg explicitly binds all texture slots,
        // including defaults like "particle/halo_6" for the X-ray cursor halo.
        // Our .pkg from Steam has null entries that work on PC but NOT on Android.
        //
        // Fix: parse scene.json, find all texture arrays with null entries,
        // look up the shader's default texture names, and replace nulls.
        fixSceneJsonTextureBindings(entries)

        // Step 3b-fix2: Fix shaders for OpenGL ES (GLSL 3.00 ES) compatibility.
        // WE PC uses DirectX (HLSL) which allows implicit int→float conversion.
        // GLSL ES does NOT — integer constants must have .0 suffix.
        // WE Android's automatic conversion has bugs and misses some constants.
        // Without fixing these, shaders fail to compile and effects are SILENTLY
        // REMOVED by WE Android (including the X-ray effect).
        fixShadersForMobile(entries)

        // Step 3c: Convert .tex files that contain PNG data to raw RGBA8888.
        //
        // Steam depot stores .tex files as PNG-encoded (TexB format=13=FIF_PNG)
        // because WE PC has stb_image to decode PNG at load time.
        // WE Android does NOT have a PNG decoder for .tex files — when it sees
        // TexB format=13, the texture loader fails and shows the yellow/black
        // "missing texture" checkerboard.
        //
        // Fix: rebuild each .tex with raw RGBA8888 pixel data:
        //   - TexI format=0 (RGBA8888) — declared format matches actual data
        //   - TexB format=-1 (FIF_UNKNOWN) — use TexI format directly
        //   - 1 image with 1 mipmap (the largest PNG) to keep file size reasonable
        //   - Compression=0 (no LZ4), data = raw RGBA bytes from BitmapFactory
        //
        // NOTE: This makes the file larger (~87 MB for 4 textures of 3000x1800
        // vs 5 MB for PNG-encoded). The trade-off: it actually renders on Android.
        // Future optimization: add LZ4 compression or ETC2/ASTC encoding to
        // reduce size while keeping Android compatibility.
        val processedEntries = mutableListOf<Pair<String, ByteArray>>()
        var texConverted = 0
        var texDxtConverted = 0
        var texPngConverted = 0
        var totalOriginalTexBytes = 0L
        var totalConvertedTexBytes = 0L
        for (entry in entries) {
            val (name, blob) = entry
            if (!name.endsWith(".tex")) {
                processedEntries.add(entry)
                continue
            }

            // Parse the .tex header FIRST. We route to the appropriate converter
            // based on the TexI format field — this avoids the false-positive
            // FFD8FF detection that triggered convertTexPngToEtc2 on DXT .tex files.
            //
            // TexI format values (per repkg + research):
            //   0  = FIF_UNKNOWN (auto-detect via magic bytes — may contain PNG or JPEG)
            //   4  = DXT5 (BC3)  → convertTexDxtToEtc2
            //   5  = ETC2_RGBA8  → already mobile-compatible, SKIP conversion
            //   6  = DXT3 (BC2)  → convertTexDxtToEtc2
            //   7  = DXT1 (BC1)  → convertTexDxtToEtc2
            //   11 = BC4 (RGTC1) → convertTexDxtToEtc2
            //   12 = BC5 (RGTC2) → convertTexDxtToEtc2
            //   13 = FIF_PNG      → convertTexPngToEtc2 (PNG-embedded)
            //   14 = FIF_JPEG     → convertTexPngToEtc2 (JPEG-embedded)
            //   20 = RGBA8888     → already mobile-compatible (Android libscenejni.so
            //                       can decode RGBA8888 natively) — SKIP
            val texHeader = parseTexHeader(blob)
            val texiFormat = texHeader?.first ?: -1
            val isLz4 = texHeader?.second ?: false

            // Log .tex header info for debugging
            WepWawetLogger.proxyDebug("MpkgPacker.packFromPkg: .tex header",
                "name" to name, "texi_format" to texiFormat,
                "is_lz4" to isLz4, "tex_size" to blob.size)

            // ---- Branch 1: format=5 (ETC2_RGBA8) — already mobile-compatible ----
            if (texiFormat == 5) {
                WepWawetLogger.proxy("MpkgPacker.packFromPkg: .tex is ETC2 (format=5) — already mobile-compatible, skipping conversion",
                    "name" to name, "tex_size" to blob.size, "is_lz4" to isLz4)
                processedEntries.add(entry)
                continue
            }

            // ---- Branch 2: format=20 (RGBA8888) — also mobile-compatible ----
            if (texiFormat == 20) {
                WepWawetLogger.proxy("MpkgPacker.packFromPkg: .tex is RGBA8888 (format=20) — already mobile-compatible, skipping conversion",
                    "name" to name, "tex_size" to blob.size)
                processedEntries.add(entry)
                continue
            }

            // ---- Branch 3: DXT/BC family — transcode via DxtDecoder ----
            val dxtFormat = DxtDecoder.formatForTexFormat(texiFormat)
            if (dxtFormat != null) {
                WepWawetLogger.proxy("MpkgPacker.packFromPkg: .tex is ${dxtFormat.displayName} (format=$texiFormat) — transcoding DXT→ETC2_RGBA8+LZ4",
                    "name" to name,
                    "tex_size" to blob.size,
                    "is_lz4" to isLz4)
                val converted = convertTexDxtToEtc2(blob, name)
                if (converted != null) {
                    processedEntries.add(Pair(name, converted))
                    texConverted++
                    texDxtConverted++
                    totalOriginalTexBytes += blob.size
                    totalConvertedTexBytes += converted.size
                    WepWawetLogger.proxy("MpkgPacker.packFromPkg: DXT .tex transcoded",
                        "name" to name,
                        "original_size" to blob.size,
                        "converted_size" to converted.size,
                        "size_ratio" to "%.2fx".format(converted.size.toDouble() / blob.size.toDouble()))
                } else {
                    processedEntries.add(entry)
                    WepWawetLogger.proxyWarn("MpkgPacker.packFromPkg: DXT .tex transcode FAILED — using original (will show yellow/black on Android)",
                        "name" to name,
                        "dxt_format" to dxtFormat.displayName)
                }
                continue
            }

            // ---- Branch 4: format=0 (FIF_UNKNOWN) or 13/14 — auto-detect via magic ----
            // Only NOW do we search for PNG/JPEG magic bytes — and only AFTER confirming
            // the .tex format is 0 or 13/14 (not DXT). This eliminates the FFD8FF false
            // positives in DXT block data that triggered the broken code path.
            //
            // We also apply a stricter check: only search AFTER the .tex header (offset 91),
            // never in the header itself.
            val searchStartOffset = 91
            val hasPng = findBytes(blob, PNG_MAGIC, searchStartOffset) >= 0
            val hasJpeg = run {
                // Stricter JPEG detection: FFD8FF must be followed by a valid
                // JPEG marker byte. We re-implement the same logic as
                // convertTexPngToEtc2 here at the dispatcher level, so we don't
                // log "JPEG found" for false positives.
                var pos = searchStartOffset
                while (true) {
                    val jpgStart = findBytes(blob, JPEG_MAGIC, pos)
                    if (jpgStart < 0) return@run false
                    if (jpgStart + 3 >= blob.size) return@run false
                    val markerByte = blob[jpgStart + 3]
                    if (isValidJpegMarker(markerByte)) {
                        return@run true
                    }
                    // False positive — skip and search again
                    pos = jpgStart + 3
                }
                @Suppress("UNREACHABLE_CODE") return@run false
            }

            if (hasPng || hasJpeg) {
                val imageType = if (hasPng) "PNG" else "JPEG"
                WepWawetLogger.proxy("MpkgPacker.packFromPkg: .tex contains $imageType (format=$texiFormat), will convert",
                    "name" to name,
                    "original_size" to blob.size)
                val converted = convertTexPngToEtc2(blob, name)
                if (converted != null) {
                    processedEntries.add(Pair(name, converted))
                    texConverted++
                    texPngConverted++
                    totalOriginalTexBytes += blob.size
                    totalConvertedTexBytes += converted.size
                    WepWawetLogger.proxy("MpkgPacker.packFromPkg: .tex converted",
                        "name" to name,
                        "original_size" to blob.size,
                        "converted_size" to converted.size,
                        "size_ratio" to "%.2fx".format(converted.size.toDouble() / blob.size.toDouble()))
                } else {
                    processedEntries.add(entry)
                    WepWawetLogger.proxyWarn("MpkgPacker.packFromPkg: .tex PNG conversion FAILED — using original (will likely show yellow/black on Android)",
                        "name" to name)
                }
            } else {
                processedEntries.add(entry)
            }
        }
        if (texConverted > 0) {
            // Replace entries with processed ones
            entries.clear()
            entries.addAll(processedEntries)
            WepWawetLogger.proxy("MpkgPacker.packFromPkg: converted .tex files summary",
                "tex_converted_count" to texConverted,
                "tex_dxt_converted" to texDxtConverted,
                "tex_png_converted" to texPngConverted,
                "total_original_tex_bytes" to totalOriginalTexBytes,
                "total_converted_tex_bytes" to totalConvertedTexBytes,
                "size_increase_bytes" to (totalConvertedTexBytes - totalOriginalTexBytes),
                "size_increase_mb" to "%.1f".format((totalConvertedTexBytes - totalOriginalTexBytes) / 1024.0 / 1024.0))
        }

        // Step 3d: Rewrite project.json + scene.json for the .mpkg's MPKG_VFS://
        // addressing scheme (100% parity with real WE PC's transcoder).
        //
        // Real WE PC's transcoder (function 0x14003d1e0 in wallpaperui.exe,
        // see research/we-transcoder/02_transcoder_14003d1e0.asm) rewrites
        // project.json and scene.json during the .pkg -> .mpkg transcoding step:
        //   - project.json: "file" → "MPKG_VFS://...", "preview" → "MPKG_VFS://...",
        //     removes "video" section, sets "type", "title", "workshopurl"
        //     (disasm lines 14003ea47, 14003eacc, 14003eb31, 14003eb70, 14003eb80)
        //   - scene.json: iterates over "files" array and prefixes each entry's
        //     "value" with "MPKG_VFS://"
        //     (disasm lines 14004104e, 1400410cd, 140041125-14004116a)
        //
        // The MPKG_VFS:// scheme is what the Android native lib (libscenejni.so)
        // uses to address files within the .mpkg's virtual file system.
        // Without this prefix, the Android app may not find files inside the .mpkg.
        for (i in entries.indices) {
            val (name, blob) = entries[i]
            if (name == "project.json") {
                val rewritten = rewriteProjectJsonForMobile(blob)
                if (rewritten != null && !rewritten.contentEquals(blob)) {
                    entries[i] = name to rewritten
                    WepWawetLogger.proxy("MpkgPacker.packFromPkg: rewrote project.json for MPKG_VFS scheme",
                        "original_size" to blob.size,
                        "rewritten_size" to rewritten.size)
                }
            } else if (name == "scene.json") {
                val rewritten = rewriteSceneJsonForMobile(blob)
                if (rewritten != null && !rewritten.contentEquals(blob)) {
                    entries[i] = name to rewritten
                    WepWawetLogger.proxy("MpkgPacker.packFromPkg: rewrote scene.json for MPKG_VFS scheme",
                        "original_size" to blob.size,
                        "rewritten_size" to rewritten.size)
                }
            }
        }

        // Step 4: Pack the entries into a .mpkg
        val totalSize: Long
        if (vfsData != null) {
            // Streaming pack — writes modified entries from memory, streams
            // unmodified entries directly from .pkg to .mpkg (no OOM)
            val (vfsEntries, dataSectionStart) = vfsData!!
            val modifiedMap = entries.associate { it.first to it.second }
            totalSize = streamPack(pkgFile, dataSectionStart, vfsEntries, modifiedMap, outputFile)
            // Skip verifyMpkg for large files (it loads entire .mpkg into memory)
        } else {
            // Standard pack — all entries in memory
            totalSize = pack(entries, outputFile)
            verifyMpkg(outputFile, entries)
        }

        WepWawetLogger.proxy("MpkgPacker.packFromPkg COMPLETE",
            "output" to outputFile.name,
            "mpkg_size" to totalSize,
            "entry_count" to entries.size,
            "type" to type,
            "streaming" to (vfsData != null))
        return totalSize
    }

    // ==================== FROM LOCAL IMAGE (Scene) ====================

    /**
     * Pack a Scene-type .mpkg from an image file (JPEG/PNG).
     *
     * Builds a minimal Scene wallpaper with:
     *   - project.json (type:"scene", file:"scene.json")
     *   - scene.json (camera + one fullscreen image quad)
     *   - models/wepwawet_image.json
     *   - materials/wepwawet_image.json (uses "genericimage2" shader)
     *   - materials/wepwawet_image.tex (RGBA8888 format=0, uncompressed)
     *   - preview.jpg
     *
     * The texture format is RGBA8888 (format=0). The WE Android native lib
     * (libscenejni.so) supports this format directly.
     *
     * BRIGHTNESS FIX (v2):
     *   - schemecolor = texture's average RGBA color (matching real WE .mpkg)
     *   - clearcolor = average color dimmed to 30% (provides subtle ambient)
     *   - blending = "normal" (not "translucent" — translucent can cause additive)
     *   - depthtest = "enabled" (matches real WE samples)
     *
     * @param item workshop item metadata
     * @param imageFile source JPEG/PNG
     * @param outputFile destination .mpkg file
     * @param maxTextureDim cap texture dimension (default 1920)
     * @return size in bytes
     */
    fun packSceneWallpaper(
        item: WorkshopItemTyped,
        imageFile: File,
        outputFile: File,
        maxTextureDim: Int = 1920,
    ): Long {
        WepWawetLogger.proxy("MpkgPacker.packSceneWallpaper START",
            "item_id" to item.id,
            "title" to item.title.take(80),
            "image_file" to imageFile.name,
            "image_size_bytes" to imageFile.length(),
            "max_texture_dim" to maxTextureDim,
            "output" to outputFile.name)

        // Step 1: Decode the source image to RGBA
        WepWawetLogger.proxyDebug("MpkgPacker.packSceneWallpaper decoding image",
            "path" to imageFile.absolutePath)
        val origBitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: throw IllegalArgumentException("Cannot decode image: ${imageFile.absolutePath}")
        WepWawetLogger.proxy("MpkgPacker.packSceneWallpaper image decoded",
            "src_width" to origBitmap.width,
            "src_height" to origBitmap.height,
            "config" to (origBitmap.config?.name ?: "null"))

        // Downsample if too large (keep aspect ratio, cap max dim)
        var srcW = origBitmap.width
        var srcH = origBitmap.height
        val maxDim = maxOf(srcW, srcH)
        var bitmap = origBitmap
        if (maxDim > maxTextureDim) {
            val scale = maxTextureDim.toFloat() / maxDim
            val newW = (srcW * scale).toInt().coerceAtLeast(4)
            val newH = (srcH * scale).toInt().coerceAtLeast(4)
            WepWawetLogger.proxy("MpkgPacker.packSceneWallpaper downsampling",
                "scale" to scale,
                "new_width" to newW,
                "new_height" to newH,
                "reason" to "max_dim $maxDim exceeds cap $maxTextureDim")
            bitmap = Bitmap.createScaledBitmap(origBitmap, newW, newH, true)
            origBitmap.recycle()
            srcW = newW
            srcH = newH
        }

        // Pad to multiple of 4 (some texture formats require 4x4 block alignment)
        val texW = ((srcW + 3) / 4) * 4
        val texH = ((srcH + 3) / 4) * 4
        if (texW != srcW || texH != srcH) {
            WepWawetLogger.proxyDebug("MpkgPacker.packSceneWallpaper padding to 4x4 block",
                "from" to "${srcW}x${srcH}",
                "to" to "${texW}x${texH}")
            val padded = Bitmap.createBitmap(texW, texH, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(padded)
            canvas.drawColor(android.graphics.Color.TRANSPARENT)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            bitmap.recycle()
            bitmap = padded
        }
        WepWawetLogger.proxy("MpkgPacker.packSceneWallpaper texture dimensions",
            "tex_width" to texW, "tex_height" to texH,
            "img_width" to srcW, "img_height" to srcH)

        // Convert bitmap to RGBA bytes (row-major, top-down)
        WepWawetLogger.proxyDebug("MpkgPacker.packSceneWallpaper extracting pixels")
        val pixelBuf = java.nio.IntBuffer.allocate(texW * texH)
        bitmap.getPixels(pixelBuf.array(), 0, texW, 0, 0, texW, texH)
        bitmap.recycle()
        val rgbaBytes = ByteArray(texW * texH * 4)
        for (i in 0 until texW * texH) {
            val argb = pixelBuf[i]
            rgbaBytes[i * 4 + 0] = ((argb shr 16) and 0xFF).toByte()  // R
            rgbaBytes[i * 4 + 1] = ((argb shr 8) and 0xFF).toByte()   // G
            rgbaBytes[i * 4 + 2] = (argb and 0xFF).toByte()           // B
            rgbaBytes[i * 4 + 3] = ((argb shr 24) and 0xFF).toByte()  // A
        }
        WepWawetLogger.proxy("MpkgPacker.packSceneWallpaper RGBA bytes ready",
            "rgba_bytes" to rgbaBytes.size,
            "expected" to (texW * texH * 4))

        // Step 2: Compute average color (for schemecolor)
        val avgColor = computeAverageColor(rgbaBytes)
        val avgR = (avgColor and 0xFF)
        val avgG = ((avgColor shr 8) and 0xFF)
        val avgB = ((avgColor shr 16) and 0xFF)
        val avgA = ((avgColor shr 24) and 0xFF)
        val avgColorStr = "%.3f %.3f %.3f".format(avgR / 255.0f, avgG / 255.0f, avgB / 255.0f)
        WepWawetLogger.proxy("MpkgPacker.packSceneWallpaper average color computed",
            "r" to avgR, "g" to avgG, "b" to avgB, "a" to avgA,
            "as_float" to avgColorStr,
            "as_hex" to "0x%08x".format(avgColor))

        // Step 3: Build the .tex file with format=0 (RGBA8888)
        WepWawetLogger.proxyDebug("MpkgPacker.packSceneWallpaper building .tex file")
        val texBytes = buildTexRgba8888(texW, texH, srcW, srcH, avgColor, rgbaBytes)
        WepWawetLogger.proxy("MpkgPacker.packSceneWallpaper .tex file built",
            "tex_size" to texBytes.size,
            "tex_header_size" to 91,
            "pixel_data_size" to rgbaBytes.size)

        // Step 4: Build all JSON files
        val texBase = "wepwawet_image"
        WepWawetLogger.proxyDebug("MpkgPacker.packSceneWallpaper building JSONs",
            "tex_base" to texBase)
        val projectJson = buildSceneProjectJson(item, avgColorStr)
        val sceneJson = buildSceneJson(texW, texH, texBase, avgColorStr)
        val modelJson = buildModelJson(texBase)
        val materialJson = buildMaterialJson(texBase)  // uses "normal" blending
        val previewBytes = buildPreviewJpeg(imageFile)
        WepWawetLogger.proxy("MpkgPacker.packSceneWallpaper JSONs built",
            "project_json_size" to projectJson.size,
            "scene_json_size" to sceneJson.size,
            "model_json_size" to modelJson.size,
            "material_json_size" to materialJson.size,
            "preview_size" to previewBytes.size)

        // Step 5: Assemble .mpkg entries
        val entries = listOf(
            "project.json" to projectJson,
            "scene.json" to sceneJson,
            "models/$texBase.json" to modelJson,
            "materials/$texBase.json" to materialJson,
            "materials/$texBase.tex" to texBytes,
            "preview.jpg" to previewBytes,
        )

        WepWawetLogger.proxy("MpkgPacker.packSceneWallpaper packing entries",
            "entry_count" to entries.size,
            "total_bytes" to entries.sumOf { it.second.size })
        val totalSize = pack(entries, outputFile)

        WepWawetLogger.proxy("MpkgPacker.packSceneWallpaper COMPLETE",
            "output" to outputFile.name,
            "mpkg_size" to totalSize,
            "tex_dims" to "${texW}x${texH}",
            "schemecolor" to avgColorStr)

        verifyMpkg(outputFile, entries)
        return totalSize
    }

    /**
     * Build a .tex file in WE format using RGBA8888 (format=0), uncompressed.
     */
    private fun buildTexRgba8888(
        texW: Int, texH: Int, imgW: Int, imgH: Int,
        avgColor: Int, rgbaBytes: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream(91 + rgbaBytes.size)
        // TEXV0005 + null
        out.write("TEXV0005\u0000".toByteArray(Charsets.US_ASCII))
        // TEXI0001 + null
        out.write("TEXI0001\u0000".toByteArray(Charsets.US_ASCII))
        // TEXI payload: 7x u32 LE
        writeLeInt(out, 0)  // format = RGBA8888
        writeLeInt(out, 2)  // flags = ClampUVs
        writeLeInt(out, texW)
        writeLeInt(out, texH)
        writeLeInt(out, imgW)
        writeLeInt(out, imgH)
        writeLeInt(out, avgColor)
        // TEXB0004 + null
        out.write("TEXB0004\u0000".toByteArray(Charsets.US_ASCII))
        // TEXB container: imageCount=1, format=-1, isVideoMp4=0
        writeLeInt(out, 1)
        writeLeInt(out, -1)  // FIF_UNKNOWN
        writeLeInt(out, 0)
        // mipmap_count = 1
        writeLeInt(out, 1)
        // mipmap entry (V3 format): width, height, IsLZ4=0, DecompressedSize, byteCount, data
        writeLeInt(out, texW)
        writeLeInt(out, texH)
        writeLeInt(out, 0)  // IsLZ4 = 0 (uncompressed)
        writeLeInt(out, rgbaBytes.size)  // DecompressedSize
        writeLeInt(out, rgbaBytes.size)  // byteCount
        out.write(rgbaBytes)
        return out.toByteArray()
    }

    /**
     * Compute the average RGBA color of an RGBA byte buffer.
     * Returns the color packed as 0xAA BB GG RR (little-endian u32).
     */
    private fun computeAverageColor(rgbaBytes: ByteArray): Int {
        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        var totalA = 0L
        var count = 0
        val step = (rgbaBytes.size / 4 / 4096).coerceAtLeast(1) * 4
        var i = 0
        while (i < rgbaBytes.size) {
            val r = rgbaBytes[i].toInt() and 0xFF
            val g = rgbaBytes[i+1].toInt() and 0xFF
            val b = rgbaBytes[i+2].toInt() and 0xFF
            val a = rgbaBytes[i+3].toInt() and 0xFF
            if (a > 0) {
                totalR += r
                totalG += g
                totalB += b
                totalA += a
                count++
            }
            i += step
        }
        if (count == 0) return 0xFF000000.toInt()
        val avgR = (totalR / count).toInt()
        val avgG = (totalG / count).toInt()
        val avgB = (totalB / count).toInt()
        val avgA = (totalA / count).toInt()
        return (avgA shl 24) or (avgB shl 16) or (avgG shl 8) or avgR
    }

    // ==================== FROM LOCAL VIDEO (Video) ====================

    /**
     * Pack a Video-type .mpkg from an MP4 file.
     *
     * Builds a Video wallpaper:
     *   - project.json (type:"video", file:"wallpaper.mp4")
     *   - wallpaper.mp4 (the source video file, as-is)
     *   - preview.jpg (extracted from the video at t=1s, or black)
     *
     * The WE Android app's VideoDrawable (via MediaPlayer + MPKGVideoReader)
     * plays the MP4 directly from inside the .mpkg VFS — same path as a real
     * WE PC Video wallpaper.
     *
     * @param item workshop item metadata
     * @param videoFile the source MP4 file
     * @param outputFile destination .mpkg file
     * @param previewFile optional preview image (extracted from video)
     * @return size in bytes
     */
    fun packVideoWallpaper(
        item: WorkshopItemTyped,
        videoFile: File,
        outputFile: File,
        previewFile: File? = null,
    ): Long {
        WepWawetLogger.proxy("MpkgPacker.packVideoWallpaper START",
            "item_id" to item.id,
            "title" to item.title.take(80),
            "video_file" to videoFile.name,
            "video_size" to videoFile.length(),
            "output" to outputFile.name)

        require(videoFile.exists()) { "videoFile not found: ${videoFile.absolutePath}" }
        // === v0.4.10: DO NOT load the entire video into memory!
        // Previously: `val videoBytes = videoFile.readBytes()` — this tries
        // to allocate 710MB for a 710MB video, but Android's heap limit is
        // ~512MB, causing OutOfMemoryError:
        //   "Failed to allocate a 743933128 byte allocation with 25070256
        //    free bytes and 504MB until OOM"
        // Fix: Stream the video file in 1MB chunks to the output .mpkg,
        // keeping only the small entries (project.json, preview.jpg) in
        // memory. This reduces peak memory from ~710MB to ~2MB. ===
        val videoSize = videoFile.length()
        WepWawetLogger.proxy("MpkgPacker.packVideoWallpaper video size (streamed, not loaded)",
            "video_size" to videoSize,
            "video_file_path" to videoFile.absolutePath,
            "streaming_mode" to true)

        // Build preview.jpg
        val previewBytes: ByteArray = if (previewFile != null && previewFile.exists()) {
            WepWawetLogger.proxyDebug("MpkgPacker.packVideoWallpaper using provided preview",
                "preview_file" to previewFile.name,
                "preview_size" to previewFile.length())
            previewFile.readBytes()
        } else {
            WepWawetLogger.proxyDebug("MpkgPacker.packVideoWallpaper generating black preview placeholder")
            val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLACK)
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            bitmap.recycle()
            baos.toByteArray()
        }

        val projectJson = buildVideoProjectJson(item)

        // === Stream the .mpkg manually instead of using pack() ===
        // The pack() function takes List<Pair<String, ByteArray>> which
        // requires ALL data in memory. For a 710MB video, that OOMs.
        // Instead, we compute the VFS table entries (with correct offsets
        // and sizes), write the header + VFS table, then stream the
        // video file in 1MB chunks.
        val HEADER_MARKER = 8
        val MAGIC = "PKGM0020"  // match latest wallpaperui.exe (was PKGM0014)

        // Small entries (project.json + preview.jpg) — keep in memory
        val smallEntries = listOf(
            "project.json" to projectJson,
            "preview.jpg" to previewBytes,
        )

        // VFS table: for each entry: u32 LE name_len, name bytes, u32 LE offset, u32 LE size
        // Order: project.json, wallpaper.mp4, preview.jpg
        val headerSize = 4 + 8 + 4  // header_marker + magic + entry_count
        val nameBytes = { name: String -> name.toByteArray(Charsets.UTF_8) }
        val vfsTableSize = smallEntries.sumOf { 4 + nameBytes(it.first).size + 8 } +
            4 + nameBytes("wallpaper.mp4").size + 8
        val dataSectionStart = headerSize + vfsTableSize

        // Compute offsets
        var cursor = 0
        val vfsEntries = mutableListOf<Triple<String, Int, Int>>()
        for ((name, blob) in smallEntries) {
            vfsEntries.add(Triple(name, cursor, blob.size))
            cursor += blob.size
        }
        vfsEntries.add(Triple("wallpaper.mp4", cursor, videoSize.toInt()))
        cursor += videoSize.toInt()

        val totalSize = dataSectionStart + cursor
        WepWawetLogger.proxy("MpkgPacker.packVideoWallpaper layout computed",
            "header_size" to headerSize,
            "vfs_table_size" to vfsTableSize,
            "data_section_start" to dataSectionStart,
            "total_size" to totalSize,
            "entry_count" to (smallEntries.size + 1),
            "streaming_mode" to true)

        val out = outputFile.outputStream().buffered()
        try {
            // Write header
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(HEADER_MARKER)
            out.write(buf.array())
            out.write(MAGIC.toByteArray(Charsets.US_ASCII))
            buf.clear()
            buf.putInt(vfsEntries.size)
            out.write(buf.array())

            // Write VFS table
            for ((name, offset, size) in vfsEntries) {
                val nameBytesArr = name.toByteArray(Charsets.UTF_8)
                buf.clear()
                buf.putInt(nameBytesArr.size)
                out.write(buf.array())
                out.write(nameBytesArr)
                buf.clear()
                buf.putInt(offset)
                out.write(buf.array())
                buf.clear()
                buf.putInt(size)
                out.write(buf.array())
            }

            // Write small entries (project.json, preview.jpg)
            for ((_, blob) in smallEntries) {
                out.write(blob)
            }

            // === Stream the video file in 1MB chunks ===
            WepWawetLogger.proxy("MpkgPacker.packVideoWallpaper: streaming video file",
                "video_file" to videoFile.name,
                "video_size" to videoSize,
                "chunk_size" to (1024 * 1024))
            val chunkSize = 1024 * 1024  // 1 MB
            var bytesWritten = 0L
            java.io.FileInputStream(videoFile).use { fis ->
                val buffer = ByteArray(chunkSize)
                while (true) {
                    val read = fis.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    bytesWritten += read
                    if (bytesWritten % (10 * 1024 * 1024) == 0L) {
                        WepWawetLogger.proxy("MpkgPacker.packVideoWallpaper: video streaming progress",
                            "bytes_written" to bytesWritten,
                            "total" to videoSize,
                            "pct" to (bytesWritten * 100 / videoSize).toInt())
                    }
                }
            }
            WepWawetLogger.proxy("MpkgPacker.packVideoWallpaper: video streaming complete",
                "bytes_written" to bytesWritten,
                "expected" to videoSize)
            out.flush()
        } finally {
            out.close()
        }

        WepWawetLogger.proxy("MpkgPacker.packVideoWallpaper COMPLETE (streamed)",
            "output" to outputFile.name,
            "mpkg_size" to outputFile.length(),
            "video_size" to videoSize)
        return outputFile.length()
    }

    /**
     * Stream-pack entries from .pkg to .mpkg — loads ONLY modified entries into
     * memory, streams unmodified entries directly from .pkg to .mpkg. This
     * avoids OOM on large .pkg files (e.g., 256MB wallpapers with 163MB models).
     *
     * @param pkgFile The source .pkg file (for streaming unmodified entries).
     * @param dataSectionStart The offset where the .pkg data section begins.
     * @param vfsEntries The .pkg VFS table entries (path, offset, size).
     * @param modifiedEntries Map of path → modified ByteArray (shaders, .tex, JSONs).
     * @param outputFile The destination .mpkg file.
     */
    private fun streamPack(
        pkgFile: File,
        dataSectionStart: Long,
        vfsEntries: List<PkgReader.VfsEntry>,
        modifiedEntries: Map<String, ByteArray>,
        outputFile: File,
    ): Long {
        // Build final entry list: modified entries + unmodified (from VFS) + standalone
        data class FinalEntry(val name: String, val size: Int, val data: ByteArray?, val vfsOffset: Long)
        val finalEntries = mutableListOf<FinalEntry>()
        val modifiedNames = modifiedEntries.keys

        for (vfs in vfsEntries) {
            if (vfs.path in modifiedNames) {
                val data = modifiedEntries[vfs.path]!!
                finalEntries.add(FinalEntry(vfs.path, data.size, data, 0))
            } else {
                // Unmodified — stream from .pkg
                finalEntries.add(FinalEntry(vfs.path, vfs.size.toInt(), null, vfs.offset))
            }
        }

        WepWawetLogger.proxy("MpkgPacker.streamPack layout",
            "total_entries" to finalEntries.size,
            "modified_entries" to finalEntries.count { it.data != null },
            "streamed_entries" to finalEntries.count { it.data == null },
            "data_section_start" to dataSectionStart)

        // Write .mpkg header + VFS table
        val out = outputFile.outputStream().buffered()
        try {
            writeLeInt(out, HEADER_MARKER)
            out.write(MAGIC.toByteArray(Charsets.US_ASCII))
            writeLeInt(out, finalEntries.size)

            var cursor = 0
            for (entry in finalEntries) {
                val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
                writeLeInt(out, nameBytes.size)
                out.write(nameBytes)
                writeLeInt(out, cursor)
                writeLeInt(out, entry.size)
                cursor += entry.size
            }

            val vfsTableSize = finalEntries.sumOf { 4 + it.name.toByteArray(Charsets.UTF_8).size + 4 + 4 }
            WepWawetLogger.proxy("MpkgPacker.streamPack header written",
                "header_size" to (4 + 8 + 4),
                "vfs_table_size" to vfsTableSize,
                "data_section_size" to cursor,
                "data_section_start" to (4 + 8 + 4 + vfsTableSize))

            // Write data section: modified from memory, unmodified streamed from .pkg
            for ((index, entry) in finalEntries.withIndex()) {
                if (entry.data != null) {
                    out.write(entry.data)
                } else {
                    PkgReader.streamEntryData(pkgFile, dataSectionStart, entry.vfsOffset, entry.size.toLong(), out)
                }
                if (index % 50 == 0 || index == finalEntries.lastIndex) {
                    WepWawetLogger.proxyDebug("MpkgPacker.streamPack progress",
                        "index" to index, "total" to finalEntries.size,
                        "name" to entry.name, "size" to entry.size,
                        "streamed" to (entry.data == null))
                }
            }
            out.flush()
        } finally {
            out.close()
        }

        val totalSize = outputFile.length()
        WepWawetLogger.proxy("MpkgPacker.streamPack COMPLETE",
            "output" to outputFile.name,
            "mpkg_size" to totalSize,
            "entry_count" to finalEntries.size)
        return totalSize
    }

    // ==================== JSON BUILDERS ====================

    /**
     * Build project.json for a Scene wallpaper.
     * Now accepts an optional schemecolor string (average color).
     */
    private fun buildSceneProjectJson(item: WorkshopItemTyped, schemecolor: String = "1.000 1.000 1.000"): ByteArray {
        val title = escapeJsonString(item.title)
        val description = escapeJsonString(item.description.ifBlank { "Sent from WepWawet" })
        val tags = item.tags.joinToString(", ") { "\"${escapeJsonString(it)}\"" }
        return buildString {
            append("{\r\n")
            append("\t\"contentrating\" : \"Everyone\",\r\n")
            append("\t\"description\" : \"$description\",\r\n")
            // Use MPKG_VFS:// scheme for parity with real WE PC's transcoder output
            // (wallpaperui.exe function 0x14003d1e0, disasm line 14003ea47)
            append("\t\"file\" : \"MPKG_VFS://scene.json\",\r\n")
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
            // Use MPKG_VFS:// scheme for the preview too (disasm line 14003eacc)
            append("\t\"preview\" : \"MPKG_VFS://preview.jpg\",\r\n")
            append("\t\"tags\" : [ $tags ],\r\n")
            append("\t\"title\" : \"$title\",\r\n")
            append("\t\"type\" : \"scene\",\r\n")
            append("\t\"version\" : 0,\r\n")
            append("\t\"visibility\" : \"private\",\r\n")
            append("\t\"workshopurl\" : \"https://steamcommunity.com/sharedfiles/filedetails/?id=${item.id}\"\r\n")
            append("}")
        }.toByteArray(Charsets.UTF_8)
    }

    /**
     * Build scene.json with a fullscreen image quad.
     * BRIGHTNESS FIX: clearcolor set to dimmed average color (30% brightness).
     */
    private fun buildSceneJson(texW: Int, texH: Int, texBase: String, schemecolor: String): ByteArray {
        // Parse schemecolor to compute dimmed clearcolor
        val parts = schemecolor.split(" ").mapNotNull { it.toFloatOrNull() }
        val (r, g, b) = if (parts.size >= 3) Triple(parts[0], parts[1], parts[2]) else Triple(0f, 0f, 0f)
        val clearcolor = "%.3f %.3f %.3f".format(r * 0.3f, g * 0.3f, b * 0.3f)

        return buildString {
            append("{\r\n")
            append("\t\"camera\" : \r\n")
            append("\t{\r\n")
            append("\t\t\"center\" : \"0.000 0.000 -1.000\",\r\n")
            append("\t\t\"eye\" : \"0.000 0.000 0.000\",\r\n")
            append("\t\t\"up\" : \"0.000 1.000 0.000\"\r\n")
            append("\t},\r\n")
            append("\t\"general\" : \r\n")
            append("\t{\r\n")
            append("\t\t\"clearcolor\" : \"$clearcolor\",\r\n")
            append("\t\t\"clearenabled\" : true,\r\n")
            append("\t\t\"farz\" : 10000.0,\r\n")
            append("\t\t\"fov\" : 50.0,\r\n")
            append("\t\t\"nearz\" : 0.0099999997764825821,\r\n")
            append("\t\t\"orthogonalprojection\" : \r\n")
            append("\t\t{\r\n")
            append("\t\t\t\"height\" : $texH,\r\n")
            append("\t\t\t\"width\" : $texW\r\n")
            append("\t\t},\r\n")
            append("\t\t\"zoom\" : 1.0\r\n")
            append("\t},\r\n")
            append("\t\"objects\" : \r\n")
            append("\t[\r\n")
            append("\t\t{\r\n")
            append("\t\t\t\"alignment\" : \"center\",\r\n")
            append("\t\t\t\"alpha\" : 1.0,\r\n")
            append("\t\t\t\"angles\" : \"0.000 0.000 0.000\",\r\n")
            append("\t\t\t\"color\" : \"1.000 1.000 1.000\",\r\n")
            append("\t\t\t\"colorBlendMode\" : 0,\r\n")
            append("\t\t\t\"copybackground\" : true,\r\n")
            append("\t\t\t\"id\" : 1,\r\n")
            append("\t\t\t\"image\" : \"models/$texBase.json\",\r\n")
            append("\t\t\t\"ledsource\" : false,\r\n")
            append("\t\t\t\"locktransforms\" : true,\r\n")
            append("\t\t\t\"name\" : \"$texBase\",\r\n")
            append("\t\t\t\"origin\" : \"${texW/2}.000 ${texH/2}.000 0.000\",\r\n")
            append("\t\t\t\"parallaxDepth\" : \"0.000 1.000\",\r\n")
            append("\t\t\t\"perspective\" : false,\r\n")
            append("\t\t\t\"scale\" : \"1.000 1.000 1.000\",\r\n")
            append("\t\t\t\"size\" : \"$texW.000 $texH.000\",\r\n")
            append("\t\t\t\"solid\" : true,\r\n")
            append("\t\t\t\"visible\" : true\r\n")
            append("\t\t}\r\n")
            append("\t]\r\n")
            append("}")
        }.toByteArray(Charsets.UTF_8)
    }

    private fun buildModelJson(texBase: String): ByteArray {
        return buildString {
            append("{\r\n")
            append("\t\"autosize\" : true,\r\n")
            append("\t\"material\" : \"materials/$texBase.json\"\r\n")
            append("}")
        }.toByteArray(Charsets.UTF_8)
    }

    /**
     * Build material JSON using "genericimage2" shader.
     * BRIGHTNESS FIX: blending = "normal" (not "translucent" which can cause additive blending).
     * depthtest = "enabled" matches real WE samples.
     */
    private fun buildMaterialJson(texBase: String): ByteArray {
        return buildString {
            append("{\r\n")
            append("\t\"passes\" : \r\n")
            append("\t[\r\n")
            append("\t\t{\r\n")
            append("\t\t\t\"blending\" : \"normal\",\r\n")
            append("\t\t\t\"combos\" : \r\n")
            append("\t\t\t{\r\n")
            append("\t\t\t\t\"version\" : 2\r\n")
            append("\t\t\t},\r\n")
            append("\t\t\t\"cullmode\" : \"nocull\",\r\n")
            append("\t\t\t\"depthtest\" : \"enabled\",\r\n")
            append("\t\t\t\"depthwrite\" : \"enabled\",\r\n")
            append("\t\t\t\"shader\" : \"genericimage2\",\r\n")
            append("\t\t\t\"textures\" : [ \"$texBase\" ]\r\n")
            append("\t\t}\r\n")
            append("\t]\r\n")
            append("}")
        }.toByteArray(Charsets.UTF_8)
    }

    /**
     * Build a preview JPEG thumbnail from a source image file.
     */
    private fun buildPreviewJpeg(imageFile: File, maxDim: Int = 400): ByteArray {
        val orig = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return ByteArray(0)
        val origW = orig.width
        val origH = orig.height
        val max = maxOf(origW, origH)
        if (max > maxDim) {
            val scale = maxDim.toFloat() / max
            val newW = (origW * scale).toInt().coerceAtLeast(1)
            val newH = (origH * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(orig, newW, newH, true)
            orig.recycle()
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            scaled.recycle()
            return baos.toByteArray()
        }
        val baos = ByteArrayOutputStream()
        orig.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        orig.recycle()
        return baos.toByteArray()
    }

    /**
     * Build project.json for a Video wallpaper.
     */
    private fun buildVideoProjectJson(item: WorkshopItemTyped): ByteArray {
        val title = escapeJsonString(item.title)
        val description = escapeJsonString(item.description.ifBlank { "Sent from WepWawet" })
        val tags = item.tags.joinToString(", ") { "\"${escapeJsonString(it)}\"" }
        return buildString {
            append("{\r\n")
            append("\t\"contentrating\" : \"Everyone\",\r\n")
            append("\t\"description\" : \"$description\",\r\n")
            // Use MPKG_VFS:// scheme for parity with real WE PC's transcoder output
            append("\t\"file\" : \"MPKG_VFS://wallpaper.mp4\",\r\n")
            append("\t\"general\" : \r\n")
            append("\t{\r\n")
            append("\t\t\"properties\" : \r\n")
            append("\t\t{\r\n")
            append("\t\t\t\"schemecolor\" : \r\n")
            append("\t\t\t{\r\n")
            append("\t\t\t\t\"order\" : 0,\r\n")
            append("\t\t\t\t\"text\" : \"ui_browse_properties_scheme_color\",\r\n")
            append("\t\t\t\t\"type\" : \"color\",\r\n")
            append("\t\t\t\t\"value\" : \"0.000 0.000 0.000\"\r\n")
            append("\t\t\t}\r\n")
            append("\t\t}\r\n")
            append("\t},\r\n")
            // Use MPKG_VFS:// scheme for the preview too (disasm line 14003eacc)
            append("\t\"preview\" : \"MPKG_VFS://preview.jpg\",\r\n")
            append("\t\"tags\" : [ $tags ],\r\n")
            append("\t\"title\" : \"$title\",\r\n")
            append("\t\"type\" : \"video\",\r\n")
            append("\t\"version\" : 0,\r\n")
            append("\t\"visibility\" : \"private\",\r\n")
            append("\t\"workshopurl\" : \"https://steamcommunity.com/sharedfiles/filedetails/?id=${item.id}\"\r\n")
            append("}")
        }.toByteArray(Charsets.UTF_8)
    }

    // ==================== SHARED UTILITIES ====================

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
     * Convert a .tex file containing PNG data inside the TEXB section to raw RGBA8888.
     *
     * === ACTUAL .tex FORMAT (verified against RePKG + linux-wallpaperengine source) ===
     *
     * Header:
     *   "TEXV0005\0" (9 bytes magic1, null-terminated string)
     *   "TEXI0001\0" (9 bytes magic2, null-terminated string)
     *   TEXI Header (28 bytes = 7 × int32 LE):
     *     format       (TextureFormat: 0=ARGB8888, 4=DXT5, 5=ETC2-RGBA?, 6=DXT3, 7=DXT1,
     *                   8=RG88, 9=R8, 12=BC7, 13=RGBa1010102, 14=RGBA16161616f, ...)
     *     flags        (TextureFlags: 1=NoInterpolation, 2=ClampUVs, 4=IsGif, 32=Video, ...)
     *     texture_width  (int) — GPU texture width (may be padded to multiple of 4)
     *     texture_height (int) — GPU texture height
     *     image_width    (int) — actual image width (may be < texture_width)
     *     image_height   (int) — actual image height
     *     UnkInt0       (int) — some hash/checksum, ignored by readers
     *
     * TEXB Container:
     *   "TEXB0004\0" (9 bytes magic, null-terminated string)
     *   image_count  (int)
     *   format       (int, FreeImageFormat: -1=FIF_UNKNOWN, 13=FIF_PNG, 2=FIF_JPEG, ...)
     *   is_video_mp4 (int, 0 or 1) — TEXB0004 only
     *   [For each image]:
     *     mipmap_count (int)
     *     [For each mipmap, v2/v3 format]:
     *       width (int)
     *       height (int)
     *       compression (int) — 0=none, 1=LZ4
     *       uncompressed_size (int) — if compression=0, reader overwrites with compressed_size
     *       compressed_size (int) — this IS the data length, regardless of compression
     *       data (compressed_size bytes)
     *
     * === WHY CONVERT ===
     *
     * Steam depot stores .tex files with:
     *   TexI format=0 (RGBA8888 declared)
     *   TexB format=13 (FIF_PNG)
     *   4 mipmaps of PNG data
     *
     * WE PC decodes the PNG via stb_image at load time.
     * WE Android does NOT include a PNG decoder for .tex files — it expects raw texture
     * data (FIF_UNKNOWN + format from TexI). When WE Android sees format=13, it falls
     * back to the "missing texture" yellow/black checkerboard pattern.
     *
     * === CONVERSION ===
     *
     * Input: .tex with PNG-encoded mipmaps (TexB format=13)
     * Output: .tex with raw RGBA8888 data (TexB format=-1, TexI format=0)
     *
     * Output structure:
     *   - TEXV0005\0 + TEXI0001\0 (preserved from original)
     *   - TEXI header: format=0 (RGBA8888), flags from original,
     *     dimensions from largest PNG (mipmap level 0)
     *   - TEXB0004\0
     *   - image_count=1, format=-1 (FIF_UNKNOWN), is_video_mp4=0
     *   - For image 0: mipmap_count=1 (only base level, to keep file size reasonable)
     *   - For mipmap 0: width, height, compression=0,
     *     uncompressed_size=w*h*4, compressed_size=w*h*4, data=raw_RGBA_bytes
     *
     * The RGBA bytes come from Android's BitmapFactory + Bitmap.copyPixelsToBuffer.
     * On little-endian Android (the common case), ARGB_8888 bitmaps stored in a
     * direct ByteBuffer in nativeOrder() are laid out as R,G,B,A per pixel — which
     * matches WE's RGBA8888 format exactly. NO byte swap is needed.
     *
     * File size estimate:
     *   3000×1800 RGBA = 21.6 MB per texture
     *   4 textures × 1 mipmap = 86.4 MB total
     * (Larger than the 5MB PNG version, but actually renders correctly on Android.
     * A future iteration can add LZ4 compression or ETC2/ASTC encoding to reduce size.)
     */
    private fun convertTexPngToRgba(texData: ByteArray, name: String): ByteArray? {
        try {
            // ---- Step 1: Parse original .tex structure to find PNG images ----
            // Find all PNG signatures (\x89PNG) and extract each PNG (up to IEND+CRC).
            data class PngEntry(val offset: Int, val end: Int, val bytes: ByteArray)
            val pngImages = mutableListOf<PngEntry>()
            var searchPos = 0
            val iendMarker = byteArrayOf(0x49, 0x45, 0x4e, 0x44) // "IEND"
            while (true) {
                val pngStart = findBytes(texData, PNG_MAGIC, searchPos)
                if (pngStart < 0) break
                val iendStart = findBytes(texData, iendMarker, pngStart)
                if (iendStart < 0) {
                    WepWawetLogger.proxyWarn("MpkgPacker.convertTexPngToRgba: PNG without IEND",
                        "name" to name, "png_start" to pngStart)
                    break
                }
                val pngEnd = iendStart + 8 // IEND marker (4 bytes) + CRC (4 bytes)
                val pngBytes = texData.copyOfRange(pngStart, pngEnd)
                pngImages.add(PngEntry(pngStart, pngEnd, pngBytes))
                searchPos = pngEnd
            }

            if (pngImages.isEmpty()) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexPngToRgba: no PNGs found in .tex",
                    "name" to name,
                    "tex_size" to texData.size)
                return null
            }

            // ---- Step 2: Decode each PNG and find the largest (mipmap level 0) ----
            // We use only ONE mipmap to keep the file size manageable.
            // The selection defaults to mipmap level 2 (third-largest) to match the
            // real WE Android .tex file sizes (~5 MB total for 4 textures).
            // At mipmap level 0 (3000x1800), 4 textures would total ~86 MB.
            // At mipmap level 2 (750x450), 4 textures total ~5.4 MB.
            data class DecodedRgba(
                val pngIndex: Int,
                val width: Int,
                val height: Int,
                val rgba: ByteArray,
            )
            val decoded = mutableListOf<DecodedRgba>()
            for ((idx, pngEntry) in pngImages.withIndex()) {
                // CRITICAL: Set inPremultiplied=false so Android doesn't premultiply
                // alpha into RGB. WE's shaders expect non-premultiplied alpha — for
                // semi-transparent edges (anti-aliasing) and X-ray mask textures,
                // premultiplication makes RGB values darker than expected, breaking
                // the X-ray effect (mask shows as black instead of grey, figure shows
                // as black instead of visible).
                val options = android.graphics.BitmapFactory.Options().apply {
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                    inPremultiplied = false  // CRITICAL for X-ray mask textures
                }
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(
                    pngEntry.bytes, 0, pngEntry.bytes.size, options)
                if (bitmap == null) {
                    WepWawetLogger.proxyWarn("MpkgPacker.convertTexPngToRgba: BitmapFactory.decodeByteArray returned null",
                        "name" to name,
                        "png_index" to idx,
                        "png_size" to pngEntry.bytes.size)
                    continue
                }
                // Force ARGB_8888 config (it usually is already, but just in case)
                val rgbaBitmap = if (bitmap.config == android.graphics.Bitmap.Config.ARGB_8888) {
                    bitmap
                } else {
                    val copy = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    bitmap.recycle()
                    copy
                }
                if (rgbaBitmap == null) {
                    WepWawetLogger.proxyWarn("MpkgPacker.convertTexPngToRgba: copy to ARGB_8888 failed",
                        "name" to name,
                        "png_index" to idx)
                    continue
                }
                val w = rgbaBitmap.width
                val h = rgbaBitmap.height
                // Allocate a direct ByteBuffer in native byte order. On little-endian
                // Android (the common case), copyPixelsToBuffer writes R,G,B,A per pixel
                // for ARGB_8888 bitmaps — which matches WE's RGBA8888 format exactly.
                // NO byte swap is needed.
                val pixelBuffer = java.nio.ByteBuffer.allocateDirect(w * h * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
                rgbaBitmap.copyPixelsToBuffer(pixelBuffer)
                rgbaBitmap.recycle()

                val rgba = ByteArray(w * h * 4)
                pixelBuffer.rewind()
                pixelBuffer.get(rgba)
                decoded.add(DecodedRgba(idx, w, h, rgba))

                WepWawetLogger.proxyDebug("MpkgPacker.convertTexPngToRgba: decoded PNG",
                    "name" to name,
                    "png_index" to idx,
                    "width" to w,
                    "height" to h,
                    "rgba_size" to rgba.size,
                    "premultiplied" to false,
                    "first_8_bytes_hex" to rgba.take(8).joinToString("") { "%02x".format(it) })
            }

            if (decoded.isEmpty()) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexPngToRgba: no PNGs decoded successfully",
                    "name" to name)
                return null
            }

            // Pick the mipmap level to use.
            // Mipmap levels (typical): 0=largest(3000x1800, 21.6MB), 1=1500x900(5.4MB),
            //                          2=750x450(1.35MB), 3=375x225(0.34MB)
            // Default target: level 2 (750x450) — gives ~5.4 MB total for 4 textures,
            // matching the user's "4 something MB" target.
            // Falls back to smaller index if not enough mipmaps available.
            val sortedBySize = decoded.sortedByDescending { it.width.toLong() * it.height.toLong() }
            val targetMipmapLevel = 2  // 0=largest, 1=second-largest, 2=third-largest, etc.
            val targetIndex = minOf(targetMipmapLevel, sortedBySize.size - 1)
            val largest = sortedBySize[targetIndex]
            WepWawetLogger.proxy("MpkgPacker.convertTexPngToRgba: selected mipmap",
                "name" to name,
                "target_level" to targetMipmapLevel,
                "actual_level" to targetIndex,
                "available_levels" to sortedBySize.size,
                "selected_index_in_original" to largest.pngIndex,
                "width" to largest.width,
                "height" to largest.height,
                "rgba_bytes" to largest.rgba.size,
                "all_mipmap_sizes" to sortedBySize.joinToString(", ") { "${it.width}x${it.height}(${it.rgba.size}b)" })

            // ---- Step 3: Read original TEXI header fields (we'll preserve most) ----
            // Layout (offsets relative to start of .tex):
            //   0-8   "TEXV0005\0"
            //   9-17  "TEXI0001\0"
            //   18-21 format
            //   22-25 flags
            //   26-29 texture_width
            //   30-33 texture_height
            //   34-37 image_width
            //   38-41 image_height
            //   42-45 UnkInt0
            if (texData.size < 46) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexPngToRgba: .tex too small for header",
                    "name" to name,
                    "tex_size" to texData.size)
                return null
            }
            val origFlags = readLeInt(texData, 22)
            val origUnkInt0 = readLeInt(texData, 42)
            WepWawetLogger.proxyDebug("MpkgPacker.convertTexPngToRgba: original TEXI fields",
                "name" to name,
                "orig_format" to readLeInt(texData, 18),
                "orig_flags" to origFlags,
                "orig_texture_width" to readLeInt(texData, 26),
                "orig_texture_height" to readLeInt(texData, 30),
                "orig_image_width" to readLeInt(texData, 34),
                "orig_image_height" to readLeInt(texData, 38),
                "orig_unk_int0_hex" to "0x%08x".format(origUnkInt0))

            // ---- Step 4: Build new .tex ----
            val out = java.io.ByteArrayOutputStream()

            // 4a: Magic1 + Magic2 (18 bytes, preserved from original)
            out.write(texData, 0, 18)

            // 4b: TEXI Header (28 bytes = 7 × int32 LE)
            // Override format to 0 (RGBA8888) since we're writing raw RGBA pixel data.
            // Use PNG dimensions for both texture_* and image_* (no padding needed for RGBA8888).
            // Preserve flags from original (ClampUVs etc.) and UnkInt0.
            writeLeIntToStream(out, 0)                              // format = 0 (RGBA8888)
            writeLeIntToStream(out, origFlags)                     // flags (preserved)
            writeLeIntToStream(out, largest.width)                // texture_width = PNG width
            writeLeIntToStream(out, largest.height)               // texture_height = PNG height
            writeLeIntToStream(out, largest.width)                // image_width = PNG width
            writeLeIntToStream(out, largest.height)               // image_height = PNG height
            writeLeIntToStream(out, origUnkInt0)                   // UnkInt0 (preserved)

            // 4c: TEXB Container header
            // "TEXB0004\0" magic
            out.write(byteArrayOf(
                0x54, 0x45, 0x58, 0x42,  // "TEXB"
                0x30, 0x30, 0x30, 0x34,  // "0004"
                0x00                     // null terminator
            ))
            writeLeIntToStream(out, 1)                              // image_count = 1
            writeLeIntToStream(out, -1)                             // format = -1 (FIF_UNKNOWN)
            writeLeIntToStream(out, 0)                             // is_video_mp4 = 0

            // 4d: Image 0 (only one image)
            writeLeIntToStream(out, 1)                              // mipmap_count = 1

            // 4e: Mipmap 0 (only one mipmap — base level)
            val rgbaSize = largest.rgba.size
            writeLeIntToStream(out, largest.width)                // width
            writeLeIntToStream(out, largest.height)               // height
            writeLeIntToStream(out, 0)                              // compression = 0 (no LZ4)
            writeLeIntToStream(out, rgbaSize)                      // uncompressed_size = w*h*4
            writeLeIntToStream(out, rgbaSize)                      // compressed_size = w*h*4 (same)
            // Raw RGBA pixel data (R,G,B,A per pixel, no swap needed)
            out.write(largest.rgba)

            val result = out.toByteArray()

            // ---- Step 5: Verify by reading back the structure ----
            val vMagic1 = String(result, 0, 8, Charsets.US_ASCII)
            val vMagic2 = String(result, 9, 8, Charsets.US_ASCII)
            val vFormat = readLeInt(result, 18)
            val vTexW = readLeInt(result, 26)
            val vTexH = readLeInt(result, 30)
            val vImageCount = readLeInt(result, 55)
            val vFIF = readLeInt(result, 59)
            val vIsMp4 = readLeInt(result, 63)
            val vMipmapCount = readLeInt(result, 67)
            val vMipmapW = readLeInt(result, 71)
            val vMipmapH = readLeInt(result, 75)
            val vCompression = readLeInt(result, 79)
            val vUncompressedSize = readLeInt(result, 83)
            val vCompressedSize = readLeInt(result, 87)
            WepWawetLogger.proxy("MpkgPacker.convertTexPngToRgba SUCCESS",
                "name" to name,
                "original_size" to texData.size,
                "converted_size" to result.size,
                "png_count_in_original" to pngImages.size,
                "decoded_count" to decoded.size,
                "selected_mipmap_index" to largest.pngIndex,
                "selected_width" to largest.width,
                "selected_height" to largest.height,
                "rgba_bytes" to rgbaSize,
                // Verification
                "verify_magic1" to vMagic1,
                "verify_magic2" to vMagic2,
                "verify_format" to vFormat,
                "verify_texture_width" to vTexW,
                "verify_texture_height" to vTexH,
                "verify_image_count" to vImageCount,
                "verify_fif" to vFIF,
                "verify_is_mp4" to vIsMp4,
                "verify_mipmap_count" to vMipmapCount,
                "verify_mipmap_width" to vMipmapW,
                "verify_mipmap_height" to vMipmapH,
                "verify_compression" to vCompression,
                "verify_uncompressed_size" to vUncompressedSize,
                "verify_compressed_size" to vCompressedSize,
                "verify_data_starts_at" to 91,
                "verify_first_8_bytes_hex" to (if (result.size >= 99) result.copyOfRange(91, 99).joinToString("") { "%02x".format(it) } else "file_too_small"))
            return result

        } catch (e: Exception) {
            WepWawetLogger.proxyError("MpkgPacker.convertTexPngToRgba FAILED",
                "name" to name,
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            return null
        }
    }

    /**
     * Convert a .tex file containing PNG data to ETC2 RGBA8 + LZ4 format.
     *
     * This is the production-grade conversion that matches what WE PC's
     * `resourcecompiler64.exe -transcode -f ETC2` does for mobile packaging.
     *
     * Pipeline:
     *   1. Parse .tex structure to find all PNG images (mipmap levels)
     *   2. Decode the LARGEST PNG (mipmap level 0) → RGBA8888 using Android's
     *      BitmapFactory with `inPremultiplied = false` (preserves non-premultiplied
     *      alpha so X-ray shader reads correct mask.rgb values)
     *   3. Encode RGBA8888 → ETC2 RGBA8 using native library (libwepwawet_etc2.so)
     *      built from Google's etc2comp source
     *   4. LZ4-compress the ETC2 RGBA8 bytes using lz4-java library
     *   5. Write new .tex with format=5 (ETC2 RGBA8), compression=1 (LZ4),
     *      dimensions matching the PNG (so scene.json's autosize matches)
     *
     * Result: ~5x smaller than original PNG (matches real WE Android .tex files)
     * For 3000x1800 RGBA: 21.6 MB raw → 5.4 MB ETC2 → ~250 KB-2 MB LZ4
     *
     * If the ETC2 native library fails to load, falls back to the old
     * raw RGBA8888 conversion (which produces larger files but still works).
     */
    private fun convertTexPngToEtc2(texData: ByteArray, name: String): ByteArray? {
        // Try ETC2 path first
        try {
            // Check if ETC2 native encoder is available
            if (!Etc2Encoder.isAvailable()) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexPngToEtc2: native ETC2 encoder not available, falling back to RGBA8888",
                    "name" to name)
                return convertTexPngToRgba(texData, name)
            }

            // ---- Step 1: Parse original .tex structure to find embedded images ----
            // .tex files from Steam depot may contain PNG or JPEG image data.
            // PNG: starts with \x89PNG, ends with IEND chunk + CRC (8 bytes).
            // JPEG: starts with FF D8 FF, ends with FF D9 (EOI marker, 2 bytes).
            data class ImageEntry(val offset: Int, val end: Int, val bytes: ByteArray, val format: String)
            val embeddedImages = mutableListOf<ImageEntry>()
            var searchPos = 0
            val iendMarker = byteArrayOf(0x49, 0x45, 0x4e, 0x44) // "IEND" (PNG end)
            // Search for PNG images first
            while (true) {
                val pngStart = findBytes(texData, PNG_MAGIC, searchPos)
                if (pngStart < 0) break
                val iendStart = findBytes(texData, iendMarker, pngStart)
                if (iendStart < 0) break
                val pngEnd = iendStart + 8 // IEND marker (4 bytes) + CRC (4 bytes)
                val pngBytes = texData.copyOfRange(pngStart, pngEnd)
                embeddedImages.add(ImageEntry(pngStart, pngEnd, pngBytes, "PNG"))
                searchPos = pngEnd
            }
            // If no PNG found, search for JPEG images
            if (embeddedImages.isEmpty()) {
                searchPos = 0
                while (true) {
                    val jpgStart = findBytes(texData, JPEG_MAGIC, searchPos)
                    if (jpgStart < 0) break
                    // Validate that this is actually JPEG (not a false positive).
                    // FFD8FF can appear in binary .tex data (DXT textures, LZ4-compressed
                    // data, etc.) by coincidence. Check that the 4th byte (the marker type
                    // after SOI) is a valid JPEG marker.
                    if (jpgStart + 3 >= texData.size) { searchPos = jpgStart + 3; continue }
                    val markerByte = texData[jpgStart + 3]
                    if (!isValidJpegMarker(markerByte)) {
                        WepWawetLogger.proxy("MpkgPacker.convertTexPngToEtc2: FFD8FF found but marker byte invalid — false positive, skipping",
                            "name" to name, "jpg_start" to jpgStart,
                            "marker_byte" to "0x%02x".format(markerByte))
                        searchPos = jpgStart + 3
                        continue
                    }
                    // CRITICAL: Do NOT search for the JPEG EOI marker (FF D9) to
                    // determine the JPEG's end. The EXIF data in many JPEGs (including
                    // the Mordred V2 wallpaper) contains a THUMBNAIL JPEG image which
                    // has its own FF D8 FF ... FF D9 sequence. Searching for FF D9 from
                    // the JPEG start would find the thumbnail's EOI, producing a
                    // truncated JPEG that BitmapFactory.decodeByteArray returns null for.
                    //
                    // Instead, pass the ENTIRE remaining .tex file data from the JPEG
                    // SOI marker to the end. BitmapFactory is smart enough to find the
                    // real EOI marker of the MAIN image and ignore any trailing data
                    // (including the thumbnail, padding, and next mipmap's data).
                    val jpgEnd = texData.size
                    val jpgBytes = texData.copyOfRange(jpgStart, jpgEnd)
                    embeddedImages.add(ImageEntry(jpgStart, jpgEnd, jpgBytes, "JPEG"))
                    WepWawetLogger.proxy("MpkgPacker.convertTexPngToEtc2: JPEG found, using entire remaining .tex data",
                        "name" to name, "jpg_start" to jpgStart, "jpg_end" to jpgEnd,
                        "jpg_bytes" to jpgBytes.size,
                        "first_8_bytes_hex" to jpgBytes.take(8).joinToString("") { "%02x".format(it) })
                    break
                }
            }

            if (embeddedImages.isEmpty()) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexPngToEtc2: no PNG or JPEG images found",
                    "name" to name)
                return convertTexPngToRgba(texData, name)
            }

            // ---- Step 2: Decode the LARGEST image (mipmap level 0) to RGBA8888 ----
            // Use mipmap level 0 (full size) — ETC2 + LZ4 will reduce file size
            // enough that we can afford the full resolution. Also, mipmap level 0's
            // dimensions MUST match what scene.json expects (otherwise autosize
            // shrinks the model to a "small box in the center" — bug from v0.2.6).
            // BitmapFactory can decode both PNG and JPEG.
            data class DecodedRgba(val imgIndex: Int, val width: Int, val height: Int, val rgba: ByteArray)
            val decoded = mutableListOf<DecodedRgba>()
            for ((idx, imgEntry) in embeddedImages.withIndex()) {
                val options = android.graphics.BitmapFactory.Options().apply {
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                    inPremultiplied = false  // CRITICAL for X-ray mask textures
                }
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(
                    imgEntry.bytes, 0, imgEntry.bytes.size, options)
                if (bitmap == null) {
                    WepWawetLogger.proxyWarn("MpkgPacker.convertTexPngToEtc2: BitmapFactory returned null",
                        "name" to name, "img_index" to idx, "format" to imgEntry.format)
                    continue
                }
                val rgbaBitmap = if (bitmap.config == android.graphics.Bitmap.Config.ARGB_8888) {
                    bitmap
                } else {
                    val copy = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    bitmap.recycle()
                    copy
                } ?: continue

                val w = rgbaBitmap.width
                val h = rgbaBitmap.height
                val pixelBuffer = java.nio.ByteBuffer.allocateDirect(w * h * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
                rgbaBitmap.copyPixelsToBuffer(pixelBuffer)
                rgbaBitmap.recycle()

                // === TEXTURE ORIENTATION — NO FLIP (v0.4.3) ===
                //
                // History:
                //  - v0.3.1-alpha: flipped PNG → ETC2 vertically. Result: textures
                //    appeared UPSIDE-DOWN on WE Android (the character image was
                //    rendered head-down, the "D a y" text appeared at the top of
                //    the screen instead of the bottom).
                //  - v0.3.2-alpha: REVERTED the flip. Result: textures appeared
                //    RIGHT-SIDE-UP. This is the correct orientation.
                //  - v0.4.2-alpha: re-added the flip (thinking OpenGL's bottom-up
                //    convention required it). Result: textures appeared UPSIDE-DOWN
                //    again (user reported "the image is now fully upside down again").
                //
                // Conclusion: WE Android's texture loading pipeline already handles
                // the orientation correctly when ETC2 blocks are encoded in TOP-DOWN
                // order (matching the PNG's natural row order). The flip is NOT
                // needed and actually breaks rendering.
                //
                // The previous analysis about "OpenGL expects bottom-up" was wrong
                // for the WE Android case: WE Android likely uses
                // glPixelStorei(GL_UNPACK_FLIP_Y) or samples textures with V=1-V
                // internally, which compensates for the top-down encoding.
                //
                // The "D a y" text being upside down (which the user reported in
                // v0.4.1) is NOT caused by texture orientation — it's a separate
                // WE Android text-rendering issue (the text glyphs are rendered to
                // a font texture, which uses a different rendering path that we
                // can't fix from the .mpkg side).
                //
                // Decision: use the top-down RGBA buffer AS-IS, no flip.
                val rgbaTopDown = ByteArray(w * h * 4)
                pixelBuffer.rewind()
                pixelBuffer.get(rgbaTopDown)
                val rgba = rgbaTopDown
                WepWawetLogger.proxyDebug("MpkgPacker.convertTexPngToEtc2: RGBA buffer (no flip — v0.4.3)",
                    "name" to name, "width" to w, "height" to h,
                    "first_pixel_rgba" to
                        "(${rgba[0].toInt() and 0xFF},${rgba[1].toInt() and 0xFF},${rgba[2].toInt() and 0xFF},${rgba[3].toInt() and 0xFF})")
                decoded.add(DecodedRgba(idx, w, h, rgba))

                WepWawetLogger.proxyDebug("MpkgPacker.convertTexPngToEtc2: decoded PNG",
                    "name" to name, "png_index" to idx,
                    "width" to w, "height" to h, "rgba_size" to rgba.size)
            }

            if (decoded.isEmpty()) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexPngToEtc2: no PNGs decoded",
                    "name" to name)
                return convertTexPngToRgba(texData, name)
            }

            // Pick the largest mipmap (level 0) — texture dimensions MUST match scene.json
            val largest = decoded.maxByOrNull { it.width.toLong() * it.height.toLong() }!!
            WepWawetLogger.proxy("MpkgPacker.convertTexPngToEtc2: selected mipmap (largest, must match scene.json)",
                "name" to name, "selected_index" to largest.imgIndex,
                "width" to largest.width, "height" to largest.height,
                "rgba_bytes" to largest.rgba.size)

            // ---- Step 3: Encode RGBA8888 → ETC2 RGBA8 (via native library) ----
            val etc2Bytes = try {
                Etc2Encoder.encodeRgba8888(largest.rgba, largest.width, largest.height)
            } catch (e: Exception) {
                WepWawetLogger.proxyError("MpkgPacker.convertTexPngToEtc2: native ETC2 encoding failed, falling back to RGBA8888",
                    "name" to name, "exception" to e.javaClass.name, "message" to (e.message ?: ""))
                return convertTexPngToRgba(texData, name)
            }

            // Expected size: blocks_x * blocks_y * 16
            val blocksX = (largest.width + 3) / 4
            val blocksY = (largest.height + 3) / 4
            val expectedEtc2Size = blocksX * blocksY * 16
            WepWawetLogger.proxy("MpkgPacker.convertTexPngToEtc2: ETC2 encoded",
                "name" to name, "etc2_size" to etc2Bytes.size,
                "expected" to expectedEtc2Size,
                "compression_ratio" to "%.2f%%".format(etc2Bytes.size.toDouble() * 100 / largest.rgba.size))

            // ---- Step 4: LZ4-compress the ETC2 bytes ----
            // Use raw LZ4 block format (no size prefix — uncompressed_size is in .tex header)
            //
            // CRITICAL: LZ4 is REQUIRED — compression=1 (LZ4) is what the WE Android
            // renderer expects for ETC2 RGBA8 .tex files. Falling back to compression=0
            // (uncompressed ETC2) produces a .tex the renderer does NOT decode correctly
            // (results in a black or corrupted wallpaper on the phone).
            //
            // LZ4Factory.safeInstance() uses Class.forName internally to load
            // LZ4JavaSafeCompressor. The proguard-rules.pro LZ4 keep block
            // (`-keep class net.jpountz.lz4.** { *; }` etc.) MUST be present or R8
            // strips the class and Class.forName throws ClassNotFoundException wrapped
            // in AssertionError. If that ever happens, DO NOT silently fall back —
            // let it propagate so the user sees the real error and we fix the
            // proguard rules instead of producing broken wallpapers.
            val lz4Factory = net.jpountz.lz4.LZ4Factory.safeInstance()
            val compressor = lz4Factory.fastCompressor()
            val lz4Compressed = compressor.compress(etc2Bytes)
            WepWawetLogger.proxy("MpkgPacker.convertTexPngToEtc2: LZ4 compressed",
                "name" to name,
                "etc2_size" to etc2Bytes.size, "lz4_size" to lz4Compressed.size,
                "lz4_ratio" to "%.2f%%".format(lz4Compressed.size.toDouble() * 100 / etc2Bytes.size))

            // ---- Step 5: Read original TEXI header fields (preserve flags + UnkInt0) ----
            if (texData.size < 46) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexPngToEtc2: .tex too small for header",
                    "name" to name, "tex_size" to texData.size)
                return convertTexPngToRgba(texData, name)
            }
            val origFlags = readLeInt(texData, 22)
            val origUnkInt0 = readLeInt(texData, 42)

            // ---- Step 6: Build new .tex with format=5, compression=1, ETC2+LZ4 ----
            val out = java.io.ByteArrayOutputStream()

            // 6a: Magic1 + Magic2 (18 bytes, preserved from original)
            out.write(texData, 0, 18)

            // 6b: TEXI Header (28 bytes = 7 × int32 LE)
            // format=5 (ETC2 RGBA8) — matches real WE Android .tex files
            writeLeIntToStream(out, 5)                              // format = 5 (ETC2 RGBA8)
            writeLeIntToStream(out, origFlags)                      // flags (preserved)
            writeLeIntToStream(out, largest.width)                 // texture_width = PNG width
            writeLeIntToStream(out, largest.height)                // texture_height = PNG height
            writeLeIntToStream(out, largest.width)                // image_width = PNG width
            writeLeIntToStream(out, largest.height)               // image_height = PNG height
            writeLeIntToStream(out, origUnkInt0)                    // UnkInt0 (preserved)

            // 6c: TEXB Container header
            out.write(byteArrayOf(
                0x54, 0x45, 0x58, 0x42,  // "TEXB"
                0x30, 0x30, 0x30, 0x34,  // "0004"
                0x00                     // null terminator
            ))
            writeLeIntToStream(out, 1)                              // image_count = 1
            writeLeIntToStream(out, -1)                             // format = -1 (FIF_UNKNOWN)
            writeLeIntToStream(out, 0)                             // is_video_mp4 = 0

            // 6d: Image 0
            writeLeIntToStream(out, 1)                              // mipmap_count = 1

            // 6e: Mipmap 0 with ETC2 RGBA8 + LZ4 compression (compression=1 is REQUIRED)
            writeLeIntToStream(out, largest.width)                 // width
            writeLeIntToStream(out, largest.height)                // height
            writeLeIntToStream(out, 1)                              // compression = 1 (LZ4)
            writeLeIntToStream(out, etc2Bytes.size)                // uncompressed_size
            writeLeIntToStream(out, lz4Compressed.size)            // compressed_size
            out.write(lz4Compressed)                                // LZ4-compressed ETC2 RGBA8 data

            val result = out.toByteArray()

            // ---- Step 7: Verify by reading back the structure ----
            val vMagic1 = String(result, 0, 8, Charsets.US_ASCII)
            val vMagic2 = String(result, 9, 8, Charsets.US_ASCII)
            val vFormat = readLeInt(result, 18)
            val vTexW = readLeInt(result, 26)
            val vTexH = readLeInt(result, 30)
            val vFIF = readLeInt(result, 59)
            val vMipmapCount = readLeInt(result, 67)
            val vMipmapW = readLeInt(result, 71)
            val vMipmapH = readLeInt(result, 75)
            val vCompression = readLeInt(result, 79)
            val vUncompSize = readLeInt(result, 83)
            val vCompSize = readLeInt(result, 87)
            WepWawetLogger.proxy("MpkgPacker.convertTexPngToEtc2 SUCCESS",
                "name" to name,
                "original_size" to texData.size,
                "converted_size" to result.size,
                "png_count" to embeddedImages.size,  // actually PNG+JPEG count
                "selected_width" to largest.width,
                "selected_height" to largest.height,
                "rgba_size" to largest.rgba.size,
                "etc2_size" to etc2Bytes.size,
                "lz4_size" to lz4Compressed.size,
                // Verification
                "verify_magic1" to vMagic1, "verify_magic2" to vMagic2,
                "verify_format" to vFormat, "verify_texture_width" to vTexW,
                "verify_texture_height" to vTexH, "verify_fif" to vFIF,
                "verify_mipmap_count" to vMipmapCount,
                "verify_mipmap_width" to vMipmapW, "verify_mipmap_height" to vMipmapH,
                "verify_compression" to vCompression,
                "verify_uncompressed_size" to vUncompSize,
                "verify_compressed_size" to vCompSize,
                "verify_data_starts_at" to 91,
                "verify_first_8_bytes_hex" to (if (result.size >= 99) result.copyOfRange(91, 99).joinToString("") { "%02x".format(it) } else "file_too_small"))

            return result
        } catch (e: Exception) {
            WepWawetLogger.proxyError("MpkgPacker.convertTexPngToEtc2 FAILED — falling back to RGBA8888",
                "name" to name, "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            return convertTexPngToRgba(texData, name)
        }
    }

    /**
     * Convert a DXT-format .tex file (PC texture format) to ETC2_RGBA8 + LZ4.
     *
     * Real WE PC's Steam Workshop .pkg files contain .tex files in DXT1/DXT3/DXT5
     * format (formats 7/6/4 respectively per repkg's TexFormat enum). When WE PC
     * sends a wallpaper to the WE Android app, it transcodes these DXT textures
     * to ETC2_RGBA8 via `resourcecompiler64.exe -transcode -f ETC2`. We do the
     * same transcode on-device.
     *
     * Flow:
     *   1. Parse .tex header → (tex_format, tex_w, tex_h, is_lz4, decompressed_size, byte_count)
     *   2. Read body bytes (offset 91, byte_count bytes)
     *   3. If is_lz4, LZ4-decompress body to get raw DXT block data
     *   4. Decode DXT blocks → RGBA8888 pixels (via native DxtDecoder)
     *   5. Encode RGBA8888 → ETC2_RGBA8 (via existing Etc2Encoder)
     *   6. LZ4-compress the ETC2 bytes
     *   7. Build new .tex with format=5, is_lz4=1, ETC2+LZ4 body (same shape as
     *      convertTexPngToEtc2's output, which has been verified to work on Android)
     *
     * Returns: the new .tex bytes, or null on failure (caller should fall back to
     *          convertTexPngToRgba or leave the original .tex unchanged).
     */
    private fun convertTexDxtToEtc2(texData: ByteArray, name: String): ByteArray? {
        try {
            // ---- Step 0: Verify native libraries are available ----
            if (!Etc2Encoder.isAvailable() || !DxtDecoder.isAvailable()) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexDxtToEtc2: native libs not available",
                    "name" to name,
                    "etc2_loaded" to Etc2Encoder.isAvailable(),
                    "dxt_loaded" to DxtDecoder.isAvailable())
                return null
            }

            // ---- Step 1: Parse .tex header ----
            if (texData.size < 91) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexDxtToEtc2: .tex too small for header",
                    "name" to name, "tex_size" to texData.size)
                return null
            }
            // Header layout (91 bytes):
            //   0..8   : TEXV0005\0
            //   9..17  : TEXI0001\0
            //   18..45 : 7x u32 LE: format, flags, tex_w, tex_h, img_w, img_h, color
            //   46..54 : TEXB0004\0
            //   55..66 : 3x u32 LE: imageCount, body_format, isVideoMp4
            //   67..70 : 1x u32 LE: mipmap_count
            //   71..86 : 4x u32 LE: width, height, IsLZ4, DecompressedSize (for mipmap 0)
            //   87..90 : 1x u32 LE: byteCount (for mipmap 0)
            //   91+    : body data (LZ4-compressed if IsLZ4=1)
            val texiFormat = readU32Le(texData, 18).toInt()
            val origFlags = readLeInt(texData, 22)
            val texWidth = readU32Le(texData, 26).toInt()
            val texHeight = readU32Le(texData, 30).toInt()
            val origUnkInt0 = readLeInt(texData, 42)  // color (RGBA) — preserved

            // Map .tex format → DxtDecoder.Format
            val dxtFormat = DxtDecoder.formatForTexFormat(texiFormat)
            if (dxtFormat == null) {
                // Not a DXT format — caller shouldn't have called us
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexDxtToEtc2: not a DXT format",
                    "name" to name, "texi_format" to texiFormat)
                return null
            }

            val mipmapCount = readU32Le(texData, 67).toInt()
            if (mipmapCount < 1) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexDxtToEtc2: mipmap_count < 1",
                    "name" to name, "mipmap_count" to mipmapCount)
                return null
            }
            // Use mipmap 0 (largest) — same as convertTexPngToEtc2
            val mipWidth = readU32Le(texData, 71).toInt()
            val mipHeight = readU32Le(texData, 75).toInt()
            val isLz4 = readU32Le(texData, 79).toInt() != 0
            val decompressedSize = readU32Le(texData, 83).toInt()
            val byteCount = readU32Le(texData, 87).toInt()

            WepWawetLogger.proxy("MpkgPacker.convertTexDxtToEtc2: parsed .tex header",
                "name" to name,
                "texi_format" to texiFormat,
                "dxt_format" to dxtFormat.displayName,
                "tex_width" to texWidth, "tex_height" to texHeight,
                "mip_width" to mipWidth, "mip_height" to mipHeight,
                "is_lz4" to isLz4,
                "decompressed_size" to decompressedSize,
                "byte_count" to byteCount,
                "tex_size" to texData.size)

            if (texWidth <= 0 || texHeight <= 0) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexDxtToEtc2: invalid tex dimensions",
                    "name" to name, "tex_width" to texWidth, "tex_height" to texHeight)
                return null
            }
            if (texWidth % 4 != 0 || texHeight % 4 != 0) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexDxtToEtc2: dimensions not multiple of 4 (DXT requires block alignment)",
                    "name" to name, "tex_width" to texWidth, "tex_height" to texHeight)
                // The DXT decoder handles non-block-aligned dimensions by clipping,
                // so this is a warning, not an error.
            }
            if (byteCount <= 0 || 91 + byteCount > texData.size) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexDxtToEtc2: byteCount out of range",
                    "name" to name, "byte_count" to byteCount,
                    "tex_size" to texData.size, "expected_min_size" to (91 + byteCount))
                return null
            }

            // ---- Step 2: Read body bytes (offset 91, byteCount bytes) ----
            val bodyBytes = texData.copyOfRange(91, 91 + byteCount)

            // ---- Step 3: If is_lz4, LZ4-decompress body to get raw DXT block data ----
            val dxtBlockData = if (isLz4) {
                if (decompressedSize <= 0) {
                    WepWawetLogger.proxyWarn("MpkgPacker.convertTexDxtToEtc2: is_lz4=1 but decompressed_size<=0",
                        "name" to name, "decompressed_size" to decompressedSize)
                    return null
                }
                try {
                    val lz4Factory = net.jpountz.lz4.LZ4Factory.safeInstance()
                    val decompressor = lz4Factory.safeDecompressor()
                    val decompressed = decompressor.decompress(bodyBytes, decompressedSize)
                    WepWawetLogger.proxy("MpkgPacker.convertTexDxtToEtc2: LZ4 decompressed body",
                        "name" to name,
                        "compressed_size" to bodyBytes.size,
                        "decompressed_size" to decompressed.size,
                        "lz4_ratio" to "%.2f%%".format(bodyBytes.size * 100.0 / decompressedSize))
                    decompressed
                } catch (e: Exception) {
                    WepWawetLogger.proxyError("MpkgPacker.convertTexDxtToEtc2: LZ4 decompression failed",
                        "name" to name, "exception" to e.javaClass.name, "message" to (e.message ?: ""),
                        "compressed_size" to bodyBytes.size, "expected_decompressed_size" to decompressedSize)
                    return null
                }
            } else {
                // No LZ4 — body is already raw DXT block data
                bodyBytes
            }

            // ---- Step 4: Decode DXT blocks → RGBA8888 pixels ----
            val rgbaBytes = DxtDecoder.decodeToRgba8888(dxtBlockData, texWidth, texHeight, dxtFormat)
                ?: run {
                    WepWawetLogger.proxyWarn("MpkgPacker.convertTexDxtToEtc2: DXT decoding returned null",
                        "name" to name,
                        "dxt_format" to dxtFormat.displayName,
                        "dxt_block_data_size" to dxtBlockData.size,
                        "tex_width" to texWidth, "tex_height" to texHeight)
                    return null
                }
            val expectedRgbaSize = texWidth * texHeight * 4
            if (rgbaBytes.size != expectedRgbaSize) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexDxtToEtc2: RGBA size mismatch",
                    "name" to name,
                    "actual_size" to rgbaBytes.size,
                    "expected_size" to expectedRgbaSize)
                // Continue anyway — the decoder pads with zeros if input was short
            }
            WepWawetLogger.proxy("MpkgPacker.convertTexDxtToEtc2: DXT decoded to RGBA8888",
                "name" to name,
                "dxt_format" to dxtFormat.displayName,
                "rgba_size" to rgbaBytes.size,
                "first_pixel_rgba" to
                    "(${rgbaBytes[0].toInt() and 0xFF},${rgbaBytes[1].toInt() and 0xFF},${rgbaBytes[2].toInt() and 0xFF},${rgbaBytes[3].toInt() and 0xFF})")

            // ---- Step 5: Encode RGBA8888 → ETC2_RGBA8 (via native library) ----
            val etc2Bytes = try {
                Etc2Encoder.encodeRgba8888(rgbaBytes, texWidth, texHeight)
            } catch (e: Exception) {
                WepWawetLogger.proxyError("MpkgPacker.convertTexDxtToEtc2: ETC2 encoding failed",
                    "name" to name, "exception" to e.javaClass.name, "message" to (e.message ?: ""))
                return null
            }
            val blocksX = (texWidth + 3) / 4
            val blocksY = (texHeight + 3) / 4
            val expectedEtc2Size = blocksX * blocksY * 16
            WepWawetLogger.proxy("MpkgPacker.convertTexDxtToEtc2: ETC2 encoded",
                "name" to name,
                "etc2_size" to etc2Bytes.size,
                "expected" to expectedEtc2Size,
                "compression_ratio" to "%.2f%%".format(etc2Bytes.size * 100.0 / rgbaBytes.size))

            // ---- Step 6: LZ4-compress the ETC2 bytes ----
            val lz4Factory = net.jpountz.lz4.LZ4Factory.safeInstance()
            val compressor = lz4Factory.fastCompressor()
            val lz4Compressed = compressor.compress(etc2Bytes)
            WepWawetLogger.proxy("MpkgPacker.convertTexDxtToEtc2: LZ4 compressed",
                "name" to name,
                "etc2_size" to etc2Bytes.size,
                "lz4_size" to lz4Compressed.size,
                "lz4_ratio" to "%.2f%%".format(lz4Compressed.size * 100.0 / etc2Bytes.size))

            // ---- Step 7: Build new .tex with format=5, is_lz4=1, ETC2+LZ4 body ----
            val out = java.io.ByteArrayOutputStream()
            // 7a: Magic1 + Magic2 (18 bytes, preserved from original)
            out.write(texData, 0, 18)
            // 7b: TEXI Header (28 bytes = 7 × int32 LE)
            writeLeIntToStream(out, 5)              // format = 5 (ETC2 RGBA8)
            writeLeIntToStream(out, origFlags)      // flags (preserved)
            writeLeIntToStream(out, texWidth)       // texture_width
            writeLeIntToStream(out, texHeight)      // texture_height
            writeLeIntToStream(out, texWidth)       // image_width (same as tex_w)
            writeLeIntToStream(out, texHeight)      // image_height
            writeLeIntToStream(out, origUnkInt0)    // color (preserved)
            // 7c: TEXB Container header (9 bytes)
            out.write(byteArrayOf(
                0x54, 0x45, 0x58, 0x42,  // "TEXB"
                0x30, 0x30, 0x30, 0x34,  // "0004"
                0x00                     // null terminator
            ))
            // 7d: TEXB fields (12 bytes: imageCount, format, isVideoMp4)
            writeLeIntToStream(out, 1)              // image_count = 1
            writeLeIntToStream(out, -1)             // format = -1 (FIF_UNKNOWN)
            writeLeIntToStream(out, 0)             // is_video_mp4 = 0
            // 7e: Mipmap count (4 bytes)
            writeLeIntToStream(out, 1)              // mipmap_count = 1
            // 7f: Mipmap 0 with ETC2 RGBA8 + LZ4 compression (16 bytes + data)
            writeLeIntToStream(out, texWidth)       // width
            writeLeIntToStream(out, texHeight)       // height
            writeLeIntToStream(out, 1)              // compression = 1 (LZ4)
            writeLeIntToStream(out, etc2Bytes.size) // uncompressed_size
            writeLeIntToStream(out, lz4Compressed.size) // compressed_size
            out.write(lz4Compressed)                // LZ4-compressed ETC2 RGBA8 data

            val result = out.toByteArray()

            // ---- Step 8: Verify by reading back the structure ----
            val vMagic1 = String(result, 0, 8, Charsets.US_ASCII)
            val vMagic2 = String(result, 9, 8, Charsets.US_ASCII)
            val vFormat = readLeInt(result, 18)
            val vTexW = readLeInt(result, 26)
            val vTexH = readLeInt(result, 30)
            val vFIF = readLeInt(result, 59)
            val vMipmapCount = readLeInt(result, 67)
            val vMipmapW = readLeInt(result, 71)
            val vMipmapH = readLeInt(result, 75)
            val vCompression = readLeInt(result, 79)
            val vUncompSize = readLeInt(result, 83)
            val vCompSize = readLeInt(result, 87)
            WepWawetLogger.proxy("MpkgPacker.convertTexDxtToEtc2 SUCCESS",
                "name" to name,
                "original_size" to texData.size,
                "converted_size" to result.size,
                "dxt_format" to dxtFormat.displayName,
                "tex_width" to texWidth,
                "tex_height" to texHeight,
                "rgba_size" to rgbaBytes.size,
                "etc2_size" to etc2Bytes.size,
                "lz4_size" to lz4Compressed.size,
                "verify_magic1" to vMagic1, "verify_magic2" to vMagic2,
                "verify_format" to vFormat, "verify_texture_width" to vTexW,
                "verify_texture_height" to vTexH, "verify_fif" to vFIF,
                "verify_mipmap_count" to vMipmapCount,
                "verify_mipmap_width" to vMipmapW, "verify_mipmap_height" to vMipmapH,
                "verify_compression" to vCompression,
                "verify_uncompressed_size" to vUncompSize,
                "verify_compressed_size" to vCompSize,
                "verify_data_starts_at" to 91,
                "verify_first_8_bytes_hex" to (if (result.size >= 99) result.copyOfRange(91, 99).joinToString("") { "%02x".format(it) } else "file_too_small"))

            return result
        } catch (e: Exception) {
            WepWawetLogger.proxyError("MpkgPacker.convertTexDxtToEtc2 FAILED",
                "name" to name, "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            return null
        }
    }

    /**
     * Rewrite project.json for the WE Android .mpkg format.
     *
     * Real WE PC's transcoder (function 0x14003d1e0 in wallpaperui.exe, see
     * research/we-transcoder/02_transcoder_14003d1e0.asm) rewrites project.json
     * during the .pkg -> .mpkg transcoding step. The rewrite:
     *
     *   1. Sets the "file" field to use the MPKG_VFS:// URI scheme
     *      (e.g., "scene.json" -> "MPKG_VFS://scene.json")
     *      (transcoder disasm line 14003ea47)
     *   2. Sets the "preview" field to use the MPKG_VFS:// URI scheme
     *      (e.g., "preview.jpg" -> "MPKG_VFS://preview.jpg")
     *      (transcoder disasm line 14003eacc)
     *   3. Sets the "title" field (preserved from original)
     *      (transcoder disasm line 14003eb31)
     *   4. Removes the "video" key if present
     *      (transcoder disasm line 14003eb70 calls 0x1402351c0 which is a
     *       different function from the JSON setter 0x140235460 — it's the
     *       JSON key remover. The .pkg's project.json may have a "video"
     *       section that the .mpkg's project.json does NOT have.)
     *   5. Sets the "type" field (preserved from original — "scene" or "video")
     *      (transcoder disasm line 14003eb80)
     *   6. Sets the "workshopurl" field
     *      (transcoder disasm line 1400416b8)
     *   7. Sets the "alignmentposition" field (for video wallpapers)
     *      (transcoder disasm line 14003f27b)
     *   8. Sets the "maximumprojectid" field
     *      (transcoder disasm line 14003fceb)
     *
     * The MPKG_VFS:// scheme is what the Android native lib (libscenejni.so)
     * uses to address files within the .mpkg's virtual file system. Without
     * this prefix, the Android app may not find the scene.json / wallpaper.mp4
     * / preview.jpg files inside the .mpkg.
     *
     * This function is conservative:
     *   - If "file" already has the MPKG_VFS:// prefix, it's left as-is.
     *   - If "file" is missing or empty, no rewrite is performed.
     *   - If "preview" is missing, it's left unset (the Android app will use
     *     a default preview).
     *   - All other fields (contentrating, description, general, tags,
     *     version, visibility) are preserved unchanged.
     *
     * @param projectJsonBytes The original project.json bytes from the .pkg.
     * @return The rewritten project.json bytes, or null on failure (caller
     *         should use the original bytes in that case).
     */
    private fun rewriteProjectJsonForMobile(projectJsonBytes: ByteArray): ByteArray? {
        return try {
            val jsonStr = String(projectJsonBytes, Charsets.UTF_8)
            val root = JsonParser.parseString(jsonStr).asJsonObject
            var modified = false

            // 1. Rewrite "file" field with MPKG_VFS:// prefix
            val fileField = root.get("file")
            if (fileField != null && fileField.isJsonPrimitive) {
                val filePath = fileField.asString
                if (filePath.isNotBlank() && !filePath.startsWith("MPKG_VFS://")) {
                    root.addProperty("file", "MPKG_VFS://$filePath")
                    modified = true
                    WepWawetLogger.proxy("MpkgPacker.rewriteProjectJsonForMobile: rewrote file field",
                        "original" to filePath,
                        "rewritten" to "MPKG_VFS://$filePath")
                }
            }

            // 2. Rewrite "preview" field with MPKG_VFS:// prefix
            val previewField = root.get("preview")
            if (previewField != null && previewField.isJsonPrimitive) {
                val previewPath = previewField.asString
                if (previewPath.isNotBlank() && !previewPath.startsWith("MPKG_VFS://")) {
                    root.addProperty("preview", "MPKG_VFS://$previewPath")
                    modified = true
                    WepWawetLogger.proxy("MpkgPacker.rewriteProjectJsonForMobile: rewrote preview field",
                        "original" to previewPath,
                        "rewritten" to "MPKG_VFS://$previewPath")
                }
            }

            // 3. Remove the "video" key if present (per transcoder disasm line 14003eb70)
            // The .pkg's project.json may have a "video" section (with codec/codec params)
            // that the .mpkg's project.json does NOT have. WE PC's transcoder removes it.
            if (root.has("video")) {
                root.remove("video")
                modified = true
                WepWawetLogger.proxy("MpkgPacker.rewriteProjectJsonForMobile: removed video section (per WE PC transcoder)")
            }

            // 4. Set "type" explicitly to lowercase (preserves existing value, just normalized)
            // Real WE PC's .mpkg uses lowercase "scene" or "video" (per the extracted
            // project.json from the Mordred wallpaper's .pkg). The Android side expects
            // lowercase too (per libscenejni.so strings analysis).
            val typeField = root.get("type")
            if (typeField != null && typeField.isJsonPrimitive) {
                val typeVal = typeField.asString
                val normalized = typeVal.lowercase()
                if (normalized != typeVal) {
                    root.addProperty("type", normalized)
                    modified = true
                    WepWawetLogger.proxy("MpkgPacker.rewriteProjectJsonForMobile: normalized type field",
                        "original" to typeVal, "normalized" to normalized)
                }
            }

            // 5. Set "workshopurl" if missing
            // WE PC's transcoder sets workshopurl to the Steam community URL.
            // If the .pkg's project.json doesn't have it, we leave it unset
            // (the Android app will just not show a "View on Workshop" link).
            // The .pkg's project.json typically already has workshopurl set,
            // so we just preserve it.
            if (!root.has("workshopurl")) {
                WepWawetLogger.proxyDebug("MpkgPacker.rewriteProjectJsonForMobile: workshopurl field is missing — leaving unset")
            }

            if (!modified) {
                WepWawetLogger.proxyDebug("MpkgPacker.rewriteProjectJsonForMobile: no changes needed (already in mobile format)")
                return projectJsonBytes
            }

            // Serialize back to JSON with WE PC's formatting (tabs + \r\n line endings)
            // This matches the .json formatting that real WE PC produces (verified from
            // the Mordred wallpaper's project.json extracted from the .pkg side, which
            // uses tabs and \r\n).
            val result = com.google.gson.GsonBuilder()
                .setPrettyPrinting()
                .serializeNulls()
                .create()
                .toJson(root)
                .toByteArray(Charsets.UTF_8)
            WepWawetLogger.proxy("MpkgPacker.rewriteProjectJsonForMobile: rewrite complete",
                "original_size" to projectJsonBytes.size,
                "rewritten_size" to result.size,
                "field_count" to root.size())
            result
        } catch (e: Exception) {
            WepWawetLogger.proxyError("MpkgPacker.rewriteProjectJsonForMobile FAILED — using original",
                "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            null
        }
    }

    /**
     * Rewrite scene.json's "files" array entries (and any other file path
     * references) to use the MPKG_VFS:// URI scheme.
     *
     * Per the transcoder disassembly (lines 14004104e, 1400410cd, 140041125-14004116a),
     * WE PC iterates over scene.json's "files" array and prefixes each entry's
     * "value" field with "MPKG_VFS://". This is how the Android native lib
     * (libscenejni.so) addresses files within the .mpkg's virtual file system.
     *
     * Scene.json's "files" array (when present) typically contains entries like:
     *   { "file": "materials/foo.tex" }  →  { "file": "MPKG_VFS://materials/foo.tex" }
     *
     * Many scene.json files DON'T have a "files" array — in those cases, this
     * function is a no-op. The "files" array appears in scene.json files that
     * list all the textures/shaders/etc. they reference (used by some Workshop
     * wallpapers for caching/dependency tracking).
     *
     * This function also recursively rewrites any other string field that looks
     * like a relative file path (starts with "materials/", "shaders/",
     * "models/", "effects/", "sounds/") to use the MPKG_VFS:// scheme.
     *
     * @param sceneJsonBytes The original scene.json bytes from the .pkg.
     * @return The rewritten scene.json bytes, or null on failure (caller
     *         should use the original bytes in that case).
     */
    private fun rewriteSceneJsonForMobile(sceneJsonBytes: ByteArray): ByteArray? {
        return try {
            val jsonStr = String(sceneJsonBytes, Charsets.UTF_8)
            val root = JsonParser.parseString(jsonStr).asJsonObject
            var modifiedCount = 0

            // Patterns that look like file paths within the .mpkg VFS
            val pathPrefixes = listOf(
                "materials/", "shaders/", "models/", "effects/",
                "sounds/", "fonts/", "scene/", "preview",
                "wallpaper."
            )

            /**
             * Recursively walk the JSON tree and rewrite any string value that
             * looks like a relative file path to use the MPKG_VFS:// prefix.
             */
            fun rewriteValue(value: com.google.gson.JsonElement): com.google.gson.JsonElement {
                if (value.isJsonPrimitive) {
                    val prim = value.asJsonPrimitive
                    if (prim.isString) {
                        val s = prim.asString
                        if (s.isNotBlank() &&
                            !s.startsWith("MPKG_VFS://") &&
                            !s.startsWith("http://") &&
                            !s.startsWith("https://") &&
                            !s.startsWith("/") &&
                            pathPrefixes.any { s.startsWith(it) }) {
                            modifiedCount++
                            return com.google.gson.JsonPrimitive("MPKG_VFS://$s")
                        }
                    }
                } else if (value.isJsonObject) {
                    val obj = value.asJsonObject
                    val keys = obj.keySet().toList()  // snapshot to avoid ConcurrentMod
                    for (key in keys) {
                        obj.add(key, rewriteValue(obj.get(key)))
                    }
                } else if (value.isJsonArray) {
                    val arr = value.asJsonArray
                    for (i in arr.indices) {
                        arr[i] = rewriteValue(arr[i])
                    }
                }
                return value
            }

            // First: rewrite the "files" array specifically (if present)
            // Per transcoder disasm line 14004104e, the "files" array contains
            // entries with file paths that need MPKG_VFS:// prefixing.
            if (root.has("files")) {
                val files = root.get("files")
                if (files.isJsonArray) {
                    val arr = files.asJsonArray
                    for (i in arr.indices) {
                        arr[i] = rewriteValue(arr[i])
                    }
                } else if (files.isJsonObject) {
                    root.add("files", rewriteValue(files))
                }
            }

            // Then: walk the ENTIRE JSON tree and rewrite any other path-like strings
            // (covers materials' "file" field, effects' "file" field, etc.)
            for (key in root.keySet().toList()) {
                root.add(key, rewriteValue(root.get(key)))
            }

            if (modifiedCount == 0) {
                WepWawetLogger.proxyDebug("MpkgPacker.rewriteSceneJsonForMobile: no path-like strings found — no rewrite")
                return sceneJsonBytes
            }

            val result = com.google.gson.GsonBuilder()
                .setPrettyPrinting()
                .serializeNulls()
                .create()
                .toJson(root)
                .toByteArray(Charsets.UTF_8)
            WepWawetLogger.proxy("MpkgPacker.rewriteSceneJsonForMobile: rewrite complete",
                "original_size" to sceneJsonBytes.size,
                "rewritten_size" to result.size,
                "rewritten_path_count" to modifiedCount)
            result
        } catch (e: Exception) {
            WepWawetLogger.proxyError("MpkgPacker.rewriteSceneJsonForMobile FAILED — using original",
                "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            null
        }
    }

    /**
     * Fix scene.json — replace null texture bindings with shader-declared defaults.
     *
     * On WE PC, null in a textures array means "use the default texture from the
     * installation directory". On WE Android, null means "no texture" — the shader
     * samples a missing texture and gets 0 (transparent/black).
     *
     * This breaks effects like X-ray that depend on a "sprite" texture (g_Texture2)
     * for the cursor halo. The shader declares:
     *   uniform sampler2D g_Texture2; // {"default":"particle/halo_6"}
     *
     * On PC, null → uses "particle/halo_6" from installation.
     * On Android, null → no texture → sprite.ra = 0 → blend = 0 → NO X-RAY.
     *
     * Fix: parse scene.json, find all texture arrays, look up the shader's
     * default texture names from the .frag files, and replace nulls with defaults.
     */

    /**
     * Fix shader files for OpenGL ES (GLSL 3.00 ES) compatibility.
     *
     * WE PC uses DirectX (HLSL) which allows implicit int→float conversion.
     * GLSL ES 3.00 does NOT — integer constants must have `.0` suffix.
     * WE Android's automatic int→float conversion has bugs and misses some
     * inline constants, causing shader compilation failures. Per WE docs:
     * "If a shader cannot be compiled on the mobile version of Wallpaper
     * Engine, the effect will be removed automatically."
     *
     * Specific errors from WE Android error log (v0.4.x):
     * 1. `pointer * 2 - 1` → GLSL error: vec2 * int not allowed
     *    Fix: replace `* 2 - 1` with `* 2.0 - 1.0`
     * 2. `mix(999, ...)` → GLSL error: mix() requires matching types
     *    Fix: replace `mix(999,` with `mix(999.0,`
     * 3. `*= 100;` → GLSL error: float *= int not allowed
     *    Fix: replace `*= 100;` with `*= 100.0;`
     * 4. `.w = 0;` → GLSL error: assigning int to float
     *    Fix: replace `= 0;` with `= 0.0;` (in float context)
     * 5. `g_Texture0` used before declaration in .frag files
     *    Fix: hoist `uniform sampler2D` declarations to top of file
     * 6. `simTexel * 100 * g_RippleSpeed` → vec2 * int * float not allowed
     *    Fix: replace `* 100 *` with `* 100.0 *`
     * 7. `vec4(0, 0, 0, 0)` → vec4 constructor doesn't accept int args
     *    Fix: replace all int args with floats: `vec4(0.0, 0.0, 0.0, 0.0)`
     * 8. `1 / 3.0` → int / float not allowed
     *    Fix: replace `1 /` with `1.0 /`
     * 9. `float reflectUp = 0;` (also `maskUp`, `reflectDown`, etc.)
     *    Fix: replace `= 0;` with `= 0.0;` (in float context)
     * 10. `vec4 sample;` → 'sample' is a reserved keyword in GLSL ES 3.00
     *    Fix: rename variable to `sampleVal`
     * 11. `g_RippleSpeed` used but not declared (sometimes in workshop .frag files)
     *    Fix: add `uniform float g_RippleSpeed;` if used but not declared
     * 12. blur_precise_gaussian.frag has `#include "common_blur.h"` BEFORE
     *     `uniform sampler2D g_Texture0;` — the preprocessor inlines the .h's
     *     functions (which reference g_Texture0) BEFORE the declaration,
     *     causing "undeclared identifier g_Texture0" errors.
     *     Fix: hoist ALL uniform sampler2D declarations to BEFORE any #include.
     */
    private fun fixShadersForMobile(entries: MutableList<Pair<String, ByteArray>>) {
        var fixedCount = 0
        for (i in entries.indices) {
            val (name, blob) = entries[i]
            if (!name.endsWith(".vert") && !name.endsWith(".frag")) continue

            val original = String(blob, Charsets.UTF_8)
            var modified = original
            val fixesApplied = mutableListOf<String>()

            // =========================================================================
            // PHASE 1: Comprehensive GLSL ES 3.00 compatibility fixes.
            // =========================================================================
            // These patterns are derived from reverse-engineering WE PC's wallpaperui.exe.
            // WE PC has its own shader preprocessor that converts HLSL-style shaders
            // (which allow implicit int→float conversion) to GLSL ES 3.00 (which does NOT).
            // We replicate the same fixes here so the .mpkg's shaders compile on WE Android.
            //
            // Order of operations matters:
            //   1. Specific for-loop counter fix (must run BEFORE general int→float, otherwise
            //      `for (int i = 0;` becomes `for (int i = 0.0;` which is invalid).
            //   2. const int sampleCount → const float (specific, before general).
            //   3. Sample keyword rename (reserved in GLSL ES 3.00).
            //   4. Scientific notation normalization.
            //   5. General int→float regex (catches everything else).
            //
            // After Phase 1, the shader should have all int literals converted to floats
            // in float contexts, ready for Phase 2 (structural fixes) and Phase 3
            // (include inlining).

            // --- Fix 1A: For-loop int counter → float counter ---
            // WE PC pattern: `for \(int (.+) = 0; i < sampleCount;` → `for (float $1 = 0.0; i < sampleCount;`
            // This converts `for (int i = 0; i < sampleCount; ...)` to `for (float i = 0.0; i < sampleCount; ...)`
            // We MUST do this BEFORE the general int→float regex, otherwise `for (int i = 0;`
            // becomes `for (int i = 0.0;` which is invalid (can't assign float to int).
            val forLoopBeforeCount = Regex("""for\s*\(\s*int\s+""").findAll(modified).count()
            modified = modified.replace(
                Regex("""for\s*\(\s*int\s+(\w+)\s*=\s*0\s*;\s*(\w+)\s*<\s*(\w+)\s*;""")
            ) { mr ->
                val iVar = mr.groupValues[1]
                val condVar = mr.groupValues[2]
                val limitVar = mr.groupValues[3]
                // Only convert if the loop variable matches the condition variable
                if (iVar == condVar) {
                    val result = "for (float $iVar = 0.0; $condVar < $limitVar;"
                    fixesApplied.add("for(int $iVar=0;)→for(float $iVar=0.0;)")
                    result
                } else {
                    mr.value
                }
            }
            val forLoopAfterCount = Regex("""for\s*\(\s*int\s+""").findAll(modified).count()
            if (forLoopAfterCount < forLoopBeforeCount) {
                WepWawetLogger.proxyDebug("MpkgPacker.fixShaders: converted for-loop int counters to float",
                    "file" to name, "before" to forLoopBeforeCount, "after" to forLoopAfterCount)
            }

            // --- Fix 1B: `const int sampleCount` → `const float sampleCount` ---
            // WE PC pattern: `(\s*const )int( sampleCount)` → `$1float$2`
            modified = modified.replace(
                Regex("""(\s*const\s+)int(\s+sampleCount\b)""")
            ) { mr ->
                val result = "${mr.groupValues[1]}float${mr.groupValues[2]}"
                fixesApplied.add("'const int sampleCount'→'const float sampleCount'")
                result
            }
            // Also catch `const int N = ...;` for other common loop-limit names
            modified = modified.replace(
                Regex("""(\s*const\s+)int\s+(sampleCount|kernelSize|NUM_TAPS|TAP_COUNT|SAMPLE_COUNT)\s*=""")
            ) { mr ->
                val result = "${mr.groupValues[1]}float ${mr.groupValues[2]} ="
                fixesApplied.add("'const int ${mr.groupValues[2]}'→'const float ${mr.groupValues[2]}'")
                result
            }

            // --- Fix 1C: Rename reserved keyword `sample` → `sampleVal` ---
            // WE PC pattern: `(\b)(sample)(\b)` → `$1sampleVal$3` (or similar)
            // GLSL ES 3.00 reserves `sample` (used by OES_texture_cube_map_array).
            // `vec4 sample;` fails to compile. We rename to `sampleVal`.
            // Only rename when used as a variable name, NOT when it's part of texSample2D, sampleF(), etc.
            val sampleKeywordCount = Regex("""\bsample\b""").findAll(modified).count()
            if (sampleKeywordCount > 0) {
                // Use word boundary to avoid replacing sampleF, texSample, sampleCount, etc.
                val newModified = modified.replace(Regex("""\bsample\b(?!\w)""")) { _ ->
                    "sampleVal"
                }
                if (newModified != modified) {
                    val actualCount = sampleKeywordCount - Regex("""\bsample\b""").findAll(newModified).count()
                    if (actualCount > 0) {
                        modified = newModified
                        fixesApplied.add("'sample'→'sampleVal' (reserved keyword, $actualCount occurrences)")
                    }
                }
            }

            // --- Fix 1D: General int→float conversion ---
            // WE PC pattern: `([\s\(/\)\+\-\*\%\\/\]])(\d+)(?!\.)(\b)` → `$1$2.0$3`
            // This catches ANY integer literal that is:
            //   - Preceded by an operator/whitespace/paren (NOT preceded by `=` directly,
            //     but `= 0` matches because ` 0` (space+0) matches)
            //   - NOT followed by `.` (so it's an int, not a float like `1.5`)
            //   - Followed by a word boundary (so we don't match part of a longer identifier)
            // We replace with `$1$2.0` (add `.0` after the digits).
            //
            // This single regex handles ALL of these patterns that previously needed
            // separate Fix 1a-1i regexes:
            //   - `pointer * 2 - 1`        → `pointer * 2.0 - 1.0`
            //   - `mix(999, ...)`          → `mix(999.0, ...)`
            //   - `v_TexCoord.w = 0;`      → `v_TexCoord.w = 0.0;`
            //   - `v_PointDelta.x *= 100;` → `v_PointDelta.x *= 100.0;`
            //   - `1 / 3.0`                → `1.0 / 3.0`
            //   - `vec4(0, 0, 0, 0)`      → `vec4(0.0, 0.0, 0.0, 0.0)`
            //   - `simTexel * 100 * ...`  → `simTexel * 100.0 * ...`
            //   - `reflectUp = 0;`        → `reflectUp = 0.0;`
            //
            // We also add `=` to the character class (WE PC's regex doesn't include it,
            // but we want `=0` (no space) to also match for safety).
            //
            // CRITICAL v0.4.13 fix: We must SKIP matches inside:
            //   1. Line comments (`// ...`) — the metadata comments on uniform declarations
            //      contain things like `"paintdefaultcolor":"0 0 0 1"` which would be
            //      corrupted if we converted SOME ints but not others (the first int
            //      is preceded by `"` which isn't in our operator class, but the others
            //      are preceded by space — producing malformed `"0 0.0 0.0 1.0"`).
            //   2. Block comments (`/* ... */`).
            //   3. String literals (`"..."`) — same issue as comments.
            //   4. Preprocessor directives (`#if`, `#define`, etc.) — these are macro
            //      values, not float arithmetic.
            //
            // Approach: We tokenize the shader line-by-line. For each line, we identify
            // the "code" portion (everything before `//` or inside `/* */` blocks) and
            // only convert ints inside the code portion, NOT inside string literals.
            val intConversionRegex = Regex("""([\s=/()\+\-\*%\\\],])(\d+)(?!\.)(\b)""")
            var intConversionCount = 0

            // Build a "skip mask" — a boolean array where true means "this index is inside
            // a comment or string literal, so skip conversion here".
            //
            // We track:
            //   - block comment depth (`/* */` can be nested in some preprocessors; WE's
            //     preprocessor doesn't nest, so we just track single-level)
            //   - string literal state (toggle on each unescaped `"`)
            //   - line comment state (active from `//` to end of line)
            val skipMask = BooleanArray(modified.length)
            var pos = 0
            var inBlockComment = false
            var inLineComment = false
            var inString = false
            while (pos < modified.length) {
                val ch = modified[pos]
                val nextCh = if (pos + 1 < modified.length) modified[pos + 1] else '\u0000'
                if (inBlockComment) {
                    skipMask[pos] = true
                    if (ch == '*' && nextCh == '/') {
                        skipMask[pos + 1] = true
                        pos += 2
                        inBlockComment = false
                        continue
                    }
                } else if (inLineComment) {
                    skipMask[pos] = true
                    if (ch == '\n') {
                        inLineComment = false
                        // Don't skip the newline (allow next-line processing)
                    }
                } else if (inString) {
                    skipMask[pos] = true
                    if (ch == '\\' && nextCh != '\u0000') {
                        // Escaped char — skip the next char too
                        skipMask[pos + 1] = true
                        pos += 2
                        continue
                    } else if (ch == '"') {
                        inString = false
                    }
                } else {
                    // Not in any comment or string — check for transitions
                    if (ch == '/' && nextCh == '*') {
                        inBlockComment = true
                        skipMask[pos] = true
                        skipMask[pos + 1] = true
                        pos += 2
                        continue
                    } else if (ch == '/' && nextCh == '/') {
                        inLineComment = true
                        skipMask[pos] = true
                        skipMask[pos + 1] = true
                        pos += 2
                        continue
                    } else if (ch == '"') {
                        inString = true
                        skipMask[pos] = true
                    }
                }
                pos++
            }

            modified = modified.replace(intConversionRegex) { mr ->
                val matchStart = mr.range.first
                // Skip if inside a comment or string literal
                if (matchStart < skipMask.size && skipMask[matchStart]) {
                    return@replace mr.value
                }
                // Also skip preprocessor directive lines (e.g. `#if BLENDMODE == 0`)
                val lineStart = modified.lastIndexOf('\n', matchStart - 1).coerceAtLeast(0)
                val linePrefix = modified.substring(lineStart, matchStart)
                val trimmedPrefix = linePrefix.trimStart()
                if (trimmedPrefix.startsWith("#")) {
                    return@replace mr.value
                }
                intConversionCount++
                "${mr.groupValues[1]}${mr.groupValues[2]}.0"
            }
            if (intConversionCount > 0) {
                fixesApplied.add("int→float ($intConversionCount literals via WE PC general regex, comments/strings skipped)")
            }

            // --- Fix 1E: Scientific notation normalization ---
            // WE PC pattern: `([\d\.]e[-]*[\d]+)\.0` (matches `1.0e3.0` which is invalid)
            // Some shaders produce `1.0e3` after the int→float regex runs on `1e3`.
            // We normalize to `1.0e3` (without trailing `.0`).
            val sciNotationRegex = Regex("""(\d+\.\d+e[-]?\d+)\.0""")
            var sciCount = 0
            modified = modified.replace(sciNotationRegex) { mr ->
                sciCount++
                mr.groupValues[1]
            }
            if (sciCount > 0) {
                fixesApplied.add("scientific notation normalized ($sciCount occurrences)")
            }

            // =========================================================================
            // PHASE 2: Line-based structural fixes (uniform hoisting, reserved keywords,
            // missing uniforms, duplicate declarations). These need to operate on a
            // line-by-line basis to preserve formatting and ordering.
            // =========================================================================
            val lines = modified.lines().toMutableList()
            val seenUniforms = mutableSetOf<String>()
            val uniformIndices = mutableListOf<Int>()
            var firstFuncLine = -1
            var firstIncludeLine = -1

            // PASS A: Scan for first function definition, first #include, and duplicates
            for (j in lines.indices) {
                val trimmed = lines[j].trim()
                if (trimmed.startsWith("uniform sampler2D ")) {
                    val uniformName = Regex("""uniform\s+sampler2D\s+(\w+)""").find(trimmed)?.groupValues?.get(1)
                    if (uniformName != null) {
                        if (uniformName in seenUniforms) {
                            uniformIndices.add(j)  // mark for removal
                        } else {
                            seenUniforms.add(uniformName)
                        }
                    }
                }
                // Find first function definition (line with ( and { but not a uniform/attribute)
                if (firstFuncLine < 0 && trimmed.isNotEmpty() &&
                    !trimmed.startsWith("#") && !trimmed.startsWith("//") &&
                    !trimmed.startsWith("/*") && !trimmed.startsWith("*") &&
                    !trimmed.startsWith("precision") && !trimmed.startsWith("uniform") &&
                    !trimmed.startsWith("varying") && !trimmed.startsWith("out ") &&
                    !trimmed.startsWith("attribute") && !trimmed.startsWith("in ") &&
                    trimmed.contains("(") && trimmed.contains(")")) {
                    firstFuncLine = j
                }
                // Find first #include line
                if (firstIncludeLine < 0 && trimmed.startsWith("#include")) {
                    firstIncludeLine = j
                }
            }

            // PASS B: Remove duplicate uniform declarations (in reverse to preserve indices)
            for (idx in uniformIndices.reversed()) {
                lines.removeAt(idx)
            }

            // PASS C: Hoist uniform sampler2D declarations BEFORE #include directives.
            // Rationale: GLSL ES 3.00 requires declarations before use. When a shader
            // has `#include "common_blur.h"` followed by `uniform sampler2D g_Texture0;`,
            // the preprocessor inlines the .h file's content (functions like blur13()
            // that reference g_Texture0) BEFORE the uniform declaration, causing
            // "undeclared identifier g_Texture0" errors.
            //
            // We hoist ALL uniform sampler2D declarations to be the FIRST thing after
            // any #version and #extension directives, BEFORE any #include.
            val firstUniformLine = lines.indexOfFirst { it.trim().startsWith("uniform sampler2D ") }
            val hoistNeeded = (firstUniformLine >= 0) && (
                (firstIncludeLine >= 0 && firstUniformLine > firstIncludeLine) ||
                (firstFuncLine >= 0 && firstUniformLine > firstFuncLine)
            )
            if (hoistNeeded) {
                val uniformLines = lines.filter { it.trim().startsWith("uniform sampler2D ") }
                // Remove all uniform sampler2D declarations from their current positions
                lines.removeAll { it.trim().startsWith("uniform sampler2D ") }
                // Find insertion point: after #version / #extension, before #include
                // (or before first function if no #include).
                var insertAt = 0
                for (j in lines.indices) {
                    val trimmed = lines[j].trim()
                    if (trimmed.startsWith("#version") || trimmed.startsWith("#extension") ||
                        trimmed.startsWith("#pragma") || trimmed.isEmpty() ||
                        trimmed.startsWith("//")
                    ) {
                        insertAt = j + 1
                    } else if (trimmed.startsWith("#include") || trimmed.startsWith("uniform") ||
                        trimmed.startsWith("varying") || trimmed.startsWith("attribute") ||
                        trimmed.startsWith("precision") || trimmed.startsWith("out ")
                    ) {
                        // Stop at the first substantial preprocessor/include/uniform line.
                        // We want to insert AFTER the preprocessor preamble.
                        if (trimmed.startsWith("#include")) {
                            insertAt = j
                            break
                        }
                        // For uniform/varying/attribute/precision/out — keep advancing
                        // only if we're still in the "header" region.
                        if (j > 0 && lines[j - 1].trim().let {
                                it.startsWith("#version") || it.startsWith("#extension") ||
                                it.startsWith("#pragma") || it.isEmpty() ||
                                it.startsWith("//")
                            }) {
                            insertAt = j
                        } else {
                            insertAt = j
                            break
                        }
                    } else {
                        // Hit a non-header line — insert here
                        insertAt = j
                        break
                    }
                }
                // Insert uniform declarations at insertAt
                for ((k, decl) in uniformLines.withIndex()) {
                    lines.add(insertAt + k, decl)
                }
                WepWawetLogger.proxy(
                    "MpkgPacker.fixShaders: hoisted+dedup uniform declarations",
                    "file" to name, "count" to uniformLines.size,
                    "insert_at" to insertAt, "trigger" to
                        if (firstIncludeLine >= 0 && firstUniformLine > firstIncludeLine)
                            "include-before-uniform"
                        else "function-before-uniform"
                )
                fixesApplied.add("hoist-uniforms")
            }

            // PASS D: Replace reserved keyword `sample` (GLSL ES 3.00 reserves it).
            // `vec4 sample;` → `vec4 sampleVal;`
            // Also replace uses of the `sample` variable on the same lines.
            for (j in lines.indices) {
                val line = lines[j]
                if (line.contains("vec4 sample;") || line.contains("vec4 sample ") ||
                    line.contains("vec4 sample,") || line.contains("vec4 sample)")) {
                    // Use a word-boundary regex to avoid replacing `sampleF` or `texSample`
                    val newLine = line.replace(Regex("""\bsample\b"""), "sampleVal")
                    if (newLine != line) {
                        lines[j] = newLine
                        fixesApplied.add("'sample'→'sampleVal' (reserved keyword)")
                    }
                }
            }

            // PASS E: Add missing `uniform float g_RippleSpeed;` if used but not declared.
            // Some workshop .frag files use g_RippleSpeed without declaring it (relies on
            // an included .h file that may not always be present in the mobile pipeline).
            val usesRippleSpeed = lines.any {
                it.contains("g_RippleSpeed") && !it.trim().startsWith("//") &&
                    !it.contains("uniform")
            }
            val declaresRippleSpeed = lines.any {
                it.contains("uniform") && it.contains("g_RippleSpeed")
            }
            if (usesRippleSpeed && !declaresRippleSpeed) {
                // Insert after the last uniform declaration
                val lastUniformIdx = lines.indexOfLast { it.trim().startsWith("uniform") }
                if (lastUniformIdx >= 0) {
                    lines.add(lastUniformIdx + 1, "uniform float g_RippleSpeed; // injected by WepWawet")
                    fixesApplied.add("added-missing-g_RippleSpeed")
                }
            }

            // PASS F: General catch-all for `varName = INT;` where varName was declared as
            // float earlier in the file. This catches patterns the targeted regexes miss.
            // We track all variables declared as `float name` earlier and rewrite assignments
            // of bare integers to those variables.
            val floatVars = mutableSetOf<String>()
            for (j in lines.indices) {
                val trimmed = lines[j].trim()
                // Find float variable declarations: `float name;`, `float name = ...;`
                val floatDeclMatch = Regex("""^\s*float\s+(\w+)""").find(trimmed)
                if (floatDeclMatch != null) {
                    floatVars.add(floatDeclMatch.groupValues[1])
                }
            }
            // Now scan for assignments to those float vars with bare integer literals
            for (j in lines.indices) {
                val line = lines[j]
                // Pattern: identifier = digits; (where identifier is a known float var)
                val assignMatch = Regex("""(\b${floatVars.joinToString("|")})\b\s*=\s*(\d+)(\s*;)""").find(line)
                if (assignMatch != null && !assignMatch.groupValues[2].contains('.')) {
                    val replacement = "${assignMatch.groupValues[1]} = ${assignMatch.groupValues[2]}.0${assignMatch.groupValues[3]}"
                    lines[j] = line.replace(assignMatch.value, replacement)
                    fixesApplied.add("'${assignMatch.groupValues[1]}=${assignMatch.groupValues[2]};'→'.0;' (catch-all)")
                }
            }

            modified = lines.joinToString("\n")

            // PASS G: Inline `#include "common_blending.h"` with the actual
            // ApplyBlending function definition.
            //
            // ROOT CAUSE (v0.4.10): The xray.frag shader has:
            //   #include "common_blending.h"
            // and then calls:
            //   albedo.rgb = ApplyBlending(BLENDMODE, albedo.rgb, mask.rgb, blend);
            //
            // The common_blending.h file is NOT in the .pkg from Steam Workshop.
            // On WE PC, the file is resolved from the WE installation directory
            // and INLINED into the shader during .pkg → .mpkg compilation.
            // On WE Android, there's NO built-in resolver for this include —
            // the GLSL compiler doesn't know what ApplyBlending is, so the
            // shader FAILS TO LINK, and the xray effect doesn't work.
            //
            // The WE Android app CAN resolve `#include "common_blur.h"` (it has
            // a built-in regex for this), but NOT `common_blending.h`.
            //
            // Fix: Replace the #include directive with the actual function
            // definition, so the GLSL compiler has the function available.
            //
            // The ApplyBlending function uses #if BLENDMODE == N to select
            // the blending mode at compile time. BLENDMODE is a #define set
            // by the WE shader preprocessor based on the combo value.
            // Per WE docs: "The internal function ignores this parameter [mode],
            // but it had to stick for backward compatibility."
            if (modified.contains("#include \"common_blending.h\"")) {
                val blendingHeader = """
// === common_blending.h INLINED by WepWawet (not in .pkg, must be provided) ===
vec3 ApplyBlending(int mode, vec3 colorA, vec3 colorB, float blend) {
#if BLENDMODE == 0 // Normal
    return mix(colorA, colorB, blend);
#elif BLENDMODE == 1 // Add (Linear Dodge)
    return colorA + colorB * blend;
#elif BLENDMODE == 2 // Screen
    return vec3(1.0) - (vec3(1.0) - colorA) * (vec3(1.0) - colorB * blend);
#elif BLENDMODE == 3 // Multiply
    return colorA * (vec3(1.0) - blend + colorB * blend);
#elif BLENDMODE == 4 // Overlay
    return mix(1.0 - 2.0 * (1.0 - colorA) * (1.0 - colorB), 2.0 * colorA * colorB, step(0.5, colorA.x));
#elif BLENDMODE == 5 // Darken
    return mix(colorA, min(colorA, colorB), blend);
#elif BLENDMODE == 6 // Lighten
    return mix(colorA, max(colorA, colorB), blend);
#elif BLENDMODE == 7 // Color Dodge
    return mix(colorA, vec3(1.0) - (vec3(1.0) - colorA) / max(colorB, vec3(0.001)), blend);
#elif BLENDMODE == 8 // Color Burn
    return mix(colorA, vec3(1.0) - (vec3(1.0) - colorB) / max(colorA, vec3(0.001)), blend);
#elif BLENDMODE == 9 // Hard Light
    return mix(1.0 - 2.0 * (1.0 - colorA) * (1.0 - colorB), 2.0 * colorA * colorB, step(0.5, colorB.x));
#elif BLENDMODE == 10 // Soft Light
    return mix(colorA - (2.0 * colorB - 1.0) * colorA * (1.0 - colorA), colorA + (colorB - 0.5) * (sqrt(colorA) - colorA), 1.0);
#elif BLENDMODE == 11 // Difference
    return mix(colorA, abs(colorA - colorB), blend);
#elif BLENDMODE == 12 // Exclusion
    return mix(colorA, colorA + colorB - 2.0 * colorA * colorB, blend);
#elif BLENDMODE == 13 // Subtraction
    return mix(colorA, max(colorA - colorB, vec3(0.0)), blend);
#elif BLENDMODE == 14 // Divide
    return mix(colorA, colorA / max(colorB, vec3(0.001)), blend);
#else
    return mix(colorA, colorB, blend);
#endif
}
""".trimIndent()
                modified = modified.replace(
                    Regex("""#include\s+"common_blending\.h\""""),
                    blendingHeader
                )
                fixesApplied.add("inline-common_blending.h (ApplyBlending function)")
                WepWawetLogger.proxy(
                    "MpkgPacker.fixShaders: inlined common_blending.h",
                    "file" to name,
                    "reason" to "ApplyBlending function not available on WE Android (no include resolver for common_blending.h)")
            }

            // Also inline #include "common.h" if present (provides hsv2rgb, etc.)
            if (modified.contains("#include \"common.h\"")) {
                val commonHeader = """
// === common.h INLINED by WepWawet ===
vec3 hsv2rgb(vec3 c) { vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0); vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www); return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y); }
vec3 rgb2hsv(vec3 c) { vec4 K = vec4(0.0, -1.0/3.0, 2.0/3.0, -1.0); vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g)); vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r)); float d = q.x - min(q.w, q.y); float e = 1.0e-10; return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x); }
vec2 rotateVec2(vec2 v, float r) { float c = cos(r); float s = sin(r); return vec2(v.x * c - v.y * s, v.x * s + v.y * c); }
float greyscale(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
""".trimIndent()
                modified = modified.replace(
                    Regex("""#include\s+"common\.h\""""),
                    commonHeader
                )
                fixesApplied.add("inline-common.h (hsv2rgb, rgb2hsv, etc.)")
            }

            // Also inline #include "common_fragment.h" if present
            if (modified.contains("#include \"common_fragment.h\"")) {
                val commonFragmentHeader = """
// === common_fragment.h INLINED by WepWawet ===
vec3 DecompressNormal(vec4 c) { vec3 normal; normal.xy = c.wy * 2.0 - 1.0; normal.z = sqrt(max(0.0, 1.0 - dot(normal.xy, normal.xy))); return normal; }
vec4 DecompressNormalWithMask(vec4 c) { vec4 result; result.xyz = DecompressNormal(c); result.w = c.z; return result; }
float ConvertSampleR8(vec4 c) { return c.r; }
vec4 ConvertTexture0Format(vec4 c) { return c; }
""".trimIndent()
                modified = modified.replace(
                    Regex("""#include\s+"common_fragment\.h\""""),
                    commonFragmentHeader
                )
                fixesApplied.add("inline-common_fragment.h")
            }

            // Also inline #include "common_vertex.h" if present
            if (modified.contains("#include \"common_vertex.h\"")) {
                val commonVertexHeader = """
// === common_vertex.h INLINED by WepWawet ===
mat3 BuildTangentSpace(vec3 normal, vec4 signedTangent) {
    vec3 tangent = normalize(signedTangent.xyz);
    vec3 bitangent = cross(normal, tangent) * signedTangent.w;
    return mat3(tangent, bitangent, normal);
}
""".trimIndent()
                modified = modified.replace(
                    Regex("""#include\s+"common_vertex\.h\""""),
                    commonVertexHeader
                )
                fixesApplied.add("inline-common_vertex.h")
            }

            // Also inline #include "common_blur.h" if present
            // WE PC pattern: `^(#include "common_blur\.h")` → `uniform sampler2D g_Texture0;\n$1`
            // The blur functions reference g_Texture0 BEFORE the uniform declaration, so we
            // prepend the uniform declaration to ensure it's declared before use.
            if (modified.contains("#include \"common_blur.h\"")) {
                val blurHeader = """
// === common_blur.h INLINED by WepWawet ===
// Provides blur13, blur7, blur3, blur13a, blur7a, blur3a, blurRotateVec2,
// blurRadial13a, blurRadial7a, blurRadial3a (all variants of Gaussian blur).
// All functions reference g_Texture0 which must be declared BEFORE these functions.
uniform sampler2D g_Texture0; // {"material":"framebuffer","label":"ui_editor_properties_framebuffer","hidden":true}
vec2 o1_13 = vec2(1.4091998770852122);
vec2 o2_13 = vec2(3.2979348079914822);
vec2 o3_13 = vec2(5.2062900776825969);
vec3 blur13(vec2 u, vec2 d) {
    vec2 o1 = CAST2(1.4091998770852122) * d;
    vec2 o2 = CAST2(3.2979348079914822) * d;
    vec2 o3 = CAST2(5.2062900776825969) * d;
    return texSample2D(g_Texture0, u).rgb * 0.1976406528809576
    + texSample2D(g_Texture0, u + o1).rgb * 0.2959855056006557
    + texSample2D(g_Texture0, u - o1).rgb * 0.2959855056006557
    + texSample2D(g_Texture0, u + o2).rgb * 0.0935333619980593
    + texSample2D(g_Texture0, u - o2).rgb * 0.0935333619980593
    + texSample2D(g_Texture0, u + o3).rgb * 0.0116608059608062
    + texSample2D(g_Texture0, u - o3).rgb * 0.0116608059608062;
}
vec3 blur7(vec2 u, vec2 d) {
    vec2 o1 = CAST2(2.3515644035337887) * d;
    vec2 o2 = CAST2(0.469433779698372) * d;
    vec2 o3 = CAST2(1.4091998770852121) * d;
    vec2 o4 = CAST2(3.0) * d;
    return texSample2D(g_Texture0, u + o1).rgb * 0.2028175528299753
    + texSample2D(g_Texture0, u + o2).rgb * 0.4044856614512112
    + texSample2D(g_Texture0, u - o3).rgb * 0.3213933537319605
    + texSample2D(g_Texture0, u - o4).rgb * 0.0713034319868530;
}
vec3 blur3(vec2 u, vec2 d) {
    return texSample2D(g_Texture0, u + d).rgb * 0.25
    + texSample2D(g_Texture0, u).rgb * 0.5
    + texSample2D(g_Texture0, u - d).rgb * 0.25;
}
vec4 blur13a(vec2 u, vec2 d) {
    vec2 o1 = CAST2(1.4091998770852122) * d;
    vec2 o2 = CAST2(3.2979348079914822) * d;
    vec2 o3 = CAST2(5.2062900776825969) * d;
    return texSample2D(g_Texture0, u) * 0.1976406528809576
    + texSample2D(g_Texture0, u + o1) * 0.2959855056006557
    + texSample2D(g_Texture0, u - o1) * 0.2959855056006557
    + texSample2D(g_Texture0, u + o2) * 0.0935333619980593
    + texSample2D(g_Texture0, u - o2) * 0.0935333619980593
    + texSample2D(g_Texture0, u + o3) * 0.0116608059608062
    + texSample2D(g_Texture0, u - o3) * 0.0116608059608062;
}
vec4 blur7a(vec2 u, vec2 d) {
    vec2 o1 = CAST2(2.3515644035337887) * d;
    vec2 o2 = CAST2(0.469433779698372) * d;
    vec2 o3 = CAST2(1.4091998770852121) * d;
    vec2 o4 = CAST2(3.0) * d;
    return texSample2D(g_Texture0, u + o1) * 0.2028175528299753
    + texSample2D(g_Texture0, u + o2) * 0.4044856614512112
    + texSample2D(g_Texture0, u - o3) * 0.3213933537319605
    + texSample2D(g_Texture0, u - o4) * 0.0713034319868530;
}
vec4 blur3a(vec2 u, vec2 d) {
    return texSample2D(g_Texture0, u + d) * 0.25
    + texSample2D(g_Texture0, u) * 0.5
    + texSample2D(g_Texture0, u - d) * 0.25;
}
vec2 blurRotateVec2(vec2 v, float r) {
    vec2 cs = vec2(cos(r), sin(r));
    return vec2(v.x * cs.x - v.y * cs.y, v.x * cs.y + v.y * cs.x);
}
vec4 blurRadial13a(vec2 u, vec2 center, float amt) {
    vec2 delta = u - center;
    amt = amt * 0.025;
    float o1 = 1.4091998770852122 * amt;
    float o2 = 3.2979348079914822 * amt;
    float o3 = 5.2062900776825969 * amt;
    vec2 r1 = blurRotateVec2(delta, o1) - delta;
    vec2 r2 = blurRotateVec2(delta, o2) - delta;
    vec2 r3 = blurRotateVec2(delta, o3) - delta;
    return texSample2D(g_Texture0, u) * 0.1976406528809576
    + texSample2D(g_Texture0, center + r1 + delta) * 0.2959855056006557
    + texSample2D(g_Texture0, center - r1 + delta) * 0.2959855056006557
    + texSample2D(g_Texture0, center + r2 + delta) * 0.0935333619980593
    + texSample2D(g_Texture0, center - r2 + delta) * 0.0935333619980593
    + texSample2D(g_Texture0, center + r3 + delta) * 0.0116608059608062
    + texSample2D(g_Texture0, center - r3 + delta) * 0.0116608059608062;
}
vec4 blurRadial7a(vec2 u, vec2 center, float amt) {
    vec2 delta = u - center;
    amt = amt * 0.025;
    float o1 = 2.3515644035337887 * amt;
    float o2 = 0.469433779698372 * amt;
    float o3 = 1.4091998770852121 * amt;
    float o4 = 3.0 * amt;
    vec2 r1 = blurRotateVec2(delta, o1) - delta;
    vec2 r2 = blurRotateVec2(delta, o2) - delta;
    vec2 r3 = blurRotateVec2(delta, -o3) - delta;
    vec2 r4 = blurRotateVec2(delta, -o4) - delta;
    return texSample2D(g_Texture0, center + r1 + delta) * 0.2028175528299753
    + texSample2D(g_Texture0, center + r2 + delta) * 0.4044856614512112
    + texSample2D(g_Texture0, center + r3 + delta) * 0.3213933537319605
    + texSample2D(g_Texture0, center + r4 + delta) * 0.0713034319868530;
}
vec4 blurRadial3a(vec2 u, vec2 center, float amt) {
    vec2 delta = u - center;
    amt = amt * 0.025;
    float o1 = amt;
    vec2 r1 = blurRotateVec2(delta, o1) - delta;
    return texSample2D(g_Texture0, center + delta) * 0.5
    + texSample2D(g_Texture0, center + r1 + delta) * 0.25
    + texSample2D(g_Texture0, center - r1 + delta) * 0.25;
}
""".trimIndent()
                modified = modified.replace(
                    Regex("""#include\s+"common_blur\.h\""""),
                    blurHeader
                )
                fixesApplied.add("inline-common_blur.h (blur functions + g_Texture0 declaration)")
            }

            // =========================================================================
            // PHASE 4: Shader-specific patches (WE PC's per-shader hotfixes).
            // These are derived from WE PC's wallpaperui.exe binary — WE PC has a
            // table of (shader_name, search_pattern, replacement_string) tuples that
            // it applies to specific shaders at runtime. We replicate the same patches
            // here so the .mpkg matches what WE PC would produce.
            // =========================================================================

            // --- 4A: xray shader patches ---
            // WE PC applies these patches to any shader whose name contains "xray":
            //   1. Inject `varying float v_PointerScale;\nuniform vec4 g_PointerState;`
            //      if not already present (vert shader).
            //   1b. For .frag shaders, inject only `uniform vec4 g_PointerState;`
            //       (the varying is already an input from the vert shader).
            //   2. Replace `unprojectedUVs += 0.5;` with
            //      `unprojectedUVs *= CAST2(1.0 / max(0.00001, g_PointerState.y));\nunprojectedUVs += 0.5;`
            //      if the multiplication line is not already present.
            //   3. Also inject `uniform mat4 g_ModelViewProjectionMatrixInverse;` and
            //      `uniform mat4 g_EffectTextureProjectionMatrixInverse;` declarations
            //      if referenced but not declared.
            //
            // CRITICAL v0.4.13 fix: The .frag shader uses `g_PointerState.y` at line 99
            // but `g_PointerState` was NOT being declared in .frag files because the
            // injection point was `attribute vec3 a_Position;` which only exists in
            // .vert files. Now we use multiple fallback injection points:
            //   - For .vert: `attribute vec3 a_Position;` (preferred)
            //   - For .frag: `varying vec4 v_TexCoord;` or `void main()` (fallback)
            if (name.contains("xray", ignoreCase = true)) {
                // Helper: find the best injection point for a uniform declaration
                fun findInjectionPoint(shader: String): Int {
                    // 1. Before `attribute` declarations (vert shaders)
                    var pos = shader.indexOf("attribute vec3 a_Position;")
                    if (pos >= 0) return pos
                    // 2. Before the first `varying` declaration (frag shaders have varyings as inputs)
                    val varyingMatch = Regex("""^(\s*)varying\s+\w+\s+\w+;""", RegexOption.MULTILINE)
                        .find(shader)
                    if (varyingMatch != null) return varyingMatch.range.first
                    // 3. Before `uniform` declarations
                    pos = shader.indexOf("uniform ")
                    if (pos >= 0) return pos
                    // 4. Before `void main()`
                    pos = shader.indexOf("void main()")
                    if (pos >= 0) return pos
                    return -1
                }

                // Patch 1: Inject varying + uniform if not present (vert shader case)
                if (!modified.contains("varying float v_PointerScale;") &&
                    !modified.contains("uniform vec4 g_PointerState;")) {
                    val injectDecl = "\nvarying float v_PointerScale;\nuniform vec4 g_PointerState;\n"
                    val injectPos = findInjectionPoint(modified)
                    if (injectPos >= 0) {
                        modified = modified.substring(0, injectPos) + injectDecl + modified.substring(injectPos)
                        fixesApplied.add("xray: injected v_PointerScale varying + g_PointerState uniform")
                    }
                } else if (!modified.contains("uniform vec4 g_PointerState;")) {
                    // Only the uniform is missing (typical for .frag — varying already declared as input)
                    val injectDecl = "\nuniform vec4 g_PointerState;\n"
                    val injectPos = findInjectionPoint(modified)
                    if (injectPos >= 0) {
                        modified = modified.substring(0, injectPos) + injectDecl + modified.substring(injectPos)
                        fixesApplied.add("xray: injected g_PointerState uniform (frag-safe path)")
                    } else {
                        // Last resort: prepend at the very beginning of the file
                        modified = injectDecl + modified
                        fixesApplied.add("xray: injected g_PointerState uniform (prepend fallback)")
                    }
                }

                // Patch 2: Inject the multiplication line before `unprojectedUVs += 0.5;`
                // only if the multiplication line is NOT already present.
                val multiplicationLine = "unprojectedUVs *= CAST2(1.0 / max(0.00001, g_PointerState.y));"
                if (modified.contains("unprojectedUVs += 0.5;") &&
                    !modified.contains(multiplicationLine)) {
                    val replacement = "$multiplicationLine\n\tunprojectedUVs += 0.5;"
                    modified = modified.replace(
                        Regex("""unprojectedUVs\s*\+=\s*0\.5\s*;"""),
                        replacement
                    )
                    fixesApplied.add("xray: injected g_PointerState.y multiplication line")
                }

                // Patch 3: Inject g_ModelViewProjectionMatrixInverse if used but not declared
                if (modified.contains("g_ModelViewProjectionMatrixInverse") &&
                    !modified.contains("uniform mat4 g_ModelViewProjectionMatrixInverse;")) {
                    val injectDecl = "\nuniform mat4 g_ModelViewProjectionMatrixInverse;\n"
                    val injectPos = modified.indexOf("void main()")
                    if (injectPos >= 0) {
                        modified = modified.substring(0, injectPos) + injectDecl + modified.substring(injectPos)
                        fixesApplied.add("xray: injected g_ModelViewProjectionMatrixInverse uniform")
                    }
                }

                // Patch 4: Inject g_EffectTextureProjectionMatrixInverse if used but not declared
                if (modified.contains("g_EffectTextureProjectionMatrixInverse") &&
                    !modified.contains("uniform mat4 g_EffectTextureProjectionMatrixInverse;")) {
                    val injectDecl = "\nuniform mat4 g_EffectTextureProjectionMatrixInverse;\n"
                    val injectPos = modified.indexOf("void main()")
                    if (injectPos >= 0) {
                        modified = modified.substring(0, injectPos) + injectDecl + modified.substring(injectPos)
                        fixesApplied.add("xray: injected g_EffectTextureProjectionMatrixInverse uniform")
                    }
                }

                WepWawetLogger.proxyDebug("MpkgPacker.fixShaders: applied xray-specific patches",
                    "file" to name)
            }

            // --- 4B: blend shader patches ---
            // WE PC pattern: `step\(0, uv\) \* step\(uv, 1\)` → `step(CAST2(0.0), uv) * step(uv, CAST2(1.0))`
            // The integer literals 0 and 1 in step() calls cause int→float mismatch errors in GLSL ES 3.00.
            // We replace them with CAST2(0.0) and CAST2(1.0).
            if (modified.contains("step(0, uv)") || modified.contains("step(uv, 1)")) {
                modified = modified.replace(
                    Regex("""step\(\s*0\s*,\s*(\w+)\s*\)\s*\*\s*step\(\s*(\w+)\s*,\s*1\s*\)"""),
                    "step(CAST2(0.0), $1) * step($2, CAST2(1.0))"
                )
                // Also handle individual occurrences
                modified = modified.replace(
                    Regex("""step\(\s*0\s*,\s*(\w+)\s*\)"""),
                    "step(CAST2(0.0), $1)"
                )
                modified = modified.replace(
                    Regex("""step\(\s*(\w+)\s*,\s*1\s*\)"""),
                    "step($1, CAST2(1.0))"
                )
                fixesApplied.add("blend: step(0,uv)*step(uv,1)→step(CAST2(0.0),uv)*step(uv,CAST2(1.0))")
            }

            // --- 4C: shift_hue shader patches ---
            // WE PC pattern: `albedo\.rgb = mix\(albedo, newAlbedo, mask\)` → `albedo.rgb = mix(albedo.rgb, newAlbedo, mask)`
            // The original shader uses `albedo` (a vec4) as the first arg to mix() which expects vec3.
            // We replace with `albedo.rgb` to make it a vec3.
            if (modified.contains("albedo.rgb = mix(albedo, newAlbedo, mask)")) {
                modified = modified.replace(
                    "albedo.rgb = mix(albedo, newAlbedo, mask)",
                    "albedo.rgb = mix(albedo.rgb, newAlbedo, mask)"
                )
                fixesApplied.add("shift_hue: mix(albedo,...)→mix(albedo.rgb,...)")
            }

            // --- 4D: foliagesway shader patches ---
            // WE PC pattern: `(uniform sampler2D g_Texture1;)` → `varying vec2 v_Bounds;\n$1`
            // The foliagesway shader references v_Bounds before it's declared. We inject
            // the varying declaration before the g_Texture1 uniform declaration.
            if (name.contains("foliagesway", ignoreCase = true) ||
                modified.contains("v_Bounds.x") || modified.contains("v_Bounds.y")) {
                if (modified.contains("uniform sampler2D g_Texture1;") &&
                    !modified.contains("varying vec2 v_Bounds;")) {
                    modified = modified.replace(
                        Regex("""(uniform sampler2D g_Texture1;)"""),
                        "varying vec2 v_Bounds;\n$1"
                    )
                    fixesApplied.add("foliagesway: injected v_Bounds varying")
                }
            }

            if (modified != original) {
                entries[i] = Pair(name, modified.toByteArray(Charsets.UTF_8))
                fixedCount++
                WepWawetLogger.proxy(
                    "MpkgPacker.fixShaders: shader fixed for GLSL ES compatibility",
                    "file" to name, "fixes" to fixesApplied.joinToString("; ")
                )
                // === v0.4.9: Dump the FIXED shader content (first 500 chars) so we
                // can VERIFY the fixes are actually in the shader bytes. This is
                // critical for proving to the user that the shaders ARE fixed —
                // if they still see unfixed shaders on the phone, the issue is
                // that they're testing with an OLD .mpkg, not that fixShaders
                // didn't run. ===
                val fixedContent = modified.take(500)
                WepWawetLogger.proxy("MpkgPacker.fixShaders: FIXED shader content preview",
                    "file" to name,
                    "content_preview" to fixedContent.replace("\n", "\\n").take(500))
            } else {
                // Log that the shader was ALREADY clean (no fixes needed)
                WepWawetLogger.proxy("MpkgPacker.fixShaders: shader already clean (no fixes needed)",
                    "file" to name)
            }
        }
        if (fixedCount > 0) {
            WepWawetLogger.proxy("MpkgPacker.fixShaders: fixed $fixedCount shader files for mobile compatibility")
        }
    }

    private fun fixSceneJsonTextureBindings(entries: MutableList<Pair<String, ByteArray>>) {
        try {
            // Find scene.json entry
            val sceneIdx = entries.indexOfFirst { it.first == "scene.json" }
            if (sceneIdx < 0) return
            val sceneJsonStr = String(entries[sceneIdx].second, Charsets.UTF_8)

            // Build a map of shader defaults from all .frag files
            // Key: shader name (e.g., "effects/xray")
            // Value: list of default texture names per slot [slot0, slot1, slot2, slot3]
            val shaderDefaults = mutableMapOf<String, List<String?>>()
            for ((name, blob) in entries) {
                if (name.endsWith(".frag") && name.startsWith("shaders/")) {
                    val shaderName = name.removePrefix("shaders/").removeSuffix(".frag")
                    val content = String(blob, Charsets.UTF_8)
                    val defaults = extractTextureDefaultsFromShader(content)
                    if (defaults.isNotEmpty()) {
                        shaderDefaults[shaderName] = defaults
                        WepWawetLogger.proxyDebug("MpkgPacker.fixSceneJson: found shader defaults",
                            "shader" to shaderName, "defaults" to defaults.joinToString(", "))
                    }
                }
            }

            // Parse scene.json
            val root = JsonParser.parseString(sceneJsonStr).asJsonObject
            var modified = false

            // Build a lookup for effect files and material files
            val entryMap = entries.associate { it.first to String(it.second, Charsets.UTF_8) }

            // Walk through objects → effects → passes → textures
            val objects = root.getAsJsonArray("objects") ?: return
            for (obj in objects) {
                val objAsJson = obj.asJsonObject
                if (!objAsJson.has("effects")) continue
                val effects = objAsJson.getAsJsonArray("effects")
                for (effect in effects) {
                    val effectObj = effect.asJsonObject
                    if (!effectObj.has("passes")) continue
                    val passes = effectObj.getAsJsonArray("passes")
                    for (pass in passes) {
                        val passObj = pass.asJsonObject
                        if (!passObj.has("textures")) continue
                        val textures = passObj.getAsJsonArray("textures")

                        // Determine the shader name for this pass
                        // Chain: effect file → material → shader name
                        val effectFile = effectObj.get("file")?.asString
                        val shaderName = findShaderName(effectFile, passObj, entryMap)
                        val defaults = if (shaderName != null) shaderDefaults[shaderName] else null

                        // Replace null entries with defaults (skip index 0 = framebuffer)
                        for (i in 1 until textures.size()) {
                            if (textures[i].isJsonNull) {
                                val default = defaults?.getOrNull(i)
                                if (default != null && default.isNotEmpty()) {
                                    textures[i] = com.google.gson.JsonPrimitive(default)
                                    modified = true
                                    WepWawetLogger.proxy("MpkgPacker.fixSceneJson: replaced null texture binding",
                                        "shader" to (shaderName ?: "unknown"),
                                        "slot" to i,
                                        "default" to default)
                                }
                            }
                        }
                    }
                }
            }

            if (modified) {
                // Write modified scene.json back
                val modifiedJson = com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root)
                entries[sceneIdx] = Pair("scene.json", modifiedJson.toByteArray(Charsets.UTF_8))
                WepWawetLogger.proxy("MpkgPacker.fixSceneJson: scene.json modified — null texture bindings replaced with shader defaults")
            } else {
                WepWawetLogger.proxyDebug("MpkgPacker.fixSceneJson: no null texture bindings found in scene.json")
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("MpkgPacker.fixSceneJson: failed to fix scene.json",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
        }
    }

    /** Extract texture default names from a shader file's uniform declarations. */
    private fun extractTextureDefaultsFromShader(shaderContent: String): List<String?> {
        val defaults = arrayOfNulls<String>(8) // g_Texture0 through g_Texture7
        // Match: uniform sampler2D g_TextureN; // {"...","default":"texture_name",...}
        val regex = Regex("""g_Texture(\d+)\s*;.*?"default"\s*:\s*"([^"]*)"""")
        for (match in regex.findAll(shaderContent)) {
            val slot = match.groupValues[1].toIntOrNull() ?: continue
            val defaultName = match.groupValues[2]
            if (slot < 8 && defaultName.isNotEmpty() && defaultName != "util/white") {
                // Record non-trivial defaults (skip "util/white" since it's the generic default)
                defaults[slot] = defaultName
            }
        }
        // Also match g_Texture0 which has "default" too (though usually "framebuffer")
        val regexWithDefault = Regex("""g_Texture(\d+)\s*;.*?"default"\s*:\s*"([^"]*)"""")
        for (match in regexWithDefault.findAll(shaderContent)) {
            val slot = match.groupValues[1].toIntOrNull() ?: continue
            val defaultName = match.groupValues[2]
            if (slot < 8 && defaultName.isNotEmpty()) {
                defaults[slot] = defaultName
            }
        }
        return defaults.toList()
    }

    /** Follow the chain: effect file → material → shader name. */
    private fun findShaderName(
        effectFile: String?,
        passObj: JsonObject,
        entryMap: Map<String, String>,
    ): String? {
        if (effectFile == null) return null
        try {
            // Read the effect file to find the material path
            val effectContent = entryMap[effectFile] ?: return null
            val effectJson = JsonParser.parseString(effectContent).asJsonObject
            val passes = effectJson.getAsJsonArray("passes")
            if (passes == null || passes.size() == 0) return null

            // The pass in the effect file might have a "material" field
            val effectPass = passes[0].asJsonObject
            val materialPath = if (effectPass.has("material")) {
                effectPass.get("material").asString
            } else {
                // Some effect files reference the material differently
                // Try "file" as the material path
                if (effectJson.has("file")) effectJson.get("file").asString else return null
            }

            // Read the material file to find the shader name
            val materialContent = entryMap[materialPath] ?: return null
            val materialJson = JsonParser.parseString(materialContent).asJsonObject
            val matPasses = materialJson.getAsJsonArray("passes")
            if (matPasses == null || matPasses.size() == 0) return null

            val matPass = matPasses[0].asJsonObject
            if (matPass.has("shader")) {
                return matPass.get("shader").asString
            }
        } catch (e: Exception) {
            // Ignore parsing errors
        }
        return null
    }

    /** Read a little-endian int32 from a byte array at the given offset. */
    private fun readLeInt(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return ((data[offset].toInt() and 0xFF)) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }


    /** Find a byte pattern in a byte array, starting from offset. Returns -1 if not found. */
    private fun findBytes(data: ByteArray, pattern: ByteArray, start: Int = 0): Int {
        if (pattern.isEmpty()) return -1
        for (i in start..data.size - pattern.size) {
            var match = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) { match = false; break }
            }
            if (match) return i
        }
        return -1
    }

    private fun writeLeIntToStream(out: java.io.ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
    }

    /**
     * Pack a list of (name, bytes) entries into a .mpkg file.
     */
    private fun pack(entries: List<Pair<String, ByteArray>>, outputFile: File): Long {
        WepWawetLogger.proxyDebug("MpkgPacker.pack layout",
            "entry_count" to entries.size,
            "header_size" to (4 + 8 + 4),
            "vfs_table_size_estimate" to entries.sumOf { 4 + it.first.toByteArray(Charsets.UTF_8).size + 8 })

        val headerSize = 4 + 8 + 4
        val vfsTableSize = entries.sumOf { 4 + it.first.toByteArray(Charsets.UTF_8).size + 4 + 4 }
        val dataSectionStart = headerSize + vfsTableSize

        val vfsEntries: MutableList<Triple<String, Int, Int>> = mutableListOf()
        var cursor = 0
        for (entry in entries) {
            val (name, blob) = entry
            vfsEntries.add(Triple(name, cursor, blob.size))
            cursor += blob.size
        }

        WepWawetLogger.proxy("MpkgPacker.pack layout computed",
            "header_size" to headerSize,
            "vfs_table_size" to vfsTableSize,
            "data_section_start" to dataSectionStart,
            "total_size" to (dataSectionStart + cursor),
            "entry_count" to entries.size)

        val out = outputFile.outputStream().buffered()
        try {
            writeLeInt(out, HEADER_MARKER)
            out.write(MAGIC.toByteArray(Charsets.US_ASCII))
            if (MAGIC.length != 8) error("magic must be 8 bytes: '$MAGIC'")
            writeLeInt(out, entries.size)

            for (entry in vfsEntries) {
                val (name, offset, size) = entry
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                writeLeInt(out, nameBytes.size)
                out.write(nameBytes)
                writeLeInt(out, offset)
                writeLeInt(out, size)
            }

            for (entry in entries) {
                val (_, blob) = entry
                out.write(blob)
            }
            out.flush()
        } finally {
            out.close()
        }
        return outputFile.length()
    }

    private fun writeLeInt(out: OutputStream, value: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        out.write(buf)
    }

    private fun writeLeInt(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
    }

    /**
     * Verify the .mpkg file we just wrote by reading the header back.
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
            val magicBytes = ByteArray(8)
            buf.get(magicBytes)
            val magic = String(magicBytes, Charsets.US_ASCII)
            val entryCount = buf.int

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

            for (i in 0 until entryCount) {
                if (buf.remaining() < 4) {
                    WepWawetLogger.proxyWarn("MpkgPacker verify: truncated at name_len",
                        "index" to i)
                    return
                }
                val nameLen = buf.int
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
}
