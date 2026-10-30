package com.wepwawet.pairing

/**
 * LZ4 block compression — the exact mode the PC's resourcecompiler uses.
 *
 * Verified byte-for-byte: compressing the RGBA/ETC2 bodies of a real
 * PC-produced .mpkg with python-lz4's default block mode
 * (LZ4_compress_default) reproduces the PC's stored bodies identically
 * (icon 110299 B, output-onlinepngtools 2520 B, MSK ETC2 1784263 B,
 * HD-thumb 267307 B — all exact). jpountz lz4-java's fast compressor
 * implements the same LZ4 block algorithm; this wrapper centralizes the
 * exact call so the mode can never drift.
 */
object Lz4Compat {
    private val compressor by lazy {
        net.jpountz.lz4.LZ4Factory.fastestInstance().fastCompressor()
    }

    private val decompressor by lazy {
        net.jpountz.lz4.LZ4Factory.fastestInstance().safeDecompressor()
    }

    /** Compress to a raw LZ4 block (no size header) — the PC's storage format. */
    fun compress(src: ByteArray): ByteArray = compressor.compress(src)

    /** Decompress a raw LZ4 block of known decompressed size. */
    fun decompress(src: ByteArray, decompSize: Int): ByteArray =
        decompressor.decompress(src, decompSize)
}
