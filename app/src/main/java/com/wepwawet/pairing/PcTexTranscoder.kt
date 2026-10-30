package com.wepwawet.pairing

import java.io.ByteArrayOutputStream

/**
 * PC-exact .tex output builder — replicates what Wallpaper Engine PC's
 * resourcecompiler64.exe produces when the transcoder
 * (wallpaperui.exe `convertProjectToMobile`, 0x14003d1e0) runs
 *
 *   resourcecompiler64.exe -transcode -i "<in>" -o "<out>" -f ETC2 \
 *       [-c force] -shrink <n> -maxmipmaps 1
 *
 * on every `materials` .tex of an unpacked project.
 *
 * Output format (verified byte-for-byte against a real PC-produced .mpkg):
 *
 *   TEXV0005\0                     (9 bytes)
 *   TEXI0001\0                     (9 bytes)
 *   u32 texiFormat                 0 = RGBA8888, 5 = ETC2_RGBA8
 *   u32 flags                      preserved; |= 0x08 when stored UNCOMPRESSED
 *   u32 texW/texH/imgW/imgH        ALL FOUR set to the image's own dims
 *                                  (the PC un-pads — a 2048×512 tex holding a
 *                                  1500×489 image comes out 1500×489)
 *   u32 color                      preserved from the source TEXI
 *   TEXB0004\0                     (9 bytes — the PC normalizes TEXB0003 → 0004)
 *   u32 imageCount = 1
 *   u32 texbFormat = -1            (0xFFFFFFFF — always, even when the source
 *                                   said 13/PNG)
 *   u32 isVideoMp4 = 0
 *   u32 mipmapCount = 1            (-maxmipmaps 1 — extra mips dropped)
 *   u32 mipW, mipH
 *   u32 isLz4
 *   u32 decompSize                 body size when uncompressed (w*h*4 or w*h)
 *   u32 byteCount                  actual body bytes
 *   body
 *
 * Decision rules (from the WORKING .mpkg ground truth; the exact thresholds
 * live inside resourcecompiler64.exe which is not yet available for
 * disassembly — both rules match all six converted textures in the ground
 * truth, and every output format chosen here loads natively on WE Android):
 *
 *   • decode source (PNG/JPEG body → RGBA at the image's intrinsic size;
 *     LZ4 body → decompressed RGBA; raw body → as-is) at the MIP 0 dims
 *   • if max(w, h) > [ETC2_MAX_DIM]      → ETC2_RGBA8 + LZ4, format=5
 *     (ground truth: 3000×2000 → ETC2; 1500×489, 296×294, 400×400 → RGBA)
 *   • else keep RGBA8888:
 *       - LZ4-compress (plain LZ4 block, default/fast mode — byte-identical
 *         to the PC's output, verified on 4 textures)
 *       - if the compressed size saves at least 25% → store LZ4, isLz4=1,
 *         flags unchanged  (ground truth ratios: 0.038/0.004/0.77 → LZ4)
 *       - else store raw, isLz4=0, flags |= 0x08
 *         (ground truth: waterflowphase ratio 0.832 and waterripplenormal
 *          ratio 1.004 → stored raw with the 0x08 flag)
 */
object PcTexTranscoder {

    /** PC ETC2 switch-over: textures whose larger dimension exceeds this become ETC2. */
    const val ETC2_MAX_DIM = 2048

    /** Compress only when lz4Len * 5 < rawLen * 4 (≥ 20% saving). */
    const val LZ4_BENEFIT_NUM = 5
    const val LZ4_BENEFIT_DEN = 4

    /** TEXI flag the PC ORs in when a body is stored uncompressed. */
    const val FLAG_UNCOMPRESSED = 0x08

    /** Fully parsed .tex header (TEXB0003 and TEXB0004 layouts). */
    class TexInfo(
        val texiFormat: Int,
        val flags: Int,
        val color: Int,
        val texW: Int, val texH: Int,
        val imgW: Int, val imgH: Int,
        val texbVersion: Int,          // 3 or 4
        val mipCount: Int,             // 1 for TEXB0003 (implicit)
        val mipW: Int, val mipH: Int,
        val bodyIsLz4: Boolean,
        val decompSize: Int,
        val byteCount: Int,            // the stored mip-0 block size (may be 0/bogus)
        val bodyStart: Int,            // 87 (TEXB0003) or 91 (TEXB0004)
        val bodyLen: Int,              // bytes from bodyStart to EOF (may include trailing mips)
    )

    /** Parse a .tex header; returns null when the file is not a recognizable TEXV. */
    fun parse(tex: ByteArray): TexInfo? {
        if (tex.size < 92) return null
        if (!String(tex, 0, 8, Charsets.US_ASCII).startsWith("TEXV")) return null
        if (!String(tex, 9, 8, Charsets.US_ASCII).startsWith("TEXI")) return null
        val texb = String(tex, 46, 8, Charsets.US_ASCII)
        val texiFormat = readU32(tex, 18)
        val flags = readU32(tex, 22)
        val texW = readU32(tex, 26)
        val texH = readU32(tex, 30)
        val imgW = readU32(tex, 34)
        val imgH = readU32(tex, 38)
        val color = readU32(tex, 42)
        return when {
            texb.startsWith("TEXB0003") -> TexInfo(
                texiFormat, flags, color, texW, texH, imgW, imgH,
                texbVersion = 3, mipCount = 1,
                mipW = readU32(tex, 67), mipH = readU32(tex, 71),
                bodyIsLz4 = readU32(tex, 75) != 0,
                decompSize = readU32(tex, 79),
                byteCount = readU32(tex, 83),
                bodyStart = 87, bodyLen = tex.size - 87,
            )
            texb.startsWith("TEXB0004") -> TexInfo(
                texiFormat, flags, color, texW, texH, imgW, imgH,
                texbVersion = 4, mipCount = readU32(tex, 67),
                mipW = readU32(tex, 71), mipH = readU32(tex, 75),
                bodyIsLz4 = readU32(tex, 79) != 0,
                decompSize = readU32(tex, 83),
                byteCount = readU32(tex, 87),
                bodyStart = 91, bodyLen = tex.size - 91,
            )
            else -> null
        }
    }

