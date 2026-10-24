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
