package com.wepwawet.pairing

import com.wepwawet.log.WepWawetLogger
import java.io.File
import java.io.RandomAccessFile

/**
 * v0.7.4 (parity G2): Video/image container sniffing by magic bytes.
 *
 * The official WE PC pipeline (wallpaperui.exe 2.8.0.42, decompiled with
 * Ghidra headless — FUN_14003d0e0 / FUN_1400e21d0) converts EVERY video
 * wallpaper that is not already MP4 before packing it into the .mpkg:
 *   - Media Foundation topology (FUN_140213e20) covers H264/H265/VP8/VP9/
 *     VP10/WMV3/YUY2 inputs,
 *   - the bundled ffmpeg fallback (FUN_1400e21d0) covers the identifiers
 *     `ffmpeg_avi_raw`, `ffmpeg_avi_yuv420`, `ffmpeg_avi_yuv422`,
 *     `ffmpeg_gif`, `ffmpeg_mp4_h264`, `ffmpeg_mp4_h265`, `ffmpeg_mp4_vp8`,
 *     `ffmpeg_mp4_vp9` (strings verified in the binary).
 *
 * WepWawet therefore has to (a) recognize every container a Workshop item
 * can ship, and (b) route each one to an on-device converter. This sniffer
 * implements (a): it classifies a file by its leading bytes, WITHOUT trusting
 * the file extension (WorkshopDownloader always names downloads *.pkg, so
 * extensions are meaningless — see detectVideoByMagicBytes history).
 *
 * Recognized signatures (verified against the format specifications):
 *   MP4 family    : bytes 4..7 == "ftyp" (covers .mp4/.m4v/.mov/.3gp/.3g2)
 *   WebM / MKV    : 0x1A 0x45 0xDF 0xA3 (EBML magic — same header for both;
 *                   MediaExtractor handles both)
 *   AVI           : "RIFF" at 0..3 + "AVI " at 8..11 (also "AVIX"/"AVI "
 *                   variants of OpenDML)
 *   WMV / ASF     : ASF header object GUID
 *                   75 B2 26 30 66 8C 11 CF A6 D9 00 DB 07 2E 5B 1
 *   GIF           : "GIF87a" / "GIF89a"
 *   FLV           : "FLV" (Flash video, 0x46 0x4C 0x56)
 *   MPEG-TS       : 0x47 at 0, 188 and 376 (188-byte packet sync)
 *   JPEG          : FF D8 FF (still image)
 *   PNG           : 89 50 4E 47 (still image)
 *   WebP          : "RIFF" + "WEBP" (still image)
 *   BMP           : "BM" (still image)
 *   PKGV (.pkg)   : "PKGV" at offset 4 (Wallpaper Engine scene package)
 *
 * Everything Android's media framework natively demuxes (MP4 family, WebM,
 * MKV, TS) is handled by [VideoTranscoder]; GIF needs the frame-accurate
 * [GifToMp4Transcoder]; AVI needs the RIFF demuxer in [AviToMp4Transcoder];
 * WMV/ASF is attempted through MediaExtractor (a number of devices ship
 * vendor ASF demuxers) and fails with a precise log otherwise.
 */
object VideoContainerSniffer {

    /** Container classification for a media file. */
    enum class Kind {
        /** MP4 family (.mp4/.m4v/.mov/.3gp) — directly playable by the phone. */
        MP4,
        /** EBML container (.webm/.mkv) — MediaExtractor + MediaCodec transcode. */
        WEBM_MKV,
        /** RIFF/AVI container — custom demux + decode. */
        AVI,
        /** ASF container (.wmv/.asf) — MediaExtractor attempt, device dependent. */
        WMV_ASF,
        /** Animated GIF — Movie-based frame decode + H264 encode. */
        GIF,
        /** Flash video — MediaExtractor attempt, device dependent. */
        FLV,
        /** MPEG transport stream — MediaExtractor. */
        TS,
        /** Still image: JPEG. */
        JPEG,
        /** Still image: PNG. */
        PNG,
        /** Still image: WebP. */
        WEBP,
        /** Still image: BMP. */
        BMP,
        /** Wallpaper Engine scene package. */
        PKGV,
        /** Unknown signature. */
        UNKNOWN,
    }

    /** ASF header object GUID (little-endian bytes as they appear on disk). */
    private val ASF_HEADER_GUID = byteArrayOf(
        0x75.toByte(), 0xB2.toByte(), 0x26.toByte(), 0x30.toByte(),
        0x66.toByte(), 0x8C.toByte(), 0x11.toByte(), 0xCF.toByte(),
        0xA6.toByte(), 0xD9.toByte(), 0x00.toByte(), 0xDB.toByte(),
        0x07.toByte(), 0x2E.toByte(), 0x5B.toByte(), 0x01.toByte(),
    )