    /**
     * The mip-0 body of [tex]: sliced to the stored byteCount when that is
     * plausible (animated/multi-mip .tex files append further LZ4 blocks
     * after mip 0 — decompressing the whole remainder corrupts or fails),
     * otherwise everything from bodyStart to EOF.
     */
    fun bodyBytes(tex: ByteArray): ByteArray {
        val info = parse(tex) ?: return ByteArray(0)
        val rest = tex.copyOfRange(info.bodyStart, tex.size)
        return if (info.byteCount in 1..rest.size) rest.copyOf(info.byteCount) else rest
    }

    /** The PC's ETC2-vs-RGBA decision for a decoded image. */
    fun shouldUseEtc2(w: Int, h: Int): Boolean = maxOf(w, h) > ETC2_MAX_DIM

    /** The PC's LZ4-vs-raw decision for an RGBA body. */
    fun lz4IsBeneficial(rawLen: Int, compressedLen: Int): Boolean =
        compressedLen.toLong() * LZ4_BENEFIT_NUM < rawLen.toLong() * LZ4_BENEFIT_DEN

    /**
     * Build the PC-format output .tex. [format] 0 (RGBA) or 5 (ETC2_RGBA8);
     * [decompSize] the uncompressed body size; [isLz4Body] whether [body]
     * is LZ4-compressed.
     */
    fun build(
        format: Int, flags: Int, color: Int, w: Int, h: Int,
        body: ByteArray, decompSize: Int, isLz4Body: Boolean,
    ): ByteArray {
        val out = ByteArrayOutputStream(91 + body.size)
        out.write("TEXV0005\u0000".toByteArray(Charsets.US_ASCII))
        out.write("TEXI0001\u0000".toByteArray(Charsets.US_ASCII))
        writeLeInt(out, format)
        writeLeInt(out, flags)
        writeLeInt(out, w)
        writeLeInt(out, h)
        writeLeInt(out, w)
        writeLeInt(out, h)
        writeLeInt(out, color)
        out.write("TEXB0004\u0000".toByteArray(Charsets.US_ASCII))
        writeLeInt(out, 1)          // imageCount
        writeLeInt(out, -1)         // texbFormat — always -1 in PC output
        writeLeInt(out, 0)          // isVideoMp4
        writeLeInt(out, 1)          // mipmapCount — -maxmipmaps 1
        writeLeInt(out, w)
        writeLeInt(out, h)
        writeLeInt(out, if (isLz4Body) 1 else 0)
        writeLeInt(out, decompSize)
        writeLeInt(out, body.size)
        out.write(body)
        return out.toByteArray()
    }

    /**
     * The full RGBA-body pipeline (the pixel data is already RGBA8888 —
     * either a raw/LZ4 body or a decoded image). Returns the PC-format
     * .tex or null when the body cannot be used.
     *
     * [etc2Encoder] is injected so unit tests (pure JVM) pass null and get
     * the RGBA path even for huge textures; the app passes the real encoder.
     */
    fun transcodeRgbaBody(
        flags: Int, color: Int, w: Int, h: Int,
        rgba: ByteArray,
        etc2Encoder: ((ByteArray, Int, Int) -> ByteArray?)?,
    ): ByteArray? {
        if (w <= 0 || h <= 0) return null
        if (rgba.size < w * h * 4) return null

        if (etc2Encoder != null && shouldUseEtc2(w, h)) {
            val etc2 = etc2Encoder(rgba, w, h)
            if (etc2 != null) {
                val lz4 = Lz4Compat.compress(etc2)
                return build(5, flags, color, w, h, lz4, etc2.size, true)
            }
            // Encoder unavailable/failed — fall through to the RGBA path.
            // A >2048px RGBA body is large but VALID; passing the original
            // through (the caller's null fallback) could ship a PNG body
            // the phone cannot load at all. RGBA always loads.
        }

        val lz4 = Lz4Compat.compress(rgba)
        return if (lz4IsBeneficial(rgba.size, lz4.size)) {
            build(0, flags, color, w, h, lz4, rgba.size, true)
        } else {
            build(0, flags or FLAG_UNCOMPRESSED, color, w, h, rgba, rgba.size, false)
        }
    }

    private fun readU32(d: ByteArray, off: Int): Int =
        (d[off].toInt() and 0xFF) or
            ((d[off + 1].toInt() and 0xFF) shl 8) or
            ((d[off + 2].toInt() and 0xFF) shl 16) or
            ((d[off + 3].toInt() and 0xFF) shl 24)

    private fun writeLeInt(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }
}
