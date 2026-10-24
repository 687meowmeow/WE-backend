#!/usr/bin/env python3
"""Replace the broken convertTexPngToRgba function in MpkgPacker.kt."""
from pathlib import Path

PACKER_PATH = Path('/home/z/my-project/app/src/main/java/com/wepwawet/pairing/MpkgPacker.kt')

# Read the existing file
content = PACKER_PATH.read_text()

# Define the new function (replacement for lines 744-945)
NEW_FUNCTION = '''    /**
     * Convert a .tex file containing PNG data inside the TEXB section to raw RGBA8888.
     *
     * === ACTUAL .tex FORMAT (verified against RePKG + linux-wallpaperengine source) ===
     *
     * Header:
     *   "TEXV0005\\0" (9 bytes magic1, null-terminated string)
     *   "TEXI0001\\0" (9 bytes magic2, null-terminated string)
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
     *   "TEXB0004\\0" (9 bytes magic, null-terminated string)
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
     *   - TEXV0005\\0 + TEXI0001\\0 (preserved from original)
     *   - TEXI header: format=0 (RGBA8888), flags from original,
     *     dimensions from largest PNG (mipmap level 0)
     *   - TEXB0004\\0
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
            // Find all PNG signatures (\\x89PNG) and extract each PNG (up to IEND+CRC).
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
            //   0-8   "TEXV0005\\0"
            //   9-17  "TEXI0001\\0"
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
            // "TEXB0004\\0" magic
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
                "verify_first_8_bytes_hex" to result.copyOfRange(91, 99).joinToString("") { "%02x".format(it) })
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
'''

# Find the start and end of the function to replace
# Start: the comment block "    /**" on a line that contains "Convert a .tex file containing PNG data"
# End: the closing "    }" of the function

lines = content.split('\n')
start_idx = None
end_idx = None

for i, line in enumerate(lines):
    # Find the comment block start for convertTexPngToRgba
    if start_idx is None and 'Convert a .tex file containing PNG data inside the TEXB section to raw RGBA8888.' in line:
        # Walk back to find the /** that starts this comment block
        for j in range(i, max(0, i - 5), -1):
            if lines[j].strip().startswith('/**'):
                start_idx = j
                break
        if start_idx is None:
            start_idx = i  # fallback

# Find the end: the function ends with the catch block returning null
if start_idx is not None:
    for i in range(start_idx, len(lines)):
        # The function ends with the catch block returning null
        # Look for the line "            return null" inside a catch block
        # immediately followed by "        }" (catch close) and "    }" (function close)
        if (i + 2 < len(lines) and
            'return null' in lines[i] and
            lines[i + 1].strip() == '}' and
            lines[i + 2].strip() == '}'):
            end_idx = i + 2
            break

if start_idx is None or end_idx is None:
    print(f"ERROR: Could not find function to replace. start_idx={start_idx}, end_idx={end_idx}")
    exit(1)

print(f"Replacing lines {start_idx + 1} to {end_idx + 1} (0-indexed: {start_idx} to {end_idx})")
print(f"First line: {lines[start_idx]!r}")
print(f"Last line: {lines[end_idx]!r}")

# Replace
new_lines = lines[:start_idx] + NEW_FUNCTION.split('\n') + lines[end_idx + 1:]
new_content = '\n'.join(new_lines)

# Backup the original
backup_path = PACKER_PATH.with_suffix('.kt.bak')
backup_path.write_text(content)
print(f"Backup written to {backup_path}")

# Write the new content
PACKER_PATH.write_text(new_content)
print(f"Wrote {len(new_content)} bytes to {PACKER_PATH}")
