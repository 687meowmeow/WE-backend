package com.wepwawet.pairing

import com.wepwawet.data.model.WorkshopItemTyped
import com.wepwawet.log.WepWawetLogger
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
 *   [8-byte ASCII magic]    ("PKGM0014")
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

    const val MAGIC = "PKGM0014"
    const val HEADER_MARKER = 8
    /** PNG magic bytes — used to detect PNG data inside .tex files. */
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47) // \x89PNG

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
        val entries = PkgReader.read(pkgFile).toMutableList()
        if (entries.isEmpty()) {
            WepWawetLogger.proxyError("MpkgPacker.packFromPkg FAILED — no entries in .pkg",
                "pkg_file" to pkgFile.name)
            return 0L
        }

        // Step 2: Detect wallpaper type from project.json
        val type = PkgReader.detectWallpaperType(entries)
        WepWawetLogger.proxy("MpkgPacker.packFromPkg type detected",
            "type" to type,
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
        if (installDir != null && installDir.exists()) {
            val standaloneNames = listOf("project.json", "preview.jpg")
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
        var totalOriginalTexBytes = 0L
        var totalConvertedTexBytes = 0L
        for (entry in entries) {
            val (name, blob) = entry
            if (name.endsWith(".tex") && blob.contains(PNG_MAGIC[0]) && findBytes(blob, PNG_MAGIC) >= 0) {
                WepWawetLogger.proxy("MpkgPacker.packFromPkg: .tex contains PNG, will convert",
                    "name" to name,
                    "original_size" to blob.size)
                val converted = convertTexPngToRgba(blob, name)
                if (converted != null) {
                    processedEntries.add(Pair(name, converted))
                    texConverted++
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
                "total_original_tex_bytes" to totalOriginalTexBytes,
                "total_converted_tex_bytes" to totalConvertedTexBytes,
                "size_increase_bytes" to (totalConvertedTexBytes - totalOriginalTexBytes),
                "size_increase_mb" to "%.1f".format((totalConvertedTexBytes - totalOriginalTexBytes) / 1024.0 / 1024.0))
        }

        // Step 4: Pack the entries into a .mpkg
        // We preserve the original .pkg entries as-is (they're already in the
        // correct WE format). The only difference between .pkg and .mpkg is
        // the magic header and the VFS table format.
        val totalSize = pack(entries, outputFile)

        WepWawetLogger.proxy("MpkgPacker.packFromPkg COMPLETE",
            "output" to outputFile.name,
            "mpkg_size" to totalSize,
            "entry_count" to entries.size,
            "type" to type)

        verifyMpkg(outputFile, entries)
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
        val videoBytes = videoFile.readBytes()
        WepWawetLogger.proxy("MpkgPacker.packVideoWallpaper video loaded",
            "video_bytes" to videoBytes.size,
            "video_file_path" to videoFile.absolutePath)

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
        val entries = listOf(
            "project.json" to projectJson,
            "wallpaper.mp4" to videoBytes,
            "preview.jpg" to previewBytes,
        )

        WepWawetLogger.proxy("MpkgPacker.packVideoWallpaper packing entries",
            "entry_count" to entries.size,
            "total_bytes" to entries.sumOf { it.second.size })
        val totalSize = pack(entries, outputFile)
        WepWawetLogger.proxy("MpkgPacker.packVideoWallpaper COMPLETE",
            "output" to outputFile.name,
            "mpkg_size" to totalSize,
            "video_size" to videoBytes.size)
        verifyMpkg(outputFile, entries)
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
            // We use only the largest mipmap to keep the file size manageable.
            // Using all 4 mipmaps would multiply file size by ~1.33x.
            data class DecodedRgba(
                val pngIndex: Int,
                val width: Int,
                val height: Int,
                val rgba: ByteArray,
            )
            val decoded = mutableListOf<DecodedRgba>()
            for ((idx, pngEntry) in pngImages.withIndex()) {
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(
                    pngEntry.bytes, 0, pngEntry.bytes.size)
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
                    "first_8_bytes_hex" to rgba.take(8).joinToString("") { "%02x".format(it) })
            }

            if (decoded.isEmpty()) {
                WepWawetLogger.proxyWarn("MpkgPacker.convertTexPngToRgba: no PNGs decoded successfully",
                    "name" to name)
                return null
            }

            // Pick the largest decoded PNG (mipmap level 0)
            val largest = decoded.maxByOrNull { it.width.toLong() * it.height.toLong() }
                ?: decoded[0]
            WepWawetLogger.proxy("MpkgPacker.convertTexPngToRgba: selected mipmap",
                "name" to name,
                "selected_index" to largest.pngIndex,
                "width" to largest.width,
                "height" to largest.height,
                "rgba_bytes" to largest.rgba.size,
                "available_mipmaps" to decoded.size)

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
