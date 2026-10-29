package com.wepwawet.pairing

/**
 * v0.7.4 (parity G3): WE PC export-quality video presets for wallpapers
 * sent to a phone. ─── SCAFFOLD ONLY — INTENTIONALLY NOT WIRED UP YET ───
 *
 * ⚠️ NOTE FROM THE AUTHOR (v0.7.4): these presets are "set up but not
 * linked to work yet" ON PURPOSE. The user wants to implement the actual
 * re-encode pass in a later release. Do NOT call [resolve] from the
 * production transcode path until that work happens — video wallpapers
 * must keep being packed at ORIGINAL quality (the PC's behavior when no
 * preset has been selected / "auto" with passthrough), which is exactly
 * what [VideoTranscoder.transcodeToMp4H264] + [MpkgPacker.packVideoWallpaper]
 * do today.
 *
 * The intended (future) wiring point, for whoever implements it later:
 *
 *     // in MpkgPacker.packVideoWallpaper / packFromPkg step 3d, right
 *     // before packing the video into the .mpkg:
 *     val preset = VideoQualityPresets.fromSettings(...)   // 1080p | 4k | auto
 *     if (preset != null) {
 *         val target = VideoQualityPresets.resolve(
 *             preset, deviceWants60Fps, srcWidth, srcHeight)
 *         // re-encode the MP4 to target.width x target.height,
 *         // target.bitrateBps and target.fpsCap via VideoTranscoder
 *         // (extend it with optional scale/bitrate/fps parameters)
 *     }
 *
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Everything below is byte-for-byte faithful to the PC implementation in
 * wallpaperui.exe 2.8.0.42 (FUN_14003d0e0, the mobile transcoder; every
 * constant cited is from the Ghidra headless decompilation of the binary
 * the user supplied):
 *
 *   uStack_2028 = 0x78000000438;      // 1080p: width  = 0x780 = 1920,
 *                                      //        height = 0x438 = 1080
 *   uStack_2014 = 8000000;             // default bitrate = 8 Mbit/s
 *   uStack_2018 = 0x1e;                // fps 30
 *   if (deviceFps == 0x3c) uStack_2018 = 0x3c;   // fps 60 when device asks
 *   if (preset == "1080p") {                       // FUN_140042480(..., "1080p")
 *       bitrate = 0x7a1200;                       // 8,000,000 @ 30 fps
 *       if (deviceFps == 0x3c) bitrate = 0xf42400; // 16,000,000 @ 60 fps
 *   } else if (preset == "4k") {                    // DAT_140ab5814 = "4k"
 *       dims   = 0xf0000000870;                    // 3840 x 2160
 *       bitrate = 16000000;                        // 16 Mbit/s @ 30 fps
 *       if (deviceFps == 0x3c) bitrate = 20000000; // 20 Mbit/s @ 60 fps
 *   } else {  // "auto" — source resolution, bitrate by source size + fps
 *       dims = sourceWidth x sourceHeight (long edge first)
 *       if (srcW * srcH >= 0x384000) {             // ≥ 3,686,400 px (~1080p)
 *           bitrate =  16,000,000 @ 30 fps
 *           bitrate =  20,000,000 @ 60 fps
 *       } else {
 *           bitrate =   8,000,000 @ 30 fps
 *           bitrate =  16,000,000 @ 60 fps
 *       }
 *   }
 *
 * The codec written for every re-encode is `mp4_h264` (FUN_14002a1b0 call
 * with uStack_200c = 1, uStack_2010 = 0x1e), i.e. H.264 in an MP4
 * container — which is exactly what [BitmapToMp4Encoder] and
 * [VideoTranscoder] produce on-device.
 */
object VideoQualityPresets {

    /**
     * The PC's selectable export presets (the strings the binary compares
     * against live at DAT_140ab5810 "1080p" and DAT_140ab5814 "4k"; any
     * other value falls into the auto/source-resolution branch).
     */
    enum class Preset(val id: String) {
        /** 1920×1080 target, 8 Mbit/s (16 Mbit/s at 60 fps). */
        P1080("1080p"),

        /** 3840×2160 target, 16 Mbit/s (20 Mbit/s at 60 fps). */
        P4K("4k"),

        /** Source resolution; bitrate picked from source size + frame rate. */
        AUTO("auto"),
    }

