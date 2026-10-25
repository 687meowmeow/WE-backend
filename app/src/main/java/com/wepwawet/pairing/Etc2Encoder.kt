package com.wepwawet.pairing

import com.wepwawet.log.WepWawetLogger

/**
 * Native ETC2 RGBA8 encoder using Google's etc2comp library.
 *
 * Encodes raw RGBA8888 pixel data as ETC2 RGBA8 block-compressed data,
 * matching the format used by Wallpaper Engine Android (.tex format=5).
 *
 * Used by MpkgPacker to convert PNG-encoded .tex files from Steam depot
 * (which work on PC but not Android) into ETC2 RGBA8 + LZ4 format that
 * works on Android.
 *
 * Native library: libwepwawet_etc2.so (built from app/src/main/cpp/)
 *
 * Usage:
 *   val etc2Bytes = Etc2Encoder.encodeRgba8888(rgbaBytes, width, height)
 *   // Returns: byte[] of ETC2 RGBA8 block data (16 bytes per 4x4 block)
 *   // For width×height image:
 *   //   blocks_x = (width + 3) / 4
 *   //   blocks_y = (height + 3) / 4
 *   //   output_size = blocks_x * blocks_y * 16
 */
object Etc2Encoder {
    private const val TAG = "Etc2Encoder"

    // Load the native library once on first use
    private val isLoaded: Boolean by lazy {
        try {
            System.loadLibrary("wepwawet_etc2")
            WepWawetLogger.proxy("Etc2Encoder: native library loaded ✓")
            true
        } catch (e: UnsatisfiedLinkError) {
            WepWawetLogger.proxyError("Etc2Encoder: FAILED to load libwepwawet_etc2.so",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            false
        } catch (e: Exception) {
            WepWawetLogger.proxyError("Etc2Encoder: exception loading libwepwawet_etc2.so",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            false
        }
    }

    /**
     * Encode RGBA8888 pixel data as ETC2 RGBA8 block-compressed data.
     *
     * @param rgbaBytes Input pixel data, RGBA8888 (R,G,B,A per pixel, row-major top-down)
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @return ETC2 RGBA8 block data (16 bytes per 4x4 block), no header
     * @throws IllegalStateException if native library failed to load
     * @throws RuntimeException if encoding failed
     */
    fun encodeRgba8888(rgbaBytes: ByteArray, width: Int, height: Int): ByteArray {
        if (!isLoaded) {
            throw IllegalStateException("Native ETC2 encoder library not loaded")
        }
        val expectedSize = width * height * 4
        if (rgbaBytes.size < expectedSize) {
            throw IllegalArgumentException(
                "RGBA bytes too small: ${rgbaBytes.size} < $expectedSize (width=$width, height=$height)")
        }
        val result = nativeEncodeEtc2Rgba8(rgbaBytes, width, height)
        if (result == null) {
            throw RuntimeException("ETC2 encoding returned null (see logs for details)")
        }
        return result
    }

    /**
     * Check if the ETC2 encoder is available (native library loaded successfully).
     */
    fun isAvailable(): Boolean = isLoaded

    // Native function declaration
    @JvmStatic
    private external fun nativeEncodeEtc2Rgba8(
        rgbaBytes: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray?
}
