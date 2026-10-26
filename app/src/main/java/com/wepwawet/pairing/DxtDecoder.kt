package com.wepwawet.pairing

import com.wepwawet.log.WepWawetLogger

/**
 * Native DXT (S3TC) / BCn block-compressed texture decoder.
 *
 * Decodes the following formats to RGBA8888 (4 bytes per pixel):
 *   - DXT1 (BC1) — 8 bytes per 4x4 block, RGB + optional 1-bit alpha
 *   - DXT3 (BC2) — 16 bytes per 4x4 block, RGB + 4-bit explicit alpha
 *   - DXT5 (BC3) — 16 bytes per 4x4 block, RGB + 8-bit interpolated alpha
 *   - BC4  (RGTC1) — 8 bytes per 4x4 block, single channel
 *   - BC5  (RGTC2) — 16 bytes per 4x4 block, two channels (R+G)
 *
 * Used by MpkgPacker to transcode PC-format .tex files (which contain DXT
 * block-compressed pixel data) to ETC2_RGBA8 + LZ4 for the WE Android renderer.
 *
 * Native library: libwepwawet_etc2.so (same .so as Etc2Encoder — built from
 * app/src/main/cpp/, with CMakeLists.txt including both etc2_jni.cpp and
 * dxt_jni.cpp + dxt_decoder.cpp).
 *
 * Usage:
 *   val rgba = DxtDecoder.decodeToRgba8888(dxtBytes, width, height, DxtFormat.DXT5)
 *   // rgba.size == width * height * 4
 */
object DxtDecoder {
    private const val TAG = "DxtDecoder"

    /**
     * DXT/BCn format ID.
     *
     * The `id` value matches the formatId expected by the native JNI call
     * (see dxt_jni.cpp). It does NOT match the .tex format field values —
     * use [formatForTexFormat] to convert from .tex format → DxtFormat.
     */
    enum class Format(val id: Int, val displayName: String, val blockSize: Int) {
        DXT1(1, "DXT1 (BC1)", 8),
        DXT3(3, "DXT3 (BC2)", 16),
        DXT5(5, "DXT5 (BC3)", 16),
        BC4(4, "BC4 (RGTC1)", 8),
        BC5(6, "BC5 (RGTC2)", 16);
    }

    /** Reverse map of .tex TexI format field → DxtFormat. */
    private val TEX_FORMAT_TO_DXT = mapOf(
        // Per repkg's TexFormat enum (from research-1b/repkg_texheader.cs):
        //   0=RGBA8888, 4=DXT5, 6=DXT3, 7=DXT1, 8=RG88, 9=R8
        4 to Format.DXT5,    // repkg: DXT5
        6 to Format.DXT3,    // repkg: DXT3
        7 to Format.DXT1,    // repkg: DXT1
        // We also accept the more modern BC enum codes (used by some new .pkg files):
        // BC4 (a.k.a. ATI1, RGTC1) — single channel
        11 to Format.BC4,
        // BC5 (a.k.a. ATI2, RGTC2) — two channels
        12 to Format.BC5,
    )

    /**
     * Map a .tex TexI format field value to a DxtFormat, or null if the
     * format is not a DXT/BC variant (e.g., 0=RGBA8888, 5=ETC2_RGBA8,
     * 13=FIF_PNG, 14=FIF_JPEG — these need a different code path).
     */
    fun formatForTexFormat(texFormat: Int): Format? = TEX_FORMAT_TO_DXT[texFormat]

    // Load the native library once on first use (shared with Etc2Encoder)
    private val isLoaded: Boolean by lazy {
        try {
            System.loadLibrary("wepwawet_etc2")
            WepWawetLogger.proxy("$TAG: native library loaded ✓")
            true
        } catch (e: UnsatisfiedLinkError) {
            WepWawetLogger.proxyError("$TAG: FAILED to load libwepwawet_etc2.so",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            false
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG: exception loading libwepwawet_etc2.so",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            false
        }
    }

    /**
     * Check if the DXT decoder is available (native library loaded successfully).
     * Same as [Etc2Encoder.isAvailable] since they share the same .so.
     */
    fun isAvailable(): Boolean = isLoaded

    /**
     * Decode DXT/BCn block-compressed data to RGBA8888.
     *
     * @param srcBytes The compressed block data (NO header, just block bytes).
     *                 For DXT1: width/4 * height/4 * 8 bytes.
     *                 For DXT3/DXT5: width/4 * height/4 * 16 bytes.
     * @param width Image width in pixels.
     * @param height Image height in pixels.
     * @param format The DXT/BCn format.
     * @return RGBA8888 pixel data (length = width * height * 4 bytes), or null on failure.
     *         Row-major, top-down (pixel 0 = top-left).
     */
    fun decodeToRgba8888(srcBytes: ByteArray, width: Int, height: Int, format: Format): ByteArray? {
        if (!isLoaded) {
            WepWawetLogger.proxyError("$TAG.decodeToRgba8888 FAILED — native library not loaded",
                "format" to format.displayName, "width" to width, "height" to height)
            return null
        }
        // Validate input dimensions
        if (width <= 0 || height <= 0) {
            WepWawetLogger.proxyError("$TAG.decodeToRgba8888 FAILED — bad dimensions",
                "width" to width, "height" to height)
            return null
        }
        val blocksX = (width + 3) / 4
        val blocksY = (height + 3) / 4
        val requiredSize = blocksX * blocksY * format.blockSize
        if (srcBytes.size < requiredSize) {
            WepWawetLogger.proxyError("$TAG.decodeToRgba8888 FAILED — input too small",
                "format" to format.displayName,
                "input_size" to srcBytes.size,
                "required_size" to requiredSize,
                "blocks_x" to blocksX, "blocks_y" to blocksY, "block_size" to format.blockSize)
            return null
        }

        return try {
            val out = nativeDecodeDxt(srcBytes, width, height, format.id)
            if (out == null) {
                WepWawetLogger.proxyError("$TAG.decodeToRgba8888 FAILED — native call returned null",
                    "format" to format.displayName, "width" to width, "height" to height)
            }
            out
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG.decodeToRgba8888 FAILED — exception",
                "format" to format.displayName,
                "width" to width, "height" to height,
                "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            null
        }
    }

    // Native function declaration
    @JvmStatic
    private external fun nativeDecodeDxt(
        srcBytes: ByteArray,
        width: Int,
        height: Int,
        formatId: Int,
    ): ByteArray?
}