    /**
     * A fully resolved transcode target — the exact parameters the PC would
     * pass to its `mp4_h264` encoder for this (preset, device, source)
     * combination.
     */
    data class ResolvedTarget(
        /** Target width in pixels (long edge). 0 = keep source width. */
        val width: Int,
        /** Target height in pixels (short edge). 0 = keep source height. */
        val height: Int,
        /** Encode bitrate in bits per second. */
        val bitrateBps: Long,
        /** Frame-rate cap: 30, or 60 when the device requested 60 fps. */
        val fpsCap: Int,
    )

    /** 1080p preset — 1920×1080, 8 Mbit/s (16 Mbit/s at 60 fps). */
    const val P1080_WIDTH = 1920      // 0x780
    const val P1080_HEIGHT = 1080     // 0x438
    const val P1080_BITRATE_30 = 8_000_000L    // 0x7a1200
    const val P1080_BITRATE_60 = 16_000_000L   // 0xf42400

    /** 4K preset — 3840×2160, 16 Mbit/s (20 Mbit/s at 60 fps). */
    const val P4K_WIDTH = 3840        // 0xf00
    const val P4K_HEIGHT = 2160        // 0x870
    const val P4K_BITRATE_30 = 16_000_000L
    const val P4K_BITRATE_60 = 20_000_000L

    /** Auto preset — source resolution, bitrate by source pixel count. */
    const val AUTO_BITRATE_SMALL_30 = 8_000_000L    //  < 0x384000 px
    const val AUTO_BITRATE_SMALL_60 = 16_000_000L
    const val AUTO_BITRATE_LARGE_30 = 16_000_000L   // ≥ 0x384000 px
    const val AUTO_BITRATE_LARGE_60 = 20_000_000L
    const val AUTO_LARGE_SOURCE_PIXELS = 3_686_400L // 0x384000 (≈ 1080p class)

    /** Default frame-rate cap when the device did not request 60 fps. */
    const val DEFAULT_FPS_CAP = 30     // 0x1e
    const val HIGH_FPS_CAP = 60        // 0x3c

    /**
     * Resolve the encode target exactly like FUN_14003d0e0 does.
     *
     * @param preset the user-selected export preset
     * @param deviceWants60Fps the device's requested frame rate equals 60
     *        (on the PC this is the phone-export dialog's fps setting; the
     *        transcoder reads it from its config struct and only
     *        distinguishes the 0x3c case)
     * @param srcWidth source video width (used by the auto preset)
     * @param srcHeight source video height
     */
    fun resolve(
        preset: Preset,
        deviceWants60Fps: Boolean,
        srcWidth: Int,
        srcHeight: Int,
    ): ResolvedTarget {
        val fpsCap = if (deviceWants60Fps) HIGH_FPS_CAP else DEFAULT_FPS_CAP
        return when (preset) {
            Preset.P1080 -> ResolvedTarget(
                width = P1080_WIDTH,
                height = P1080_HEIGHT,
                bitrateBps = if (deviceWants60Fps) P1080_BITRATE_60 else P1080_BITRATE_30,
                fpsCap = fpsCap,
            )
            Preset.P4K -> ResolvedTarget(
                width = P4K_WIDTH,
                height = P4K_HEIGHT,
                bitrateBps = if (deviceWants60Fps) P4K_BITRATE_60 else P4K_BITRATE_30,
                fpsCap = fpsCap,
            )
            Preset.AUTO -> {
                // Source resolution (long edge first, exactly like the PC's
                // min/max swap before comparing w*h).
                val longEdge = maxOf(srcWidth, srcHeight)
                val shortEdge = minOf(srcWidth, srcHeight)
                val largeSource = srcWidth.toLong() * srcHeight.toLong() >= AUTO_LARGE_SOURCE_PIXELS
                val bitrate = when {
                    largeSource && deviceWants60Fps -> AUTO_BITRATE_LARGE_60
                    largeSource -> AUTO_BITRATE_LARGE_30
                    deviceWants60Fps -> AUTO_BITRATE_SMALL_60
                    else -> AUTO_BITRATE_SMALL_30
                }
                ResolvedTarget(
                    width = longEdge,
                    height = shortEdge,
                    bitrateBps = bitrate,
                    fpsCap = fpsCap,
                )
            }
        }
    }

    /**
     * Parse a preset identifier string ("1080p" / "4k" / "auto") the way the
     * PC compares them (case-insensitive convenience; the binary compares
     * the literal strings). Returns null for unknown values — callers treat
     * null as "no preset selected" = original-quality passthrough.
     */
    fun fromId(id: String?): Preset? = when (id?.lowercase()) {
        "1080p" -> Preset.P1080
        "4k" -> Preset.P4K
        "auto" -> Preset.AUTO
        else -> null
    }
}