    /**
     * Sniff the container kind of [file] by reading its first 384 bytes.
     * Never trusts the file extension — WorkshopDownloader names every
     * download `<id>_<sendId>_debug.pkg` regardless of the real content.
     */
    fun sniff(file: File): Kind {
        if (!file.exists() || file.length() < 12) return Kind.UNKNOWN
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(384)
                val n = raf.read(header)
                if (n < 12) return Kind.UNKNOWN
                sniffBytes(header, n, file.length())
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("VideoContainerSniffer: exception",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            Kind.UNKNOWN
        }
    }

    /**
     * Classify from an in-memory header buffer.
     *
     * @param header at least 12 valid bytes; extra bytes help TS sync detection
     * @param valid number of valid bytes in [header]
     * @param fileSize total file size (used to skip false positives on tiny files)
     */
    fun sniffBytes(header: ByteArray, valid: Int, fileSize: Long): Kind {
        fun ascii(off: Int, len: Int): String =
            String(header, off, len, Charsets.US_ASCII)

        // --- PKGV: Wallpaper Engine scene package ("PKGV" at offset 4) ---
        if (valid >= 8 && ascii(4, 4) == "PKGV") return Kind.PKGV

        // --- MP4 family: "ftyp" box at offset 4 (mp42/isom/iso2/avc1/qt/3gp…) ---
        if (valid >= 12 && ascii(4, 4) == "ftyp") return Kind.MP4

        // --- EBML: WebM / MKV share the same 4-byte signature ---
        if (valid >= 4 &&
            header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
            header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()
        ) return Kind.WEBM_MKV

        // --- RIFF: AVI (…or WebP — disambiguated by the form type at 8..11) ---
        if (valid >= 12 && ascii(0, 4) == "RIFF") {
            val form = ascii(8, 4)
            return when {
                form == "AVI " || form == "AVIX" || form == "AVI!" -> Kind.AVI
                form == "WEBP" -> Kind.WEBP
                form == "WAVE" -> Kind.UNKNOWN // audio-only, not a wallpaper
                else -> Kind.UNKNOWN
            }
        }

        // --- ASF (WMV/WMA): 16-byte header object GUID ---
        if (valid >= 16 && header.indices.take(16).all { header[it] == ASF_HEADER_GUID[it] }) {
            return Kind.WMV_ASF
        }

        // --- GIF ---
        if (valid >= 6) {
            val sig = ascii(0, 6)
            if (sig == "GIF87a" || sig == "GIF89a") return Kind.GIF
        }

        // --- FLV ---
        if (valid >= 6 && ascii(0, 3) == "FLV" &&
            header[3] == 0x01.toByte()  // version 1
        ) return Kind.FLV

        // --- JPEG / PNG / BMP still images ---
        if (valid >= 3 &&
            header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()
        ) return Kind.JPEG
        if (valid >= 8 &&
            header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
            header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()
        ) return Kind.PNG
        if (valid >= 2 && ascii(0, 2) == "BM") return Kind.BMP

        // --- MPEG-TS: 0x47 sync byte every 188 bytes (0, 188, 376) ---
        if (valid >= 376 + 1 && fileSize > 1880 &&
            header[0] == 0x47.toByte() &&
            header[188] == 0x47.toByte() &&
            header[376] == 0x47.toByte()
        ) return Kind.TS

        return Kind.UNKNOWN
    }

    /** True when the kind is one the phone's VideoDrawable can never play
     *  directly and that therefore MUST be re-encoded before packing. */
    fun needsTranscode(kind: Kind): Boolean = when (kind) {
        Kind.MP4, Kind.PKGV, Kind.JPEG, Kind.PNG, Kind.WEBP, Kind.BMP,
        Kind.UNKNOWN -> false
        Kind.WEBM_MKV, Kind.AVI, Kind.WMV_ASF, Kind.GIF, Kind.FLV, Kind.TS -> true
    }

    /** True for every kind that represents a moving-picture wallpaper input. */
    fun isVideoKind(kind: Kind): Boolean = when (kind) {
        Kind.MP4, Kind.WEBM_MKV, Kind.AVI, Kind.WMV_ASF,
        Kind.GIF, Kind.FLV, Kind.TS -> true
        else -> false
    }

    /** True for every kind that represents a still image. */
    fun isImageKind(kind: Kind): Boolean = when (kind) {
        Kind.JPEG, Kind.PNG, Kind.WEBP, Kind.BMP, Kind.GIF -> true
        else -> false
    }

    /** Human-readable container name for logs. */
    fun label(kind: Kind): String = when (kind) {
        Kind.MP4 -> "mp4"
        Kind.WEBM_MKV -> "webm/mkv"
        Kind.AVI -> "avi"
        Kind.WMV_ASF -> "wmv/asf"
        Kind.GIF -> "gif"
        Kind.FLV -> "flv"
        Kind.TS -> "mpeg-ts"
        Kind.JPEG -> "jpeg"
        Kind.PNG -> "png"
        Kind.WEBP -> "webp"
        Kind.BMP -> "bmp"
        Kind.PKGV -> "pkgv"
        Kind.UNKNOWN -> "unknown"
    }
}
