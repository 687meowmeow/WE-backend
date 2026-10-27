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

    /**
     * Bump whenever the DXT decoder, ETC2 encoder, effort policy, or .tex
     * output format changes. This invalidates the texture conversion cache
     * so stale entries are not reused after a code change.
     *
     * v2: fix-dxt-decoder.patch (3-bit DXT5/BC4/BC5 indices + forceFourColor
     *     for DXT3/DXT5 color blocks) + size-based effort (10/25/40) + more
     *     jobs (oversubscribed) + NDEBUG.
     */
    const val CONVERTER_VERSION = 2

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
            // Large .pkg — load ONLY entries that need modification (shaders, JSONs).
            // v0.6.1: CRITICAL OOM FIX — .tex entries are NOT loaded here. They are loaded
            // and converted ONE AT A TIME in the .tex processing phase below. This prevents
            // OOM when a .pkg has many large .tex files (e.g., 310 entries × 2MB avg = 620MB,
            // exceeding the 512MB heap). After conversion, each .tex is ~4-8x smaller
            // (ETC2+LZ4), so the converted results fit comfortably in memory.
            val (vfsEntries, dataSectionStart) = vfsData!!
            WepWawetLogger.proxy("MpkgPacker.packFromPkg: streaming mode (large .pkg)",
                "pkg_size" to pkgFile.length(),
                "vfs_entries" to vfsEntries.size,
                "loading_only_modified" to true,
                "tex_entries_deferred" to vfsEntries.count { it.path.endsWith(".tex") })
            val loaded = mutableListOf<Pair<String, ByteArray>>()
            for (vfs in vfsEntries) {
                // Load non-.tex entries that need modification (shaders, JSONs).
                // .tex entries are deferred to the one-at-a-time processing phase.
                val needsMod = vfs.path.endsWith(".frag") || vfs.path.endsWith(".vert") ||
                    vfs.path == "scene.json" || vfs.path == "project.json"
                if (needsMod) {
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

        // Step 3c: Convert .tex files.
        //
        // v0.6.1: CRITICAL OOM FIX — In streaming mode, .tex entries are loaded from
        // the .pkg ONE AT A TIME, converted, and the converted (smaller) result is kept.
        // The original .tex bytes are freed after each conversion. This prevents OOM
        // when a .pkg has many large .tex files (e.g., 100+ textures × 2MB avg = 200MB+
        // just for original .tex data, plus RGBA decode buffers + ETC2 encode buffers
        // = well over 512MB heap).
        //
        // After conversion, each .tex is ~4-8x smaller (ETC2+LZ4 vs DXT/PNG), so the
        // converted results fit comfortably in memory for the subsequent streamPack phase.
        val processedEntries = mutableListOf<Pair<String, ByteArray>>()
        var texConverted = 0
        var texDxtConverted = 0
        var texPngConverted = 0
        var texRgbaConverted = 0  // v0.6.0: RGBA8888 → ETC2 conversions
        var totalOriginalTexBytes = 0L
        var totalConvertedTexBytes = 0L

        // Pass 1: Process non-.tex entries from `entries` (already loaded, small)
        for (entry in entries) {
            val (name, blob) = entry
            if (name.endsWith(".tex")) continue  // .tex handled in Pass 2
            processedEntries.add(entry)
        }

        // Pass 2: Process .tex entries ONE AT A TIME using a lazy sequence.
        // v0.6.1: CRITICAL OOM FIX — .tex entries are NEVER all loaded into memory at once.
        // A Kotlin Sequence is used to lazily load each .tex from .pkg (streaming mode) or
        // from the entries list (non-streaming mode). Each .tex is loaded, converted, and
        // the result added to processedEntries. The original bytes go out of scope after
        // each iteration, allowing GC to reclaim between iterations.
        val texSource: Sequence<Pair<String, ByteArray>> = if (vfsData != null) {
            // Streaming mode: lazily load each .tex from .pkg one at a time
            val (vfsEntries, dataSectionStart) = vfsData!!
            val texVfsEntries = vfsEntries.filter { it.path.endsWith(".tex") }
            WepWawetLogger.proxy("MpkgPacker.packFromPkg: streaming .tex entries (lazy, one at a time)",
                "tex_count" to texVfsEntries.size)
            texVfsEntries.asSequence().map { vfs ->
                val data = PkgReader.readEntryData(pkgFile, dataSectionStart, vfs.offset, vfs.size)
                if (data != null) vfs.path to data else null
            }.filterNotNull()
        } else {
            // Non-streaming mode: .tex entries are already in `entries`
            val texEntries = entries.filter { it.first.endsWith(".tex") }
            WepWawetLogger.proxy("MpkgPacker.packFromPkg: processing .tex entries from loaded list",
                "tex_count" to texEntries.size)
            texEntries.asSequence()
        }

        for (texEntry in texSource) {
            val (name, blob) = texEntry

            // Parse the .tex header FIRST. We route to the appropriate converter
            // based on the TexI format field — this avoids the false-positive
            // FFD8FF detection that triggered convertTexPngToEtc2 on DXT .tex files.
            //
            // TexI format values (per repkg's TexFormat enum — the authoritative source
            // https://github.com/notscuffed/repkg):
            //   0  = FIF_UNKNOWN (auto-detect via magic bytes — may contain PNG or JPEG)
            //   4  = DXT5 (BC3)  → convertTexDxtToEtc2
            //   5  = ETC2_RGBA8  → already mobile-compatible, SKIP conversion
            //   6  = DXT3 (BC2)  → convertTexDxtToEtc2
            //   7  = DXT1 (BC1)  → convertTexDxtToEtc2
            //   8  = RG88        → convertTexRg88ToEtc2 (NEW v0.6.0 §6-2)
            //   9  = R8          → convertTexR8ToEtc2 (NEW v0.6.0 §6-2)
            //   11 = BC4 (RGTC1) → convertTexDxtToEtc2
            //   12 = BC5 (RGTC2) → convertTexDxtToEtc2
            //   13 = BC6H (HDR)  → convertTexDxtToEtc2 (NEW v0.6.0 §6-1/6-9)
            //   14 = BC7         → convertTexDxtToEtc2 (NEW v0.6.0 §6-1/6-10)
            //   20 = RGBA8888    → convertTexRgba8888ToEtc2 (NEW v0.6.0 §6-3, was pass-through)
            //
            // v0.6.0 (parity §6 6-1): CRITICAL FIX — format codes 13 and 14 are BC6H and BC7
            // respectively, NOT FIF_PNG/FIF_JPEG as previously documented. PNG/JPEG-encoded
            // .tex files have TexI format=0 (FIF_UNKNOWN) and are detected via magic bytes.
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
                processedEntries.add(texEntry)
                continue
            }

            // ---- Branch 2: format=20 (RGBA8888) — convert to ETC2+LZ4 for size ----
            // v0.6.0 (parity §6 6-3): Previously this was a pass-through, producing .mpkg
            // files 4× larger than necessary. Real WE PC converts RGBA8888 to ETC2+LZ4.
            if (texiFormat == 20) {
                WepWawetLogger.proxy("MpkgPacker.packFromPkg: .tex is RGBA8888 (format=20) — converting to ETC2+LZ4 for size",
                    "name" to name, "tex_size" to blob.size)
                val converted = convertTexRgba8888ToEtc2(blob, name)
                if (converted != null) {
                    processedEntries.add(Pair(name, converted))
                    texConverted++
                    texRgbaConverted++
                    totalOriginalTexBytes += blob.size
                    totalConvertedTexBytes += converted.size
                    WepWawetLogger.proxy("MpkgPacker.packFromPkg: RGBA8888 .tex transcoded to ETC2+LZ4",
                        "name" to name,
                        "original_size" to blob.size,
                        "converted_size" to converted.size,
                        "size_ratio" to "%.2fx".format(converted.size.toDouble() / blob.size.toDouble()))
                } else {
                    processedEntries.add(texEntry)
                    WepWawetLogger.proxyWarn("MpkgPacker.packFromPkg: RGBA8888 .tex conversion FAILED — using original (4x larger than necessary)",
                        "name" to name)
                }
                continue
            }

            // ---- Branch 3: DXT/BC family — transcode via DxtDecoder ----
            // v0.6.0: Now includes BC6H (13) and BC7 (14)
            val dxtFormat = DxtDecoder.formatForTexFormat(texiFormat)
            if (dxtFormat != null) {
                WepWawetLogger.proxy("MpkgPacker.packFromPkg: .tex is ${dxtFormat.displayName} (format=$texiFormat) — transcoding → ETC2_RGBA8+LZ4",
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
                    WepWawetLogger.proxy("MpkgPacker.packFromPkg: DXT/BC .tex transcoded",
                        "name" to name,
                        "dxt_format" to dxtFormat.displayName,
                        "original_size" to blob.size,
                        "converted_size" to converted.size,
                        "size_ratio" to "%.2fx".format(converted.size.toDouble() / blob.size.toDouble()))
                } else {
                    processedEntries.add(texEntry)
                    WepWawetLogger.proxyWarn("MpkgPacker.packFromPkg: DXT/BC .tex transcode FAILED — using original (will show yellow/black on Android)",
                        "name" to name,
                        "dxt_format" to dxtFormat.displayName)
                }
                continue
            }

            // ---- Branch 4: format=8 (RG88) — 2-channel texture ----
            // v0.6.0 (parity §6 6-2): Used for normal/roughness maps in modern scenes.
            if (texiFormat == 8) {
                WepWawetLogger.proxy("MpkgPacker.packFromPkg: .tex is RG88 (format=8) — transcoding → ETC2_RGBA8+LZ4",
                    "name" to name, "tex_size" to blob.size)
                val converted = convertTexRg88ToEtc2(blob, name)
                if (converted != null) {
                    processedEntries.add(Pair(name, converted))
                    texConverted++
                    totalOriginalTexBytes += blob.size
                    totalConvertedTexBytes += converted.size
                    WepWawetLogger.proxy("MpkgPacker.packFromPkg: RG88 .tex transcoded",
                        "name" to name,
                        "original_size" to blob.size,
                        "converted_size" to converted.size)
                } else {
                    processedEntries.add(texEntry)
                    WepWawetLogger.proxyWarn("MpkgPacker.packFromPkg: RG88 .tex transcode FAILED — using original",
                        "name" to name)
                }
                continue
            }

            // ---- Branch 4b: format=9 (R8) — 1-channel texture ----
            // v0.6.0 (parity §6 6-2): Used for grayscale / single-channel masks.
            if (texiFormat == 9) {
                WepWawetLogger.proxy("MpkgPacker.packFromPkg: .tex is R8 (format=9) — transcoding → ETC2_RGBA8+LZ4",
                    "name" to name, "tex_size" to blob.size)
                val converted = convertTexR8ToEtc2(blob, name)
                if (converted != null) {
                    processedEntries.add(Pair(name, converted))
                    texConverted++
                    totalOriginalTexBytes += blob.size
                    totalConvertedTexBytes += converted.size
                    WepWawetLogger.proxy("MpkgPacker.packFromPkg: R8 .tex transcoded",
                        "name" to name,
                        "original_size" to blob.size,
                        "converted_size" to converted.size)
                } else {
                    processedEntries.add(texEntry)
                    WepWawetLogger.proxyWarn("MpkgPacker.packFromPkg: R8 .tex transcode FAILED — using original",
                        "name" to name)
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
                    processedEntries.add(texEntry)
                    WepWawetLogger.proxyWarn("MpkgPacker.packFromPkg: .tex PNG conversion FAILED — using original (will likely show yellow/black on Android)",
                        "name" to name)
                }
            } else {
                processedEntries.add(texEntry)
            }
        }
        // v0.6.1: Always replace entries with processedEntries.
        // processedEntries contains non-.tex entries (Pass 1) + converted .tex entries (Pass 2).
        // In streaming mode, `entries` doesn't have .tex entries (they were loaded one-at-a-time
        // in Pass 2), so we MUST replace entries with processedEntries to have the complete list.
        entries.clear()
        entries.addAll(processedEntries)
        if (texConverted > 0) {
            WepWawetLogger.proxy("MpkgPacker.packFromPkg: converted .tex files summary",
                "tex_converted_count" to texConverted,
                "tex_dxt_converted" to texDxtConverted,
                "tex_png_converted" to texPngConverted,
                "total_original_tex_bytes" to totalOriginalTexBytes,
                "total_converted_tex_bytes" to totalConvertedTexBytes,
                "size_increase_bytes" to (totalConvertedTexBytes - totalOriginalTexBytes),
                "size_increase_mb" to "%.1f".format((totalConvertedTexBytes - totalOriginalTexBytes) / 1024.0 / 1024.0))
        }

        // Step 3d: Transcode .webm videos to .mp4 with H.264 (100% parity with WE PC)
        //
        // Real WE PC's transcoder writes `mp4_h264` as the codec identifier for
        // video wallpapers (disasm line 14003f017). For .webm source files,
        // WE PC uses `ffmpeg_mp4_h264` to transcode to .mp4 with H.264 codec.
        // We do the same on-device using FFmpegKit.
        //
        // The Android app's VideoDrawable expects MP4 with H.264 codec. WebM
        // files (VP8/VP9 + Vorbis/Opus) may not be supported by all Android
        // VideoDrawable implementations.
        if (type == "video" || type == "unknown") {
            // Find any .webm entries and transcode them to .mp4
            val webmEntries = entries.filter { it.first.endsWith(".webm", ignoreCase = true) }
            if (webmEntries.isNotEmpty()) {
                WepWawetLogger.proxy("MpkgPacker.packFromPkg: found .webm video(s) — will transcode to .mp4",
                    "count" to webmEntries.size,
                    "files" to webmEntries.map { it.first })
                for ((webmName, webmBytes) in webmEntries) {
                    val mp4Name = webmName.substringBeforeLast('.') + ".mp4"
                    WepWawetLogger.proxy("MpkgPacker.packFromPkg: transcoding .webm → .mp4",
                        "webm_name" to webmName,
                        "mp4_name" to mp4Name,
                        "webm_size" to webmBytes.size)

                    // Write .webm to temp file, transcode, read .mp4 back
                    val tempWebm = File.createTempFile("wepwawet_transcode_", ".webm")
                    val tempMp4 = File.createTempFile("wepwawet_transcode_", ".mp4")
                    try {
                        tempWebm.writeBytes(webmBytes)
                        if (VideoTranscoder.transcodeToMp4H264(tempWebm, tempMp4)) {
                            val mp4Bytes = tempMp4.readBytes()
                            // Replace the .webm entry with the .mp4 entry
                            val webmIndex = entries.indexOfFirst { it.first == webmName }
                            if (webmIndex >= 0) {
                                entries[webmIndex] = mp4Name to mp4Bytes
                                WepWawetLogger.proxy("MpkgPacker.packFromPkg: .webm transcoded to .mp4",
                                    "webm_name" to webmName,
                                    "mp4_name" to mp4Name,
                                    "webm_size" to webmBytes.size,
                                    "mp4_size" to mp4Bytes.size,
                                    "size_ratio" to "%.2fx".format(mp4Bytes.size.toDouble() / webmBytes.size))
                            }
                            // Also update project.json's "file" field if it references the .webm
                            val pjIndex = entries.indexOfFirst { it.first == "project.json" }
                            if (pjIndex >= 0) {
                                val pjStr = String(entries[pjIndex].second, Charsets.UTF_8)
                                val updatedPj = pjStr.replace(webmName, mp4Name)
                                if (updatedPj != pjStr) {
                                    entries[pjIndex] = "project.json" to updatedPj.toByteArray(Charsets.UTF_8)
                                    WepWawetLogger.proxy("MpkgPacker.packFromPkg: updated project.json file field",
                                        "old" to webmName, "new" to mp4Name)
                                }
                            }
                        } else {
                            WepWawetLogger.proxyWarn("MpkgPacker.packFromPkg: .webm transcode FAILED — keeping .webm as-is",
                                "webm_name" to webmName)
                        }
                    } finally {
                        tempWebm.delete()
                        tempMp4.delete()
                    }
                }
            }
        }

        // Step 3e: Rewrite project.json + scene.json for the .mpkg's MPKG_VFS://
        // addressing scheme (100% parity with real WE PC's transcoder).
        //
        // Real WE PC's transcoder (function 0x14003d1e0 in wallpaperui.exe,
        // see research/we-transcoder/02_transcoder_14003d1e0.asm) rewrites
        // project.json and scene.json during the .pkg -> .mpkg transcoding step:
        //   - project.json: "file" → "MPKG_VFS://...", "preview" → "MPKG_VFS://...",
        //     removes "video" section, sets "type", "title", "workshopurl",
        //     "maximumprojectid" (max object ID from scene.json's "objects" array)
        //     (disasm lines 14003ea47, 14003eacc, 14003eb31, 14003eb70, 14003eb80,
        //      1400416b8, 14003fceb)
        //   - scene.json: iterates over "files" array and prefixes each entry's
        //     "value" with "MPKG_VFS://"
        //     (disasm lines 14004104e, 1400410cd, 140041125-14004116a)
        //
        // The MPKG_VFS:// scheme is what the Android native lib (libscenejni.so)
        // uses to address files within the .mpkg's virtual file system.
        // Without this prefix, the Android app may not find files inside the .mpkg.
        //
        // First: compute maxObjectId from scene.json (needed for project.json's
        // "maximumprojectid" field). We need to do this BEFORE rewriting project.json,
        // and we should use the rewritten scene.json (with MPKG_VFS:// paths) —
        // but the "id" field values are integers, not paths, so the rewrite doesn't
        // affect them. We can use either the original or rewritten scene.json.
        var maxObjectId = -1
        for ((name, blob) in entries) {
            if (name == "scene.json") {
                maxObjectId = computeMaxObjectId(blob)
                if (maxObjectId >= 0) {
                    WepWawetLogger.proxy("MpkgPacker.packFromPkg: computed maxObjectId from scene.json",
                        "max_object_id" to maxObjectId)
                }
                break
            }
        }
        for (i in entries.indices) {
            val (name, blob) = entries[i]
            if (name == "project.json") {
                val rewritten = rewriteProjectJsonForMobile(blob, maxObjectId)
                if (rewritten != null && !rewritten.contentEquals(blob)) {
                    entries[i] = name to rewritten
                    WepWawetLogger.proxy("MpkgPacker.packFromPkg: rewrote project.json for MPKG_VFS scheme",
                        "original_size" to blob.size,
                        "rewritten_size" to rewritten.size,
                        "max_object_id" to maxObjectId)
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
        // CRITICAL: Standalone files (project.json, preview.gif) that were added from
        // the install directory but are NOT in the .pkg's VFS table MUST be included
        // in the .mpkg. Without project.json, the Android app can't load the wallpaper
        // → BLACK SCREEN.
        data class FinalEntry(val name: String, val size: Int, val data: ByteArray?, val vfsOffset: Long)
        val finalEntries = mutableListOf<FinalEntry>()
        val modifiedNames = modifiedEntries.keys
        val vfsPaths = vfsEntries.map { it.path }.toHashSet()

        for (vfs in vfsEntries) {
            if (vfs.path in modifiedNames) {
                val data = modifiedEntries[vfs.path]!!
                finalEntries.add(FinalEntry(vfs.path, data.size, data, 0))
            } else {
                // Unmodified — stream from .pkg
                finalEntries.add(FinalEntry(vfs.path, vfs.size.toInt(), null, vfs.offset))
            }
        }

        // Add standalone files that are NOT in the .pkg's VFS table.
        // These are files like project.json and preview.gif that exist in the
        // install directory but not inside the .pkg. Without these, the .mpkg
        // is incomplete and the Android app can't load the wallpaper.
        for ((name, data) in modifiedEntries) {
            if (name !in vfsPaths) {
                finalEntries.add(FinalEntry(name, data.size, data, 0))
                WepWawetLogger.proxy("MpkgPacker.streamPack: added standalone entry (not in .pkg VFS)",
                    "name" to name,
                    "size" to data.size)
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
            append("\t\"file\" : \"scene.json\",\r\n")
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
            append("\t\"preview\" : \"preview.jpg\",\r\n")
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
            append("\t\"file\" : \"wallpaper.mp4\",\r\n")
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
            append("\t\"preview\" : \"preview.jpg\",\r\n")
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
            // v0.6.0 (parity §6 6-8): CRITICAL FIX — Use mipmap level 0 (largest) instead of
            // level 2 (third-largest). The previous behavior produced blurry textures on Android
            // when the ETC2 native lib was unavailable. Real WE PC always uses mipmap 0.
            // For size concerns, the ETC2+LZ4 path produces files ~5× smaller than RGBA8888,
            // so using mipmap 0 is not a size issue when ETC2 is available.
            // When ETC2 is unavailable (this fallback path), the file is larger but visually correct.
            // Mipmap levels (typical): 0=largest(3000x1800, 21.6MB), 1=1500x900(5.4MB),
            //                          2=750x450(1.35MB), 3=375x225(0.34MB)
            val sortedBySize = decoded.sortedByDescending { it.width.toLong() * it.height.toLong() }
            val targetMipmapLevel = 0  // v0.6.0: 0=largest (was 2 = third-largest)
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
            // ---- Cache lookup ----
            texCacheLookup(texData, name)?.let { cached ->
                WepWawetLogger.proxy("MpkgPacker.convertTexPngToEtc2: using cached conversion",
                    "name" to name, "cached_size" to cached.size)
                return cached
            }

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
                // v0.6.0 (parity §6-11): Multi-page image support (animated GIF / animated WebP).
                // Previously only the first frame was decoded via BitmapFactory. WE PC uses
                // FreeImage's LoadMultiBitmapFromMemory + LockPage + GetPageCount for these.
                // We now detect animated images via Movie API and decode all frames.
                val isAnimatedGif = imgEntry.format == "PNG" &&
                    imgEntry.bytes.size > 12 &&
                    // GIF89a header for animated GIFs (despite the .tex using PNG format internally,
                    // some Workshop wallpapers actually contain GIF data mislabeled as PNG)
                    imgEntry.bytes[0] == 0x47.toByte() && imgEntry.bytes[1] == 0x49.toByte() &&
                    imgEntry.bytes[2] == 0x46.toByte()  // "GIF"

                if (isAnimatedGif) {
                    // Use android.graphics.Movie to decode all frames of the animated GIF
                    try {
                        val movie = android.graphics.Movie.decodeByteArray(imgEntry.bytes, 0, imgEntry.bytes.size)
                        if (movie != null && movie.duration() > 0) {
                            // Movie.duration() returns total duration in ms; assume 100ms per frame
                            val frameCount = (movie.duration() / 100).coerceAtLeast(1)
                            WepWawetLogger.proxy("MpkgPacker.convertTexPngToEtc2: animated GIF detected",
                                "name" to name, "duration_ms" to movie.duration(),
                                "estimated_frames" to frameCount,
                                "width" to movie.width(), "height" to movie.height())
                            // Decode each frame as a Bitmap and add to decoded list
                            val canvasBitmap = android.graphics.Bitmap.createBitmap(
                                movie.width(), movie.height(), android.graphics.Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(canvasBitmap)
                            for (frameIdx in 0 until frameCount) {
                                canvasBitmap.eraseColor(android.graphics.Color.TRANSPARENT)
                                val movieTime = (frameIdx * 100L).toInt()
                                movie.setTime(movieTime)
                                movie.draw(canvas, 0f, 0f)
                                // Extract pixels from canvas
                                val pixelBuffer = java.nio.ByteBuffer.allocateDirect(
                                    movie.width() * movie.height() * 4)
                                    .order(java.nio.ByteOrder.nativeOrder())
                                canvasBitmap.copyPixelsToBuffer(pixelBuffer)
                                val rgbaTopDown = ByteArray(movie.width() * movie.height() * 4)
                                pixelBuffer.rewind()
                                pixelBuffer.get(rgbaTopDown)
                                decoded.add(DecodedRgba(idx, movie.width(), movie.height(), rgbaTopDown))
                            }
                            canvasBitmap.recycle()
                            continue  // Skip the single-frame decode below
                        }
                    } catch (e: Exception) {
                        WepWawetLogger.proxyWarn("MpkgPacker.convertTexPngToEtc2: animated GIF decode failed — falling back to single frame",
                            "name" to name, "exception" to e.javaClass.name)
                    }
                }

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
            // v0.6.0 (parity §6 6-7): Detect opacity for RGBX error metric
            var pngIsOpaque = true
            val pngSampleStep = 4 * 1024
            var pngI = 3
            while (pngI < largest.rgba.size) {
                if ((largest.rgba[pngI].toInt() and 0xFF) != 255) {
                    pngIsOpaque = false
                    break
                }
                pngI += pngSampleStep
            }

            val etc2EncodeStartMs = System.currentTimeMillis()
            val etc2Bytes = try {
                Etc2Encoder.encodeRgba8888(largest.rgba, largest.width, largest.height, pngIsOpaque)
            } catch (e: Exception) {
                WepWawetLogger.proxyError("MpkgPacker.convertTexPngToEtc2: native ETC2 encoding failed, falling back to RGBA8888",
                    "name" to name, "exception" to e.javaClass.name, "message" to (e.message ?: ""))
                return convertTexPngToRgba(texData, name)
            }
            val etc2ElapsedMs = System.currentTimeMillis() - etc2EncodeStartMs
            logEtc2Timing(name, largest.width, largest.height, etc2ElapsedMs)

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

            // ---- Store result in texture conversion cache ----
            texCacheStore(texData, result, name)

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

    // ========================================================================
    // Texture conversion cache
    // ========================================================================
    // Caches converted .tex files (DXT→ETC2, PNG→ETC2) by SHA-256 hash of
    // the source .tex bytes + CONVERTER_VERSION. On a cache hit, the
    // converted .tex is read from disk instead of re-encoding. The cache
    // is capped at ~2 GB, evicting least-recently-used files.
    //
    // This is especially useful when the user re-downloads the same wallpaper
    // or when multiple .pkg files share common textures.

    private const val TEX_CACHE_MAX_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB
    private const val TEX_CACHE_DIR_NAME = "tex_cache"

    /**
     * Look up the texture conversion cache for a previously-converted .tex.
     *
     * @param texData The source .tex bytes (before conversion)
     * @param name The .tex file name (for logging only)
     * @return The cached converted .tex bytes, or null on cache miss
     */
    private fun texCacheLookup(texData: ByteArray, name: String): ByteArray? {
        return try {
            val cacheDir = File(com.wepwawet.WepWawetApplication.instance.cacheDir, TEX_CACHE_DIR_NAME)
            val key = texCacheKey(texData)
            val cacheFile = File(cacheDir, "$key.tex")
            if (cacheFile.exists() && cacheFile.length() > 0) {
                val data = cacheFile.readBytes()
                WepWawetLogger.proxy("MpkgPacker.texCacheLookup: CACHE HIT",
                    "name" to name,
                    "cache_key" to key.take(16),
                    "cache_size" to data.size)
                // Update last-modified for LRU
                cacheFile.setLastModified(System.currentTimeMillis())
                data
            } else {
                null
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyDebug("MpkgPacker.texCacheLookup: error (cache miss)",
                "name" to name, "exception" to e.javaClass.name)
            null
        }
    }

    /**
     * Store a converted .tex in the cache.
     */
    private fun texCacheStore(texData: ByteArray, convertedData: ByteArray, name: String) {
        try {
            val cacheDir = File(com.wepwawet.WepWawetApplication.instance.cacheDir, TEX_CACHE_DIR_NAME)
            cacheDir.mkdirs()
            val key = texCacheKey(texData)
            val cacheFile = File(cacheDir, "$key.tex")
            cacheFile.writeBytes(convertedData)
            WepWawetLogger.proxyDebug("MpkgPacker.texCacheStore: stored",
                "name" to name,
                "cache_key" to key.take(16),
                "cache_size" to convertedData.size)
            // Evict LRU entries if cache exceeds max size
            texCacheEvictIfNeeded(cacheDir)
        } catch (e: Exception) {
            WepWawetLogger.proxyDebug("MpkgPacker.texCacheStore: error (ignored)",
                "name" to name, "exception" to e.javaClass.name)
        }
    }

    /**
     * Compute the cache key: SHA-256 of source .tex bytes + CONVERTER_VERSION.
     */
    private fun texCacheKey(texData: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(texData)
        // Append converter version to invalidate cache on code changes
        md.update(byteArrayOf(CONVERTER_VERSION.toByte()))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Evict least-recently-used cache files if total size exceeds TEX_CACHE_MAX_BYTES.
     */
    private fun texCacheEvictIfNeeded(cacheDir: File) {
        try {
            val files = cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".tex") } ?: return
            var totalSize = files.sumOf { it.length() }
            if (totalSize <= TEX_CACHE_MAX_BYTES) return
            // Sort by last-modified ascending (oldest first)
            val sorted = files.sortedBy { it.lastModified() }
            for (f in sorted) {
                if (totalSize <= TEX_CACHE_MAX_BYTES) break
                totalSize -= f.length()
                f.delete()
            }
        } catch (e: Exception) {
            // Cache eviction errors are non-fatal
        }
    }

    // ========================================================================
    // Timing logs for ETC2 encoding
    // ========================================================================

    /**
     * Log timing for an ETC2 encode operation.
     */
    private fun logEtc2Timing(name: String, width: Int, height: Int, elapsedMs: Long) {
        val pixels = width.toLong() * height.toLong()
        val megapixels = pixels / 1_000_000.0
        val mpPerSec = if (elapsedMs > 0) (megapixels / (elapsedMs / 1000.0)) else 0.0
        val effort = when {
            pixels <= 1048576 -> 40
            pixels <= 4194304 -> 25
            else -> 10
        }
        val jobs = minOf(maxOf(Runtime.getRuntime().availableProcessors() * 2, 8), 32)
        WepWawetLogger.proxy("MpkgPacker: ETC2 encode timing",
            "name" to name,
            "width" to width,
            "height" to height,
            "megapixels" to "%.2f".format(megapixels),
            "effort" to effort,
            "jobs" to jobs,
            "elapsed_ms" to elapsedMs,
            "mp_per_sec" to "%.2f".format(mpPerSec))
    }

    private fun convertTexDxtToEtc2(texData: ByteArray, name: String): ByteArray? {
        try {
            // ---- Step 0a: Check texture conversion cache ----
            texCacheLookup(texData, name)?.let { cached ->
                WepWawetLogger.proxy("MpkgPacker.convertTexDxtToEtc2: using cached conversion",
                    "name" to name, "cached_size" to cached.size)
                return cached
            }

            // ---- Step 0b: Verify native libraries are available ----
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
            // v0.6.0 (parity §6 6-4 + 6-7): Detect DXT1 vs DXT1a (use1BitAlpha) for
            // the decoder call, and detect opacity for the ETC2 encoder error metric.
            //
            // For DXT1: use the .tex flags field's bit 3 (HasAlpha = 0x08) to decide
            //   - flags & 0x08 set → DXT1a (1-bit alpha mode)
            //   - flags & 0x08 unset → DXT1 (4-color opaque)
            //
            // For DXT3/DXT5/BC4/BC5/BC6H/BC7: use1BitAlpha doesn't matter (only affects DXT1).
            val hasAlphaFlag = (origFlags and 0x08) != 0
            val use1BitAlphaForDxt1 = if (dxtFormat == DxtDecoder.Format.DXT1) hasAlphaFlag else true

            // Re-decode with proper use1BitAlpha if DXT1
            val rgbaBytesFinal = if (dxtFormat == DxtDecoder.Format.DXT1 && !use1BitAlphaForDxt1) {
                WepWawetLogger.proxy("MpkgPacker.convertTexDxtToEtc2: re-decoding DXT1 as opaque (no HasAlpha flag)",
                    "name" to name, "flags" to origFlags)
                DxtDecoder.decodeToRgba8888(dxtBlockData, texWidth, texHeight, dxtFormat, use1BitAlpha = false)
                    ?: rgbaBytes
            } else {
                rgbaBytes
            }

            // Detect opacity for ETC2 encoder (RGBX metric for opaque)
            var isOpaque = true
            val sampleStep = 4 * 1024
            var i = 3
            while (i < rgbaBytesFinal.size) {
                if ((rgbaBytesFinal[i].toInt() and 0xFF) != 255) {
                    isOpaque = false
                    break
                }
                i += sampleStep
            }

            val etc2EncodeStartMs = System.currentTimeMillis()
            val etc2Bytes = try {
                Etc2Encoder.encodeRgba8888(rgbaBytesFinal, texWidth, texHeight, isOpaque)
            } catch (e: Exception) {
                WepWawetLogger.proxyError("MpkgPacker.convertTexDxtToEtc2: ETC2 encoding failed",
                    "name" to name, "exception" to e.javaClass.name, "message" to (e.message ?: ""))
                return null
            }
            val etc2ElapsedMs = System.currentTimeMillis() - etc2EncodeStartMs
            logEtc2Timing(name, texWidth, texHeight, etc2ElapsedMs)
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

            // ---- Step 7g: Preserve TEXS0003 section (animated texture sequences) ----
            //
            // Per the Ghidra decompilation of FUN_1400de870 (the .tex parser at
            // line 152127), the parser checks for TEXI0001, TEXB0003/0004, and
            // TEXS0003 sections in a loop. The TEXS section contains per-frame
            // UV coordinates for animated texture atlases (handled by FUN_1400de440).
            //
            // If the original .tex has a TEXS section after the TEXB body, we must
            // preserve it in the output .tex — the Android app's libscenejni.so
            // reads it to know which UV region of the texture atlas to display
            // at each animation frame.
            //
            // v0.6.0 (parity §6 6-5): CRITICAL FIX — Walk all mipmaps to find the actual
            // end of the TEXB body, then look for TEXS. The previous code used
            // `91 + byteCount` which is only mipmap 0's data — for multi-mipmap .tex
            // files, mipmaps 1, 2, 3, ... are at offsets 91+mipmap0_size onwards,
            // and the bytes there look like binary DXT/ETC2 block data (not "TEXS").
            // The sectionMagic == "TEXS" check would silently fail, dropping the
            // animated texture sequences.
            //
            // The .tex mipmap layout per the spec is:
            //   [TEXV0005\0][TEXI0001\0][28-byte TEXI header]
            //   [TEXB0004\0]
            //   [4-byte imageCount][4-byte format][4-byte isVideoMp4]
            //   For each image (typically just 1):
            //     [4-byte mipmap_count]
            //     For each mipmap:
            //       [4-byte width][4-byte height][4-byte compression]
            //       [4-byte uncompressed_size][4-byte byteCount]
            //       [byteCount bytes of data]
            //   [TEXS0003\0 ... animation frames] (optional)
            val texsMipmapCount = readU32Le(texData, 67).toInt().coerceAtLeast(1)
            // Walk all mipmaps to find the actual end of the TEXB body.
            // Mipmap layout (after the 4-byte mipmap_count at offset 67):
            //   offset 71: mipmap 0 header (20 bytes: width+height+compression+uncompressed_size+byteCount)
            //   offset 91: mipmap 0 body (byteCount bytes)
            //   then mipmap 1 header (20 bytes) + body (byteCount bytes), etc.
            var mipPos = 71  // start of mipmap 0's header
            for (m in 0 until texsMipmapCount) {
                // Mipmap header: width(4) + height(4) + compression(4) + uncompressed_size(4) + byteCount(4) = 20 bytes
                // Then byteCount bytes of data
                if (mipPos + 20 > texData.size) break
                val mByteCount = readU32Le(texData, mipPos + 16).toInt()
                mipPos += 20 + mByteCount
            }
            WepWawetLogger.proxy("MpkgPacker.convertTexDxtToEtc2: walked mipmaps for TEXS",
                "name" to name,
                "mipmap_count" to texsMipmapCount,
                "texb_body_end" to mipPos,
                "tex_size" to texData.size,
                "trailing_bytes" to (texData.size - mipPos))
            if (mipPos + 9 <= texData.size) {
                val sectionMagic = String(texData, mipPos, 4, Charsets.US_ASCII)
                if (sectionMagic == "TEXS") {
                    val texsBytes = texData.copyOfRange(mipPos, texData.size)
                    out.write(texsBytes)
                    WepWawetLogger.proxy("MpkgPacker.convertTexDxtToEtc2: preserved TEXS section (multi-mipmap-aware)",
                        "name" to name,
                        "texs_size" to texsBytes.size,
                        "texs_magic" to String(texsBytes, 0, minOf(8, texsBytes.size), Charsets.US_ASCII),
                        "mipmap_count_walked" to texsMipmapCount)
                } else {
                    WepWawetLogger.proxyDebug("MpkgPacker.convertTexDxtToEtc2: trailing data after last mipmap is not TEXS",
                        "name" to name,
                        "trailing_size" to (texData.size - mipPos),
                        "trailing_magic" to sectionMagic)
                }
            }

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

            // ---- Step 9: Store result in texture conversion cache ----
            texCacheStore(texData, result, name)

            return result
        } catch (e: Exception) {
            WepWawetLogger.proxyError("MpkgPacker.convertTexDxtToEtc2 FAILED",
                "name" to name, "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            return null
        }
    }

    /**
     * Convert a .tex file with RG88 (2-channel: R, G) pixel data to ETC2_RGBA8 + LZ4.
     *
     * v0.6.0 (parity §6 6-2): RG88 (format=8) is used by modern scene wallpapers
     * for normal/roughness/metalness maps. Without this conversion, the .tex is
     * passed through unchanged — Android's libscenejni.so may render it with wrong
     * color channels (typically only R is sampled, losing the G channel).
     *
     * Pipeline:
     *   1. Parse .tex header (same as convertTexDxtToEtc2)
     *   2. Get body bytes (LZ4-decompressed if needed)
     *   3. Expand RG88 → RGBA8888 (R=R, G=G, B=0, A=255)
     *   4. Encode RGBA → ETC2 RGBA8 + LZ4
     *   5. Build new .tex with format=5 (ETC2 RGBA8), compression=1 (LZ4)
     *
     * @param texData The original .tex bytes
     * @param name For logging
     * @return New .tex bytes, or null on failure
     */
    private fun convertTexRg88ToEtc2(texData: ByteArray, name: String): ByteArray? {
        try {
            // ---- Check cache ----
            texCacheLookup(texData, name)?.let { cached ->
                WepWawetLogger.proxy("MpkgPacker.convertTexRg88ToEtc2: using cached conversion",
                    "name" to name, "cached_size" to cached.size)
                return cached
            }

            if (!Etc2Encoder.isAvailable()) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexRg88ToEtc2: ETC2 native lib not available",
                    "name" to name)
                return null
            }

            if (texData.size < 91) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexRg88ToEtc2: .tex too small",
                    "name" to name, "tex_size" to texData.size)
                return null
            }

            val texWidth = readU32Le(texData, 26).toInt()
            val texHeight = readU32Le(texData, 30).toInt()
            val origFlags = readLeInt(texData, 22)
            val origUnkInt0 = readLeInt(texData, 42)
            val isLz4 = readU32Le(texData, 79).toInt() != 0
            val decompressedSize = readU32Le(texData, 83).toInt()
            val byteCount = readU32Le(texData, 87).toInt()

            if (texWidth <= 0 || texHeight <= 0) return null
            if (91 + byteCount > texData.size) return null

            val bodyBytes = texData.copyOfRange(91, 91 + byteCount)
            val rg88Bytes = if (isLz4) {
                if (decompressedSize <= 0) return null
                try {
                    val lz4Factory = net.jpountz.lz4.LZ4Factory.safeInstance()
                    lz4Factory.safeDecompressor().decompress(bodyBytes, decompressedSize)
                } catch (e: Exception) {
                    WepWawetLogger.proxyError("MpkgPacker.convertTexRg88ToEtc2: LZ4 decompression failed",
                        "name" to name, "exception" to e.javaClass.name)
                    return null
                }
            } else bodyBytes

            val pixelCount = texWidth * texHeight
            if (rg88Bytes.size < pixelCount * 2) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexRg88ToEtc2: RG88 data too small",
                    "name" to name, "expected" to (pixelCount * 2), "actual" to rg88Bytes.size)
                return null
            }

            // Expand RG88 → RGBA8888 (R=R, G=G, B=0, A=255)
            val rgbaBytes = ByteArray(pixelCount * 4)
            for (i in 0 until pixelCount) {
                rgbaBytes[i * 4 + 0] = rg88Bytes[i * 2 + 0]  // R
                rgbaBytes[i * 4 + 1] = rg88Bytes[i * 2 + 1]  // G
                rgbaBytes[i * 4 + 2] = 0                       // B (no blue in RG88)
                rgbaBytes[i * 4 + 3] = 255.toByte()            // A (opaque)
            }

            // Encode RGBA → ETC2 RGBA8 + LZ4 (same as convertTexDxtToEtc2)
            val isOpaque = true  // We just set A=255 everywhere
            val etc2Bytes = try {
                Etc2Encoder.encodeRgba8888(rgbaBytes, texWidth, texHeight, isOpaque)
            } catch (e: Exception) {
                WepWawetLogger.proxyError("MpkgPacker.convertTexRg88ToEtc2: ETC2 encoding failed",
                    "name" to name, "exception" to e.javaClass.name)
                return null
            }
            val lz4Compressed = net.jpountz.lz4.LZ4Factory.safeInstance().fastCompressor().compress(etc2Bytes)

            // Build output .tex (format=5 ETC2_RGBA8, compression=1 LZ4)
            val out = java.io.ByteArrayOutputStream()
            out.write(texData, 0, 18)
            writeLeIntToStream(out, 5)
            writeLeIntToStream(out, origFlags)
            writeLeIntToStream(out, texWidth)
            writeLeIntToStream(out, texHeight)
            writeLeIntToStream(out, texWidth)
            writeLeIntToStream(out, texHeight)
            writeLeIntToStream(out, origUnkInt0)
            out.write(byteArrayOf(0x54, 0x45, 0x58, 0x42, 0x30, 0x30, 0x30, 0x34, 0x00))
            writeLeIntToStream(out, 1)
            writeLeIntToStream(out, -1)
            writeLeIntToStream(out, 0)
            writeLeIntToStream(out, 1)
            writeLeIntToStream(out, texWidth)
            writeLeIntToStream(out, texHeight)
            writeLeIntToStream(out, 1)
            writeLeIntToStream(out, etc2Bytes.size)
            writeLeIntToStream(out, lz4Compressed.size)
            out.write(lz4Compressed)

            val result = out.toByteArray()
            texCacheStore(texData, result, name)
            WepWawetLogger.proxy("MpkgPacker.convertTexRg88ToEtc2 SUCCESS",
                "name" to name, "original_size" to texData.size, "converted_size" to result.size)
            return result
        } catch (e: Exception) {
            WepWawetLogger.proxyError("MpkgPacker.convertTexRg88ToEtc2 FAILED",
                "name" to name, "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            return null
        }
    }

    /**
     * Convert a .tex file with R8 (1-channel: R) pixel data to ETC2_RGBA8 + LZ4.
     *
     * v0.6.0 (parity §6 6-2): R8 (format=9) is used by scene wallpapers for
     * grayscale / single-channel masks. Without this conversion, the .tex is
     * passed through unchanged — Android may sample only the red channel and
     * miss the grayscale information.
     *
     * Pipeline:
     *   1. Parse .tex header
     *   2. Get body bytes (LZ4-decompressed if needed)
     *   3. Expand R8 → RGBA8888 (R=R, G=R, B=R, A=255) — grayscale
     *   4. Encode RGBA → ETC2 RGBA8 + LZ4
     *   5. Build new .tex with format=5 (ETC2 RGBA8), compression=1 (LZ4)
     *
     * @param texData The original .tex bytes
     * @param name For logging
     * @return New .tex bytes, or null on failure
     */
    private fun convertTexR8ToEtc2(texData: ByteArray, name: String): ByteArray? {
        try {
            texCacheLookup(texData, name)?.let { cached ->
                WepWawetLogger.proxy("MpkgPacker.convertTexR8ToEtc2: using cached conversion",
                    "name" to name, "cached_size" to cached.size)
                return cached
            }

            if (!Etc2Encoder.isAvailable()) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexR8ToEtc2: ETC2 native lib not available",
                    "name" to name)
                return null
            }

            if (texData.size < 91) return null

            val texWidth = readU32Le(texData, 26).toInt()
            val texHeight = readU32Le(texData, 30).toInt()
            val origFlags = readLeInt(texData, 22)
            val origUnkInt0 = readLeInt(texData, 42)
            val isLz4 = readU32Le(texData, 79).toInt() != 0
            val decompressedSize = readU32Le(texData, 83).toInt()
            val byteCount = readU32Le(texData, 87).toInt()

            if (texWidth <= 0 || texHeight <= 0) return null
            if (91 + byteCount > texData.size) return null

            val bodyBytes = texData.copyOfRange(91, 91 + byteCount)
            val r8Bytes = if (isLz4) {
                if (decompressedSize <= 0) return null
                try {
                    val lz4Factory = net.jpountz.lz4.LZ4Factory.safeInstance()
                    lz4Factory.safeDecompressor().decompress(bodyBytes, decompressedSize)
                } catch (e: Exception) { return null }
            } else bodyBytes

            val pixelCount = texWidth * texHeight
            if (r8Bytes.size < pixelCount) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexR8ToEtc2: R8 data too small",
                    "name" to name, "expected" to pixelCount, "actual" to r8Bytes.size)
                return null
            }

            // Expand R8 → RGBA8888 (R=R, G=R, B=R, A=255) — grayscale
            val rgbaBytes = ByteArray(pixelCount * 4)
            for (i in 0 until pixelCount) {
                val v = r8Bytes[i]
                rgbaBytes[i * 4 + 0] = v  // R
                rgbaBytes[i * 4 + 1] = v  // G (grayscale)
                rgbaBytes[i * 4 + 2] = v  // B (grayscale)
                rgbaBytes[i * 4 + 3] = 255.toByte()  // A
            }

            val isOpaque = true
            val etc2Bytes = try {
                Etc2Encoder.encodeRgba8888(rgbaBytes, texWidth, texHeight, isOpaque)
            } catch (e: Exception) { return null }
            val lz4Compressed = net.jpountz.lz4.LZ4Factory.safeInstance().fastCompressor().compress(etc2Bytes)

            val out = java.io.ByteArrayOutputStream()
            out.write(texData, 0, 18)
            writeLeIntToStream(out, 5)
            writeLeIntToStream(out, origFlags)
            writeLeIntToStream(out, texWidth)
            writeLeIntToStream(out, texHeight)
            writeLeIntToStream(out, texWidth)
            writeLeIntToStream(out, texHeight)
            writeLeIntToStream(out, origUnkInt0)
            out.write(byteArrayOf(0x54, 0x45, 0x58, 0x42, 0x30, 0x30, 0x30, 0x34, 0x00))
            writeLeIntToStream(out, 1)
            writeLeIntToStream(out, -1)
            writeLeIntToStream(out, 0)
            writeLeIntToStream(out, 1)
            writeLeIntToStream(out, texWidth)
            writeLeIntToStream(out, texHeight)
            writeLeIntToStream(out, 1)
            writeLeIntToStream(out, etc2Bytes.size)
            writeLeIntToStream(out, lz4Compressed.size)
            out.write(lz4Compressed)

            val result = out.toByteArray()
            texCacheStore(texData, result, name)
            WepWawetLogger.proxy("MpkgPacker.convertTexR8ToEtc2 SUCCESS",
                "name" to name, "original_size" to texData.size, "converted_size" to result.size)
            return result
        } catch (e: Exception) {
            WepWawetLogger.proxyError("MpkgPacker.convertTexR8ToEtc2 FAILED",
                "name" to name, "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            return null
        }
    }

    /**
     * Convert a .tex file with RGBA8888 pixel data to ETC2_RGBA8 + LZ4.
     *
     * v0.6.0 (parity §6 6-3): Previously RGBA8888 textures (format=20) were
     * passed through unchanged. This produced .mpkg files 4× larger than
     * necessary. Real WE PC converts RGBA8888 → ETC2_RGBA8 + LZ4 (~4× smaller).
     *
     * Pipeline:
     *   1. Parse .tex header
     *   2. Get body bytes (LZ4-decompressed if needed) — already RGBA8888
     *   3. Detect opaque vs alpha-textured (sample every 1024th pixel's alpha)
     *   4. Encode RGBA → ETC2 RGBA8 (with RGBX metric for opaque) + LZ4
     *   5. Build new .tex with format=5 (ETC2 RGBA8), compression=1 (LZ4)
     *
     * @param texData The original .tex bytes
     * @param name For logging
     * @return New .tex bytes, or null on failure
     */
    private fun convertTexRgba8888ToEtc2(texData: ByteArray, name: String): ByteArray? {
        try {
            texCacheLookup(texData, name)?.let { cached ->
                WepWawetLogger.proxy("MpkgPacker.convertTexRgba8888ToEtc2: using cached conversion",
                    "name" to name, "cached_size" to cached.size)
                return cached
            }

            if (!Etc2Encoder.isAvailable()) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexRgba8888ToEtc2: ETC2 native lib not available — passing through unchanged",
                    "name" to name)
                return null
            }

            if (texData.size < 91) return null

            val texWidth = readU32Le(texData, 26).toInt()
            val texHeight = readU32Le(texData, 30).toInt()
            val origFlags = readLeInt(texData, 22)
            val origUnkInt0 = readLeInt(texData, 42)
            val isLz4 = readU32Le(texData, 79).toInt() != 0
            val decompressedSize = readU32Le(texData, 83).toInt()
            val byteCount = readU32Le(texData, 87).toInt()

            if (texWidth <= 0 || texHeight <= 0) return null
            if (91 + byteCount > texData.size) return null

            val bodyBytes = texData.copyOfRange(91, 91 + byteCount)
            val rgbaBytes = if (isLz4) {
                if (decompressedSize <= 0) return null
                try {
                    val lz4Factory = net.jpountz.lz4.LZ4Factory.safeInstance()
                    lz4Factory.safeDecompressor().decompress(bodyBytes, decompressedSize)
                } catch (e: Exception) { return null }
            } else bodyBytes

            val expectedSize = texWidth * texHeight * 4
            if (rgbaBytes.size < expectedSize) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexRgba8888ToEtc2: RGBA data too small",
                    "name" to name, "expected" to expectedSize, "actual" to rgbaBytes.size)
                return null
            }

            // v0.6.0 (parity §6 6-7): Detect opaque vs alpha-textured.
            // For opaque textures, use RGBX error metric (treats alpha as don't-care,
            // gaining ~0.5 dB PSNR on RGB channels).
            // Sample every 1024th pixel's alpha to avoid full scan.
            var isOpaque = true
            val sampleStep = 4 * 1024
            var i = 3  // alpha byte of first pixel
            while (i < rgbaBytes.size) {
                if ((rgbaBytes[i].toInt() and 0xFF) != 255) {
                    isOpaque = false
                    break
                }
                i += sampleStep
            }

            val etc2Bytes = try {
                Etc2Encoder.encodeRgba8888(rgbaBytes, texWidth, texHeight, isOpaque)
            } catch (e: Exception) {
                WepWawetLogger.proxyError("MpkgPacker.convertTexRgba8888ToEtc2: ETC2 encoding failed",
                    "name" to name, "exception" to e.javaClass.name)
                return null
            }
            val lz4Compressed = net.jpountz.lz4.LZ4Factory.safeInstance().fastCompressor().compress(etc2Bytes)

            val out = java.io.ByteArrayOutputStream()
            out.write(texData, 0, 18)
            writeLeIntToStream(out, 5)
            writeLeIntToStream(out, origFlags)
            writeLeIntToStream(out, texWidth)
            writeLeIntToStream(out, texHeight)
            writeLeIntToStream(out, texWidth)
            writeLeIntToStream(out, texHeight)
            writeLeIntToStream(out, origUnkInt0)
            out.write(byteArrayOf(0x54, 0x45, 0x58, 0x42, 0x30, 0x30, 0x30, 0x34, 0x00))
            writeLeIntToStream(out, 1)
            writeLeIntToStream(out, -1)
            writeLeIntToStream(out, 0)
            writeLeIntToStream(out, 1)
            writeLeIntToStream(out, texWidth)
            writeLeIntToStream(out, texHeight)
            writeLeIntToStream(out, 1)
            writeLeIntToStream(out, etc2Bytes.size)
            writeLeIntToStream(out, lz4Compressed.size)
            out.write(lz4Compressed)

            val result = out.toByteArray()
            texCacheStore(texData, result, name)
            WepWawetLogger.proxy("MpkgPacker.convertTexRgba8888ToEtc2 SUCCESS",
                "name" to name,
                "original_size" to texData.size,
                "converted_size" to result.size,
                "is_opaque" to isOpaque,
                "size_ratio" to "%.2fx".format(result.size.toDouble() / texData.size.toDouble()))
            return result
        } catch (e: Exception) {
            WepWawetLogger.proxyError("MpkgPacker.convertTexRgba8888ToEtc2 FAILED",
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
     *      (transcoder disasm line 14003f27b — value wrapped in {"value": <int>})
     *   8. Sets the "maximumprojectid" field
     *      (transcoder disasm line 14003fceb — value is the max object ID
     *       from scene.json's "objects" array, used by the Android side to
     *       pre-allocate object slots)
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
     * @param maxObjectId The maximum object ID computed from scene.json's
     *                    "objects" array (for setting "maximumprojectid").
     *                    Pass -1 to skip setting this field.
     * @return The rewritten project.json bytes, or null on failure (caller
     *         should use the original bytes in that case).
     */
    private fun rewriteProjectJsonForMobile(projectJsonBytes: ByteArray, maxObjectId: Int = -1): ByteArray? {
        return try {
            val jsonStr = String(projectJsonBytes, Charsets.UTF_8)
            val root = JsonParser.parseString(jsonStr).asJsonObject
            var modified = false

            // 1. Remove the "video" key if present (per transcoder disasm line 14003eb70)
            // The .pkg's project.json may have a "video" section (with codec/codec params)
            // that the .mpkg's project.json does NOT have. WE PC's transcoder removes it.
            if (root.has("video")) {
                root.remove("video")
                modified = true
                WepWawetLogger.proxy("MpkgPacker.rewriteProjectJsonForMobile: removed video section (per WE PC transcoder)")
            }

            // 2. Set "type" explicitly to lowercase (preserves existing value, just normalized)
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

            // 3. Set "maximumprojectid" (per transcoder disasm line 14003fceb)
            // Value is the max object ID across scene.json's "objects" array.
            // This tells the Android side how many object slots to pre-allocate.
            if (maxObjectId >= 0) {
                root.addProperty("maximumprojectid", maxObjectId)
                modified = true
                WepWawetLogger.proxy("MpkgPacker.rewriteProjectJsonForMobile: set maximumprojectid",
                    "value" to maxObjectId)
            }

            // 4. Set "alignmentposition" for video wallpapers
            // (per transcoder disasm line 14003f27b)
            //
            // The transcoder wraps the value in {"value": <int>} format:
            //   - Line 14003f2a5: loads int from struct (r13+0xb0)
            //   - Line 14003f2b6: converts int to string
            //   - Line 14003f2be: concatenates "{"value":" + int_string + "}"
            //   - Sets as the "alignmentposition" field value
            //
            // The value represents the video's alignment position (0=centered,
            // 1=top-left, etc.). Without this field, the Android app uses a
            // default centered alignment.
            //
            // We only set this for video wallpapers (type=video). For scene
            // wallpapers, the field is not relevant.
            val currentType = root.get("type")?.asString?.lowercase() ?: "unknown"
            if (currentType == "video") {
                if (!root.has("alignmentposition")) {
                    // Default: 0 (centered) — matches what the transcoder sets
                    // when the source doesn't specify a custom alignment.
                    root.addProperty("alignmentposition", "{\"value\": 0}")
                    modified = true
                    WepWawetLogger.proxy("MpkgPacker.rewriteProjectJsonForMobile: set alignmentposition",
                        "value" to "{\"value\": 0}",
                        "note" to "default centered (video wallpaper)")
                }
            }

            // NOTE: "file" and "preview" fields are PRESERVED as-is (no MPKG_VFS:// prefix).
            // The Android app's libscenejni.so looks up files by their relative paths
            // in the .mpkg VFS (e.g., "scene.json", "preview.gif", "materials/sun.tex").
            // Adding MPKG_VFS:// prefix breaks the file lookups → black image.
            // (Tested in v0.5.24-alpha — adding MPKG_VFS:// prefix caused the Saturn
            //  wallpaper to render as a completely black image. Reverted in v0.5.26-alpha.)

            if (!modified) {
                WepWawetLogger.proxyDebug("MpkgPacker.rewriteProjectJsonForMobile: no changes needed (already in mobile format)")
                return projectJsonBytes
            }

            // Serialize back to JSON with WE PC's formatting (tabs + \r\n line endings)
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
     * Compute the maximum object ID from scene.json's "objects" array.
     *
     * Per the transcoder disasm (line 14003fceb), WE PC sets the
     * "maximumprojectid" field in project.json to the max object ID
     * across scene.json's objects. This tells the Android side how many
     * object slots to pre-allocate (avoids dynamic array growth).
     *
     * The "objects" array contains entries like:
     *   { "id": 1, "effects": [{ "id": 17, ... }] }
     *
     * We walk the entire tree and find the maximum "id" value.
     *
     * @param sceneJsonBytes The scene.json bytes.
     * @return The maximum "id" value found, or -1 if no ids found or on error.
     */
    private fun computeMaxObjectId(sceneJsonBytes: ByteArray): Int {
        return try {
            val root = JsonParser.parseString(String(sceneJsonBytes, Charsets.UTF_8)).asJsonObject
            var maxId = -1
            /**
             * Recursively walk the JSON tree and find all "id" fields.
             */
            fun walkIdFields(value: com.google.gson.JsonElement) {
                if (value.isJsonObject) {
                    val obj = value.asJsonObject
                    val idField = obj.get("id")
                    if (idField != null && idField.isJsonPrimitive) {
                        try {
                            val id = idField.asInt
                            if (id > maxId) maxId = id
                        } catch (_: Exception) { /* not a number, skip */ }
                    }
                    for (key in obj.keySet()) {
                        walkIdFields(obj.get(key))
                    }
                } else if (value.isJsonArray) {
                    val arr = value.asJsonArray
                    val size = arr.size()
                    for (i in 0 until size) {
                        walkIdFields(arr[i])
                    }
                }
            }
            walkIdFields(root)
            maxId
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("MpkgPacker.computeMaxObjectId FAILED — returning -1",
                "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            -1
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
        // REVERTED in v0.5.26-alpha: The recursive path rewriting (adding MPKG_VFS://
        // prefix to all path-like strings in scene.json) was causing the Saturn
        // wallpaper to render as a completely black image on Android.
        //
        // The Android app's libscenejni.so looks up textures by their RELATIVE
        // paths in the .mpkg VFS (e.g., "materials/sun.tex"). Adding the
        // MPKG_VFS:// prefix changed these to "MPKG_VFS://materials/sun.tex"
        // which does NOT exist as a VFS entry name → no textures found → black.
        //
        // The fixSceneJsonTextureBindings function (called earlier in packFromPkg)
        // already handles the null texture bindings. No further scene.json rewrite
        // is needed for the Android app to correctly render Scene wallpapers.
        //
        // The transcoder disasm at lines 14004104e/1400410cd/140041125 does
        // iterate over a "files" array and set "value" fields, but this appears
        // to be for an INTERNAL data structure used during transcoding (not
        // written to the .mpkg's scene.json). The .mpkg's scene.json should
        // preserve the same texture paths as the .pkg's scene.json.
        WepWawetLogger.proxyDebug("MpkgPacker.rewriteSceneJsonForMobile: no-op (recursive rewrite reverted to prevent black image)")
        return sceneJsonBytes
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
            // PHASE 0: Try native glslang+SPIRV-Cross transcoder FIRST (v0.6.0 §7-3)
            // =========================================================================
            // v0.6.0 (parity §7-3): If the native shader transcoder is available, use it
            // INSTEAD of the regex path. The native path is more correct (it actually parses
            // the shader AST, so int→float conversion is type-aware, not regex-blind).
            //
            // We try native for HLSL files (file extension .hlsl or contains HLSL syntax
            // like cbuffer/SamplerState/Texture2D/mul()/saturate()/lerp()), and fall back
            // to the regex path if it fails.
            //
            // For GLSL files (.vert/.frag), we still go through the regex path because:
            // 1. The native path produces output that may not be byte-identical to what
            //    the WE Android renderer expects (e.g., SPIRV-Cross renames variables,
            //    inserts unused #version directives, etc.)
            // 2. The regex path has been battle-tested across many wallpapers and is the
            //    source of truth for what works on WE Android.
            // 3. For pure HLSL files (no .vert/.frag extension), the regex path can't help
            //    (it doesn't do HLSL→GLSL syntax conversion), so the native path is the only option.
            if (ShaderTranscoder.isAvailable()) {
                val isHlsl = name.endsWith(".hlsl", ignoreCase = true) ||
                    modified.contains("cbuffer ") ||
                    modified.contains("SamplerState ") ||
                    modified.contains("Texture2D ") ||
                    modified.contains("saturate(") ||
                    modified.contains("lerp(") ||
                    (modified.contains("mul(") && !modified.contains("mix("))
                if (isHlsl) {
                    val stage = if (name.endsWith(".vert", ignoreCase = true) ||
                                    name.endsWith("_vs", ignoreCase = true) ||
                                    name.endsWith("vertex", ignoreCase = true)) {
                        ShaderTranscoder.STAGE_VERTEX
                    } else if (name.endsWith(".geom", ignoreCase = true)) {
                        ShaderTranscoder.STAGE_GEOMETRY
                    } else if (name.endsWith(".hull", ignoreCase = true) ||
                                name.endsWith("_tc", ignoreCase = true)) {
                        ShaderTranscoder.STAGE_HULL
                    } else if (name.endsWith(".domain", ignoreCase = true) ||
                                name.endsWith("_te", ignoreCase = true)) {
                        ShaderTranscoder.STAGE_DOMAIN
                    } else if (name.endsWith(".comp", ignoreCase = true)) {
                        ShaderTranscoder.STAGE_COMPUTE
                    } else {
                        ShaderTranscoder.STAGE_FRAGMENT
                    }
                    val transcoded = ShaderTranscoder.transcodeShader(original, stage, isHlsl = true)
                    if (transcoded != null) {
                        WepWawetLogger.proxy("MpkgPacker.fixShaders: native HLSL→GLSL transcode succeeded",
                            "file" to name, "stage" to stage, "input_size" to original.length,
                            "output_size" to transcoded.length)
                        entries[i] = Pair(name, transcoded.toByteArray(Charsets.UTF_8))
                        fixedCount++
                        continue  // Skip the regex path — native already produced GLSL ES 3.00
                    } else {
                        WepWawetLogger.proxyWarn("MpkgPacker.fixShaders: native HLSL→GLSL transcode failed — falling back to regex path",
                            "file" to name, "stage" to stage)
                    }
                }
            }

            // =========================================================================
            // PHASE 0.5: HLSL→GLSL syntax conversion (regex fallback path — v0.6.0 §7-6)
            // =========================================================================
            // v0.6.0 (parity §7-6): If the native transcoder is unavailable OR the file is
            // GLSL but contains HLSL syntax, do basic HLSL→GLSL conversions.
            // This is a stopgap for the case where glslang failed to compile (network issue
            // during build, etc.) but the wallpaper still needs to render.
            //
            // Conversions:
            //   cbuffer Name { ... } → uniform Name { ... };
            //   SamplerState s; → sampler2D s;
            //   Texture2D t; → sampler2D t;
            //   mul(a, b) → (a * b)
            //   saturate(x) → clamp(x, 0.0, 1.0)
            //   lerp(a, b, t) → mix(a, b, t)
            //   frac(x) → fract(x)
            //   ddx(x) → dFdx(x)
            //   ddy(x) → dFdy(x)
            if (!ShaderTranscoder.isAvailable() || modified.contains("cbuffer ") ||
                modified.contains("SamplerState ") || modified.contains("Texture2D ")) {
                // cbuffer Name { ... } → uniform Name { ... };
                val beforeHlsl = modified.length
                modified = modified.replace(
                    Regex("""cbuffer\s+(\w+)\s*\{([^}]+)\}""", RegexOption.DOT_MATCHES_ALL)
                ) { mr ->
                    "uniform ${mr.groupValues[1]} {\n${mr.groupValues[2]}\n};"
                }
                // SamplerState s; → sampler2D s;
                modified = modified.replace(Regex("""\bSamplerState\s+(\w+)\s*;"""), "sampler2D $1;")
                // Texture2D t; → sampler2D t;
                modified = modified.replace(Regex("""\bTexture2D\s+(\w+)\s*;"""), "sampler2D $1;")
                // mul(a, b) → (a * b)
                modified = modified.replace(Regex("""\bmul\s*\(([^,]+),\s*([^)]+)\)""")) { mr ->
                    "(${mr.groupValues[1]} * ${mr.groupValues[2]})"
                }
                // saturate(x) → clamp(x, 0.0, 1.0)
                modified = modified.replace(Regex("""\bsaturate\s*\(([^)]+)\)""")) { mr ->
                    "clamp(${mr.groupValues[1]}, 0.0, 1.0)"
                }
                // lerp(a, b, t) → mix(a, b, t)
                modified = modified.replace(Regex("""\blerp\s*\(([^,]+),\s*([^,]+),\s*([^)]+)\)""")) { mr ->
                    "mix(${mr.groupValues[1]}, ${mr.groupValues[2]}, ${mr.groupValues[3]})"
                }
                // frac(x) → fract(x)
                modified = modified.replace(Regex("""\bfrac\s*\("""), "fract(")
                // ddx(x) → dFdx(x), ddy(x) → dFdy(x)
                modified = modified.replace(Regex("""\bddx\s*\("""), "dFdx(")
                modified = modified.replace(Regex("""\bddy\s*\("""), "dFdy(")
                if (modified.length != beforeHlsl) {
                    fixesApplied.add("HLSL→GLSL syntax conversion (fallback path)")
                }
            }

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
            // WE PC pattern: `for \(int (.+) = 0; i < sampleCount;` → `for (float $1 = 0.0; i < float(sampleCount);`
            // This converts `for (int i = 0; i < sampleCount; ...)` to `for (float i = 0.0; i < float(sampleCount); ...)`
            // We MUST do this BEFORE the general int→float regex, otherwise `for (int i = 0;`
            // becomes `for (int i = 0.0;` which is invalid (can't assign float to int).
            //
            // v0.5.33-alpha (solar system shader fix): THREE critical improvements over v0.5.31:
            //   1. Match ANY integer init value (not just 0) — the solar system shaders
            //      have `for (int i = 1; i <= NUM_SAMPLES;` (starts at 1, uses `<=`).
            //      The old regex `=\s*0\s*;` only matched init=0, missing these loops,
            //      which then got corrupted by PASS J's comparisonRegex (matching `=`
            //      as a comparison operator → `for (int float(i) = 1.0;`).
            //   2. Match ALL comparison operators (`<=`, `>=`, `<`, `>`) — the old regex
            //      only matched `<`. Shaders using `<=` were missed.
            //   3. Wrap the LIMIT variable with `float()` — without this, `i < LIGHT_SAMPLES`
            //      (where LIGHT_SAMPLES is `#define ... 8`, an int) becomes `float < int`
            //      after the counter is converted to float, which is a GLSL ES 3.00 error.
            //      Wrapping with `float()` makes it `float < float(int)`, which is valid.
            val forLoopBeforeCount = Regex("""for\s*\(\s*int\s+""").findAll(modified).count()
            modified = modified.replace(
                Regex("""for\s*\(\s*int\s+(\w+)\s*=\s*(\d+)\s*;\s*(\w+)\s*(<=|>=|<|>)\s*(\w+)\s*;""")
            ) { mr ->
                val iVar = mr.groupValues[1]
                val initVal = mr.groupValues[2]
                val condVar = mr.groupValues[3]
                val op = mr.groupValues[4]
                val limitVar = mr.groupValues[5]
                // Only convert if the loop variable matches the condition variable
                if (iVar == condVar) {
                    val initFloat = "${initVal}.0"
                    val result = "for (float $iVar = $initFloat; $condVar $op float($limitVar);"
                    fixesApplied.add("for(int $iVar=$initVal; $condVar$op$limitVar;)→for(float $iVar=$initFloat; $condVar$op float($limitVar);)")
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

            // --- Fix 1A2: Wrap LIMIT with float() for already-converted for-loops ---
            // v0.5.33-alpha (solar system shader fix): This handles for-loops that were
            // ALREADY converted to `float` by Fix 1A (or by the HLSL→GLSL transpiler),
            // but whose LIMIT variable wasn't wrapped with `float()`.
            //
            // Example: `for (float i = 0.0; i < LIGHT_SAMPLES;` where LIGHT_SAMPLES is
            // `#define LIGHT_SAMPLES 8` (an int). After macro expansion, this becomes
            // `i < 8` (float < int), which is a GLSL ES 3.00 error.
            //
            // Fix: wrap LIMIT with `float()`: `for (float i = 0.0; i < float(LIGHT_SAMPLES);`
            // After macro expansion: `i < float(8)` (float < float), which is valid.
            //
            // This must run AFTER Fix 1A (so we don't double-process) but BEFORE
            // the rest of Phase 1 (so the for-loop is fixed before other passes).
            modified = modified.replace(
                Regex("""for\s*\(\s*float\s+(\w+)\s*=\s*(\d+\.\d+)\s*;\s*(\w+)\s*(<=|>=|<|>)\s*(\w+)\s*;""")
            ) { mr ->
                val iVar = mr.groupValues[1]
                val initVal = mr.groupValues[2]
                val condVar = mr.groupValues[3]
                val op = mr.groupValues[4]
                val limitVar = mr.groupValues[5]
                // Only wrap if the loop variable matches the condition variable
                if (iVar == condVar) {
                    // Only wrap if LIMIT is not already float() and not a numeric literal
                    // (numeric literals would have been caught by Fix 1D's int→float conversion)
                    if (!limitVar.startsWith("float(") && !limitVar.first().isDigit()) {
                        val result = "for (float $iVar = $initVal; $condVar $op float($limitVar);"
                        fixesApplied.add("for(float $iVar=$initVal; $condVar$op$limitVar;)→...$op float($limitVar);)")
                        result
                    } else {
                        mr.value
                    }
                } else {
                    mr.value
                }
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
            // Also catch `const int N = X;` for other common loop-limit names
            // v0.5.33-alpha (solar system shader fix): Also convert the int VALUE to
            // float (e.g., `const int NUM_SAMPLES = 14;` → `const float NUM_SAMPLES = 14.0;`).
            // Without this, `const float NUM_SAMPLES = 14;` (int value) fails with
            // "non-matching or non-convertible constant type for const initializer"
            // because you can't assign an int literal to a const float variable.
            modified = modified.replace(
                Regex("""(\s*const\s+)int\s+(sampleCount|kernelSize|NUM_TAPS|TAP_COUNT|SAMPLE_COUNT|NUM_SAMPLES)\s*=\s*(\d+)\s*;""")
            ) { mr ->
                val constName = mr.groupValues[2]
                val intVal = mr.groupValues[3]
                val result = "${mr.groupValues[1]}float $constName = ${intVal}.0;"
                fixesApplied.add("'const int $constName = $intVal;'→'const float $constName = ${intVal}.0;'")
                result
            }
            // Legacy: catch `const int N =` (without value) for the original names
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
            //
            // v0.5.33-alpha (solar system shader fix): The old regex
            // `(\d+\.\d+e[-]?\d+)\.0` only matched the `1.0e3.0` form (with a leading
            // decimal point). But Fix 1D's int→float regex also matches the EXPONENT
            // part of scientific notation numbers like `1e-6` — it sees the `6`
            // (preceded by `-`, which is in the operator char class) and adds `.0`,
            // producing `1e-6.0` (INVALID — you can't have `.0` after the exponent).
            //
            // The dqss2.frag shader (part of the Live Solar System wallpaper) has
            // `max(den, 1e-6)` which became `max(den, 1e-6.0)` — a syntax error that
            // prevented the entire shader from compiling.
            //
            // Fix: extend the regex to ALSO match the `1e-6.0` form (no leading
            // decimal point). The new regex `(\d+(?:\.\d+)?e[-+]?\d+)\.0` matches:
            //   - `1.0e3.0` → `1.0e3` (group 1 = `1.0e3`, suffix `.0` removed)
            //   - `1e-6.0`  → `1e-6`  (group 1 = `1e-6`,  suffix `.0` removed)
            //   - `1.5e-3.0`→ `1.5e-3`(group 1 = `1.5e-3`, suffix `.0` removed)
            // The `(?:\.\d+)?` makes the decimal part optional, so both forms match.
            val sciNotationRegex = Regex("""(\d+(?:\.\d+)?e[-+]?\d+)\.0""")
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

            // v0.6.0 (parity §7-4): Inline additional WE PC common headers that were previously
            // missing. Workshop shaders using these headers would fail to compile, silently
            // dropping their effects. Real WE PC resolves these from its installation's
            // effects/ directory.

            // common_fog.h — fog effects
            if (modified.contains("#include \"common_fog.h\"")) {
                val fogHeader = """
// === common_fog.h INLINED by WepWawet (v0.6.0) ===
// Provides ApplyFog for distance-based fog effects.
uniform float g_FogDensity;
uniform vec4 g_FogColor;
float ApplyFog(float dist, float density, vec4 fogColor) {
    float factor = 1.0 - exp(-dist * dist * density * density);
    return clamp(factor, 0.0, 1.0);
}
"""
                modified = modified.replace(
                    Regex("""#include\s+"common_fog\.h\""""),
                    fogHeader
                )
                fixesApplied.add("inline-common_fog.h (ApplyFog)")
            }

            // common_lighting.h — PBR/lighting shaders
            if (modified.contains("#include \"common_lighting.h\"")) {
                val lightingHeader = """
// === common_lighting.h INLINED by WepWawet (v0.6.0) ===
// Provides ComputePBRLight, ComputePBRLightShadow for PBR rendering.
uniform vec4 g_LightColor;
uniform vec3 g_LightDirection;
float ComputePBRLight(vec3 normal, vec3 lightDir, vec3 viewDir, vec3 albedo, float roughness, float metalness) {
    vec3 halfDir = normalize(lightDir + viewDir);
    float NdotL = max(dot(normal, lightDir), 0.0);
    float NdotH = max(dot(normal, halfDir), 0.0);
    float NdotV = max(dot(normal, viewDir), 0.0);
    float VdotH = max(dot(viewDir, halfDir), 0.0);
    float specular = pow(NdotH, mix(1.0, 128.0, 1.0 - roughness));
    vec3 F0 = mix(vec3(0.04), albedo, metalness);
    vec3 Fresnel = F0 + (1.0 - F0) * pow(1.0 - VdotH, 5.0);
    vec3 specularContribution = Fresnel * specular * NdotL;
    vec3 diffuseContribution = albedo * NdotL * (1.0 - metalness);
    return diffuseContribution + specularContribution;
}
"""
                modified = modified.replace(
                    Regex("""#include\s+"common_lighting\.h\""""),
                    lightingHeader
                )
                fixesApplied.add("inline-common_lighting.h (ComputePBRLight)")
            }

            // common_math.h — math utilities
            if (modified.contains("#include \"common_math.h\"")) {
                val mathHeader = """
// === common_math.h INLINED by WepWawet (v0.6.0) ===
// Provides common math helpers.
float LinearToSRGB(float x) { return x <= 0.0031308 ? x * 12.92 : 1.055 * pow(x, 1.0/2.4) - 0.055; }
vec3 LinearToSRGB(vec3 c) { return vec3(LinearToSRGB(c.r), LinearToSRGB(c.g), LinearToSRGB(c.b)); }
float SRGBToLinear(float x) { return x <= 0.04045 ? x / 12.92 : pow((x + 0.055) / 1.055, 2.4); }
vec3 SRGBToLinear(vec3 c) { return vec3(SRGBToLinear(c.r), SRGBToLinear(c.g), SRGBToLinear(c.b)); }
float Luminance(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
float Remap(float v, float min1, float max1, float min2, float max2) {
    return min2 + (v - min1) * (max2 - min2) / (max1 - min1);
}
"""
                modified = modified.replace(
                    Regex("""#include\s+"common_math\.h\""""),
                    mathHeader
                )
                fixesApplied.add("inline-common_math.h (color space conversion + Remap)")
            }

            // common_particles.h — particle systems
            if (modified.contains("#include \"common_particles.h\"")) {
                val particlesHeader = """
// === common_particles.h INLINED by WepWawet (v0.6.0) ===
// Provides UpdateParticle, RenderParticle for particle effects.
uniform float g_ParticleTime;
struct Particle {
    vec3 position;
    vec3 velocity;
    vec4 color;
    float size;
    float life;
};
Particle UpdateParticle(Particle p, float dt) {
    p.position += p.velocity * dt;
    p.velocity.y -= 9.81 * dt;  // gravity
    p.life -= dt;
    return p;
}
"""
                modified = modified.replace(
                    Regex("""#include\s+"common_particles\.h\""""),
                    particlesHeader
                )
                fixesApplied.add("inline-common_particles.h (UpdateParticle, Particle struct)")
            }

            // common_shadow.h — shadow mapping
            if (modified.contains("#include \"common_shadow.h\"")) {
                val shadowHeader = """
// === common_shadow.h INLINED by WepWawet (v0.6.0) ===
// Provides PerformShadowMapping, ComputeShadowFactor for shadow rendering.
uniform sampler2DShadow g_ShadowMap;
uniform mat4 g_ShadowProjection;
uniform mat4 g_ShadowView;
float PerformShadowMapping(vec3 worldPos, sampler2DShadow shadowMap, mat4 shadowView, mat4 shadowProj) {
    vec4 shadowClip = shadowProj * shadowView * vec4(worldPos, 1.0);
    vec3 shadowCoord = shadowClip.xyz / shadowClip.w;
    shadowCoord = shadowCoord * 0.5 + 0.5;
    if (shadowCoord.x < 0.0 || shadowCoord.x > 1.0) return 1.0;
    if (shadowCoord.y < 0.0 || shadowCoord.y > 1.0) return 1.0;
    return texture(shadowMap, shadowCoord);
}
"""
                modified = modified.replace(
                    Regex("""#include\s+"common_shadow\.h\""""),
                    shadowHeader
                )
                fixesApplied.add("inline-common_shadow.h (PerformShadowMapping)")
            }

            // common_terrain.h — terrain rendering
            if (modified.contains("#include \"common_terrain.h\"")) {
                val terrainHeader = """
// === common_terrain.h INLINED by WepWawet (v0.6.0) ===
// Provides GetTerrainHeight, GetTerrainNormal for terrain rendering.
uniform sampler2D g_HeightMap;
uniform sampler2D g_NormalMap;
uniform vec2 g_TerrainSize;
float GetTerrainHeight(vec2 worldPos, sampler2D heightMap, vec2 terrainSize) {
    vec2 uv = worldPos / terrainSize;
    return texture(heightMap, uv).r * 100.0;  // scale to world units
}
vec3 GetTerrainNormal(vec2 worldPos, sampler2D normalMap, vec2 terrainSize) {
    vec2 uv = worldPos / terrainSize;
    return normalize(texture(normalMap, uv).xyz * 2.0 - 1.0);
}
"""
                modified = modified.replace(
                    Regex("""#include\s+"common_terrain\.h\""""),
                    terrainHeader
                )
                fixesApplied.add("inline-common_terrain.h (GetTerrainHeight, GetTerrainNormal)")
            }

            // common_postprocess.h — post-processing effects
            if (modified.contains("#include \"common_postprocess.h\"")) {
                val postprocessHeader = """
// === common_postprocess.h INLINED by WepWawet (v0.6.0) ===
// Provides ApplyBloom, ApplyVignette, ApplyChromaticAberration for post-process effects.
uniform float g_BloomIntensity;
uniform float g_VignetteIntensity;
vec3 ApplyBloom(vec3 color, vec3 bloomColor, float intensity) {
    return color + bloomColor * intensity;
}
vec3 ApplyVignette(vec3 color, vec2 uv, float intensity) {
    float dist = distance(uv, vec2(0.5));
    float vignette = 1.0 - smoothstep(0.3, 0.8, dist) * intensity;
    return color * vignette;
}
vec3 ApplyChromaticAberration(sampler2D tex, vec2 uv, float intensity) {
    vec2 dir = (uv - 0.5) * intensity;
    float r = texture(tex, uv - dir).r;
    float g = texture(tex, uv).g;
    float b = texture(tex, uv + dir).b;
    return vec3(r, g, b);
}
"""
                modified = modified.replace(
                    Regex("""#include\s+"common_postprocess\.h\""""),
                    postprocessHeader
                )
                fixesApplied.add("inline-common_postprocess.h (ApplyBloom, ApplyVignette, ApplyChromaticAberration)")
            }

            // PASS H: Remove duplicate uniform declarations introduced by inlined headers.
            //
            // When common_blur.h is inlined, it adds `uniform sampler2D g_Texture0;` as
            // part of its content. But the original shader also has `uniform sampler2D g_Texture0;`
            // (which was hoisted in PASS C). This creates a redefinition error:
            //   ERROR: 0:37: 'g_Texture0' : redefinition
            //
            // Fix: After all inlining, scan for duplicate `uniform <type> <name>;` declarations
            // (ignoring comments) and keep only the first occurrence.
            modified = dedupUniformDeclarations(modified)

            // PASS I: Add M_PI and M_PI_2 defines (HLSL compatibility).
            //
            // WE PC's HLSL shader preprocessor defines M_PI and M_PI_2 as:
            //   #define M_PI 3.14159265358979323846
            //   #define M_PI_2 1.57079632679489661923
            //
            // GLSL ES 3.00 does NOT define these. Shaders that use M_PI/M_PI_2 fail with:
            //   ERROR: 'M_PI_2' : undeclared identifier
            //
            // Fix: Add the defines at the top of the shader (after #version).
            // Only add if the shader actually uses M_PI or M_PI_2.
            if (modified.contains("M_PI") && !modified.contains("#define M_PI")) {
                val mpiDefines = """
// === M_PI / M_PI_2 defines (HLSL compatibility, added by WepWawet) ===
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif
#ifndef M_PI_2
#define M_PI_2 1.57079632679489661923
#endif
#ifndef M_PI_4
#define M_PI_4 0.78539816339744830962
#endif
#ifndef M_2_PI
#define M_2_PI 0.63661977236758134308
#endif
""".trimIndent()
                // Insert after #version line (or at the beginning if no #version)
                val versionMatch = Regex("""#version[^\n]*\n""").find(modified)
                modified = if (versionMatch != null) {
                    modified.replace(versionMatch.value, versionMatch.value + mpiDefines + "\n")
                } else {
                    mpiDefines + "\n" + modified
                }
                fixesApplied.add("M_PI/M_PI_2 defines")
            }

            // PASS J: Fix int-float type mismatches (HLSL→GLSL ES).
            //
            // HLSL allows implicit int↔float conversion. GLSL ES 3.00 does NOT.
            // Shaders like audio_base.frag have:
            //   int index = int(floor(position));
            //   if (index < 0.0) index = 0.0;     // ERROR: int < float, int = float
            //   float p0 = getAudioValueByBand(index - 1.0);  // ERROR: int - float
            //
            // Fix: Detect `int` variable declarations, then wrap their usage in
            // float contexts with `float()` casts. Specifically:
            //   1. `if (int_var <op> float_lit)` → `if (float(int_var) <op> float_lit)`
            //   2. `int_var = float_lit;` → `int_var = int(float_lit);`
            //   3. `int_var <op> float_lit` (arithmetic) → for whole-number floats,
            //      convert the float to int (preserves int type for function call args);
            //      for non-whole-number floats, wrap int_var with `float()`.
            //
            // We detect int variables by scanning for `int <varname>` declarations.
            //
            // v0.5.33-alpha (solar system shader fix): THREE critical bug fixes in
            // the regex patterns. The v0.5.31-alpha version of PASS J had bugs that
            // corrupted 5+ shaders in the Live Solar System wallpaper:
            //
            //   BUG 1 (comparisonRegex matched `=`):
            //     Old: `\b$varName\s*([<>=!]+)\s*(\d+\.\d+)`
            //     The char class `[<>=!]+` includes single `=` (assignment operator).
            //     So `NUM_SAMPLES = 14.0` (a const declaration) matched as a
            //     "comparison", producing `float(NUM_SAMPLES) = 14.0`. Combined with
            //     the `const int` prefix, this became `const int float(NUM_SAMPLES) = 14.0;`
            //     — a syntax error that broke guidao.frag, guidao2.frag, wxguidao.frag,
            //     wxguidao5678.frag (all solar system orbit shaders).
            //     Fix: use `(==|!=|<=|>=|<(?!=)|>(?!=))` which matches comparison
            //     operators ONLY, never a single `=`.
            //
            //   BUG 2 (arithRegex broke function call args):
            //     Old: `\b$varName\s*([+\-*/])\s*(\d+\.\d+)` → `float($varName) $op $float`
            //     For `getAudioValueByBand(index - 1.0)`, this produced
            //     `getAudioValueByBand(float(index) - 1.0)` — passing a FLOAT to a
            //     function that expects INT. GLSL ES 3.00 has no implicit conversion.
            //     Fix: for whole-number float literals (e.g., `1.0`), convert to int
            //     (`1`) instead of wrapping the int variable. This preserves the int
            //     type, so `getAudioValueByBand(index - 1)` is int - int = int. ✓
            //     Non-whole-number floats (e.g., `1.5`) still use the float() wrap.
            //
            //   BUG 3 (for-loop init matched by comparisonRegex):
            //     `for (int i = 1.0;` — the `i = 1.0` part matched comparisonRegex
            //     (because `=` was in the char class), producing
            //     `for (int float(i) = 1.0;` — invalid syntax.
            //     Fix: same as BUG 1 — don't match single `=`.
            val intVarPattern = Regex("""\bint\s+(\w+)\s*[=;,)]""")
            val intVars = intVarPattern.findAll(modified).map { it.groupValues[1] }.toSet()
            if (intVars.isNotEmpty()) {
                var intFixCount = 0
                for (varName in intVars) {
                    // Skip common false positives (keywords, type names)
                    if (varName in setOf("main", "return", "void", "if", "else", "for", "while",
                                         "float", "int", "vec2", "vec3", "vec4", "uint", "bool",
                                         "mat2", "mat3", "mat4", "sampler2D", "sampler3D",
                                         "uvec2", "uvec3", "uvec4", "ivec2", "ivec3", "ivec4",
                                         "bvec2", "bvec3", "bvec4")) continue

                    // Pattern 1: if (varName <op> floatLit) → if (float(varName) <op> floatLit)
                    // v0.5.33-alpha: ONLY match real comparison operators (==, !=, <=, >=, <, >),
                    // NOT a single `=` (which is assignment). The old `[<>=!]+` char class
                    // incorrectly matched `=`, corrupting const declarations and for-loop inits.
                    val comparisonRegex = Regex("""\b$varName\s*(==|!=|<=|>=|<(?!=)|>(?!=))\s*(\d+\.\d+)""")
                    // Pattern 2: varName = floatLit; → varName = int(floatLit);
                    val assignmentRegex = Regex("""\b$varName\s*=\s*(\d+\.\d+)\s*;""")
                    // Pattern 3: varName <op> whole_float → varName <op> int
                    // v0.5.33-alpha: For whole-number float literals (e.g., `1.0`), convert
                    // to int (e.g., `1`) instead of wrapping with float(). This preserves
                    // the int type, which is needed when the result is passed to a function
                    // that expects int (e.g., getAudioValueByBand(int index)).
                    val arithWholeRegex = Regex("""\b$varName\s*([+\-*/])\s*(\d+)\.0\b""")
                    // Pattern 3b: varName <op> non_whole_float → float(varName) <op> float
                    // For non-whole-number floats (e.g., `1.5`), we can't convert to int
                    // (would lose precision). Fall back to wrapping with float().
                    val arithRegex = Regex("""\b$varName\s*([+\-*/])\s*(\d+\.\d+[5-9]\d*)""")
                    // Reverse arith patterns (float <op> varName)
                    val reverseArithWholeRegex = Regex("""(\d+)\.0\b\s*([+\-*/])\s*\b$varName\b""")
                    val reverseArithRegex = Regex("""(\d+\.\d+[5-9]\d*)\s*([+\-*/])\s*\b$varName\b""")

                    modified = modified.replace(comparisonRegex) { mr ->
                        intFixCount++
                        "float($varName) ${mr.groupValues[1]} ${mr.groupValues[2]}"
                    }
                    modified = modified.replace(assignmentRegex) { mr ->
                        intFixCount++
                        "$varName = int(${mr.groupValues[1]});"
                    }
                    // Whole-number floats: convert to int (preserves int type)
                    modified = modified.replace(arithWholeRegex) { mr ->
                        intFixCount++
                        "$varName ${mr.groupValues[1]} ${mr.groupValues[2]}"
                    }
                    // Non-whole-number floats: wrap var with float()
                    modified = modified.replace(arithRegex) { mr ->
                        intFixCount++
                        "float($varName) ${mr.groupValues[1]} ${mr.groupValues[2]}"
                    }
                    modified = modified.replace(reverseArithWholeRegex) { mr ->
                        intFixCount++
                        "${mr.groupValues[1]} ${mr.groupValues[2]} $varName"
                    }
                    modified = modified.replace(reverseArithRegex) { mr ->
                        intFixCount++
                        "${mr.groupValues[1]} ${mr.groupValues[2]} float($varName)"
                    }
                }
                if (intFixCount > 0) {
                    fixesApplied.add("int-float type cast ($intFixCount fixes)")
                }
            }

            // =========================================================================
            // PASS K: v_TexCoord vec4→vec2 downgrade in .vert files (link fix).
            //
            // v0.5.33-alpha (solar system shader fix): Some .vert shaders declare
            // `varying vec4 v_TexCoord;` but only write to `.xy` (never `.z`/`.w`).
            // The corresponding .frag shader declares `varying vec2 v_TexCoord;`.
            // GLSL ES 3.00 requires EXACT type matching between vertex outputs and
            // fragment inputs. A vec4 vs vec2 mismatch causes a LINK error:
            //   "output v_TexCoord type mismatch with other stage"
            //
            // The color_grading.vert shader (part of the Live Solar System wallpaper,
            // workshop item 3637654840) has this exact issue:
            //   .vert: `varying vec4 v_TexCoord;` — only writes `v_TexCoord.xy = a_TexCoord;`
            //   .frag: `varying vec2 v_TexCoord;`
            //
            // Fix: In .vert files, if `varying vec4 v_TexCoord;` is declared but
            // `.z`, `.w`, `.zw`, etc. are NEVER referenced anywhere in the file
            // (outside the declaration itself), downgrade to `varying vec2 v_TexCoord;`.
            //
            // This is safe because:
            //   1. The .vert only writes to .xy — the .zw parts are uninitialized garbage
            //   2. The .frag declares vec2, so it only reads .xy
            //   3. Changing to vec2 makes the types match → link succeeds
            //   4. `v_TexCoord.xy = a_TexCoord;` is still valid for vec2 (writes to .xy
            //      of a vec2, which IS the full vector)
            //
            // We do NOT downgrade if .z/.w/.zw ARE used (e.g., godrays_combine.vert,
            // godrays_downsample2.vert, godrays_gaussian.vert legitimately use .zw
            // for texture resolution scaling).
            // =========================================================================
            if (name.endsWith(".vert")) {
                // Check if v_TexCoord is declared as vec4
                val vec4DeclPattern = Regex("""\bvarying\s+vec4\s+v_TexCoord\s*;""")
                if (vec4DeclPattern.containsMatchIn(modified)) {
                    // Check if .z, .w, .zw are ever used OUTSIDE the declaration
                    // Remove the declaration and search the body
                    val bodyWithoutDecl = modified.replace(vec4DeclPattern, "")
                    val zwUsagePattern = Regex("""v_TexCoord\s*\.\s*[zw]""")
                    if (!zwUsagePattern.containsMatchIn(bodyWithoutDecl)) {
                        // .z/.w/.zw never used → safe to downgrade to vec2
                        modified = modified.replace(
                            Regex("""\bvarying\s+vec4\s+v_TexCoord\s*;"""),
                            "varying vec2 v_TexCoord;"
                        )
                        fixesApplied.add("v_TexCoord vec4→vec2 (only .xy used in .vert, matches .frag vec2)")
                    }
                }
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

    /** Extract texture default names from a shader file's uniform declarations.
     *
     * v0.6.0 (parity §7-8): Use RegexOption.DOT_MATCHES_ALL so the `.*?` matches across
     * newlines. Previously the regex missed metadata comments on a different line from the
     * uniform declaration.
     */
    private fun extractTextureDefaultsFromShader(shaderContent: String): List<String?> {
        val defaults = arrayOfNulls<String>(8) // g_Texture0 through g_Texture7
        // Match: uniform sampler2D g_TextureN; // {"...","default":"texture_name",...}
        // v0.6.0: DOT_MATCHES_ALL allows .*? to span newlines
        val regex = Regex("""g_Texture(\d+)\s*;.*?"default"\s*:\s*"([^"]*)"""",
            RegexOption.DOT_MATCHES_ALL)
        for (match in regex.findAll(shaderContent)) {
            val slot = match.groupValues[1].toIntOrNull() ?: continue
            val defaultName = match.groupValues[2]
            if (slot < 8 && defaultName.isNotEmpty() && defaultName != "util/white") {
                // Record non-trivial defaults (skip "util/white" since it's the generic default)
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

            // v0.6.0 (parity §7-7): Iterate over ALL passes, not just passes[0]
            // Multi-pass shaders (e.g., a blur shader with horizontal + vertical passes)
            // may use different shaders per pass — return the FIRST shader found.
            for (passIdx in 0 until passes.size()) {
                // The pass in the effect file might have a "material" field
                val effectPass = passes[passIdx].asJsonObject
                val materialPath = if (effectPass.has("material")) {
                    effectPass.get("material").asString
                } else {
                    // Some effect files reference the material differently
                    // Try "file" as the material path
                    if (effectJson.has("file")) effectJson.get("file").asString else continue
                }

                // Read the material file to find the shader name
                val materialContent = entryMap[materialPath] ?: continue
                val materialJson = try {
                    JsonParser.parseString(materialContent).asJsonObject
                } catch (_: Exception) { continue }
                val matPasses = materialJson.getAsJsonArray("passes")
                if (matPasses == null || matPasses.size() == 0) continue

                // Iterate over ALL material passes too
                for (matPassIdx in 0 until matPasses.size()) {
                    val matPass = matPasses[matPassIdx].asJsonObject
                    if (matPass.has("shader")) {
                        return matPass.get("shader").asString
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore parsing errors
        }
        return null
    }

    /**
     * Remove duplicate `uniform <type> <name>;` declarations from a shader.
     *
     * After inlining headers like common_blur.h, the shader may have multiple
     * declarations of the same uniform (e.g., `uniform sampler2D g_Texture0;`
     * appears both in the original shader and in the inlined header). GLSL ES
     * 3.00 treats this as a redefinition error.
     *
     * This function parses each `uniform <type> <name>;` line (ignoring comments
     * after `//`), and keeps only the FIRST declaration of each `<name>`,
     * removing all subsequent duplicates.
     */
    private fun dedupUniformDeclarations(shaderSource: String): String {
        val lines = shaderSource.split("\n").toMutableList()
        val seenUniforms = mutableSetOf<String>()
        var dedupCount = 0

        for (i in lines.indices) {
            val line = lines[i].trim()
            // Match: uniform <type> <name>; [// comment]
            // Examples:
            //   uniform sampler2D g_Texture0; // {"hidden":true}
            //   uniform float g_Time;
            //   uniform vec4 g_Texture0Resolution;
            val uniformMatch = Regex("""^uniform\s+(\w+)\s+(\w+)\s*;""").find(line)
            if (uniformMatch != null) {
                val uniformName = uniformMatch.groupValues[2]
                if (uniformName in seenUniforms) {
                    // Duplicate — remove this line
                    lines[i] = ""
                    dedupCount++
                } else {
                    seenUniforms.add(uniformName)
                }
            }
        }

        if (dedupCount > 0) {
            WepWawetLogger.proxyDebug("MpkgPacker.dedupUniformDeclarations: removed $dedupCount duplicate uniform(s)")
        }

        return lines.joinToString("\n")
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
