package com.wepwawet.pairing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import com.wepwawet.log.WepWawetLogger
import java.io.File

/**
 * v0.7.4 (parity G2): Animated GIF → MP4 (H.264) transcoder.
 *
 * The WE PC pipeline converts animated-GIF "video" wallpapers through its
 * bundled ffmpeg with the `ffmpeg_gif` codec identifier (FUN_1400e21d0,
 * string verified in wallpaperui.exe 2.8.0.42) before packing them into the
 * .mpkg — the phone's VideoDrawable only receives `mp4_h264` content and
 * cannot play a bare .gif. WepWawet previously shipped GIF entries through
 * untouched, producing a guaranteed-black wallpaper.
 *
 * Android's MediaExtractor cannot demux GIF, so this transcoder:
 *   1. parses the per-frame delays out of the GIF89a Graphic Control
 *      Extensions (21 F9 04 …) to get exact frame timestamps,
 *   2. decodes each composited frame with android.graphics.Movie
 *      (the same API MpkgPacker already uses for animated .tex textures),
 *   3. feeds the ARGB frames into [BitmapToMp4Encoder] (H.264).
 *
 * The resulting MP4 preserves the GIF's frame timing (not a fixed rate),
 * which matches how the PC's ffmpeg conversion maps GIF timestamps.
 */
object GifToMp4Transcoder {

    private const val TAG = "GifToMp4Transcoder"

    /** Minimum frames we will ever emit (single-frame GIF → 1-frame video). */
    private const val MIN_FRAMES = 1

    /** Hard ceiling protecting against pathological GIFs (10 min @ 50 fps). */
    private const val MAX_FRAMES = 30_000

    /**
     * Transcode an animated GIF to an MP4/H.264 file.
     *
     * @return true on success ([outputFile] contains a playable MP4);
     *         false on failure (caller keeps the original entry and logs).
     */
    fun transcode(gifFile: File, outputFile: File): Boolean {
        WepWawetLogger.proxy("$TAG: starting GIF → MP4 transcode",
            "input" to gifFile.name,
            "input_size" to gifFile.length(),
            "output" to outputFile.name)
        if (!gifFile.exists()) {
            WepWawetLogger.proxyError("$TAG: input does not exist",
                "input" to gifFile.absolutePath)
            return false
        }

        val bytes = try {
            gifFile.readBytes()
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG: cannot read input",
                "exception" to e.javaClass.name)
            return false
        }

        // ── Step 1: parse per-frame delays from Graphic Control Extensions ──
        val frameStartsMs = parseFrameTimestamps(bytes)

        // ── Step 2: decode with Movie ──
        val movie = try {
            Movie.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG: Movie.decodeByteArray FAILED",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            null
        }
        if (movie == null || movie.width() <= 0 || movie.height() <= 0) {
            WepWawetLogger.proxyError("$TAG: not a decodable (animated) GIF",
                "width" to (movie?.width() ?: -1),
                "height" to (movie?.height() ?: -1))
            return false
        }

        val width = movie.width()
        val height = movie.height()
        var durationMs = movie.duration().toLong()
        if (durationMs <= 0 && frameStartsMs.isNotEmpty()) {
            // Some GIFs report duration 0 through Movie — reconstruct it
            // from the GCE delay sum plus the last frame's delay.
            durationMs = frameStartsMs.last() + 100L
        }
        WepWawetLogger.proxy("$TAG: GIF decoded",
            "width" to width, "height" to height,
            "movie_duration_ms" to durationMs,
            "gce_frames" to frameStartsMs.size)

        // ── Step 3: build the frame-time list ──
        // Prefer the GCE-derived timestamps; fall back to 100 ms sampling
        // when the stream has no (readable) control extensions.
        val frameTimesMs: List<Long> = when {
            frameStartsMs.size >= MIN_FRAMES -> frameStartsMs
            durationMs > 0 -> {
                val times = mutableListOf<Long>()
                var t = 0L
                while (t < durationMs && times.size < MAX_FRAMES) {
                    times.add(t)
                    t += 100L
                }
                if (times.isEmpty()) times.add(0L)
                times
            }
            else -> listOf(0L)
        }
        if (frameTimesMs.size > MAX_FRAMES) {
            WepWawetLogger.proxyWarn("$TAG: clamping excessive frame count",
                "frames" to frameTimesMs.size,
                "clamped_to" to MAX_FRAMES)
        }
        val frames = frameTimesMs.take(MAX_FRAMES)

        // Average frame rate for the encoder's rate hints — clamped to a
        // range MediaFormat accepts for KEY_FRAME_RATE.
        val spanMs = (frames.lastOrNull() ?: 0L) + 100L
        val avgFps = ((frames.size * 1000.0) / spanMs).toInt().coerceIn(5, 60)

        // ── Step 4: decode every frame and encode it ──
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val pixels = IntArray(width * height)

        val encoder = BitmapToMp4Encoder(
            width = width,
            height = height,
            outputFile = outputFile,
            targetBitrate = BitmapToMp4Encoder.suggestBitrate(width, height),
            frameRate = avgFps,
        )
        if (!encoder.open()) {
            bitmap.recycle()
            return false
        }

        var ok = true
        for ((i, tMs) in frames.withIndex()) {
            try {
                movie.setTime(tMs.toInt())
                bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
                canvas.setBitmap(bitmap)
                movie.draw(canvas, 0f, 0f)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                if (!encoder.writeFrame(pixels, width, height, tMs * 1000L)) {
                    ok = false
                    break
                }
            } catch (e: Exception) {
                WepWawetLogger.proxyError("$TAG: frame encode failed",
                    "frame" to i, "time_ms" to tMs,
                    "exception" to e.javaClass.name,
                    "message" to (e.message ?: ""))
                ok = false
                break
            }
        }

        val finished = if (ok) encoder.finish() else false
        if (!ok || !finished) {
            encoder.release()
            bitmap.recycle()
            runCatching { outputFile.delete() }
            WepWawetLogger.proxyError("$TAG: transcode FAILED",
                "frames_attempted" to frames.size)
            return false
        }

        bitmap.recycle()
        WepWawetLogger.proxy("$TAG: transcode SUCCESS",
            "frames" to frames.size,
            "duration_ms" to spanMs,
            "fps_avg" to avgFps,
            "output_size" to outputFile.length(),
            "size_ratio" to "%.2fx".format(
                outputFile.length().toDouble() / bytes.size.toDouble()))
        return true
    }

    /**
     * Parse the frame start timestamps out of the GIF's Graphic Control
     * Extension blocks. Each GCE looks like:
     *
     *   21 F9 04 <packed> <delay-lo> <delay-hi> <transparent-index> 00
     *
     * `delay` is in 1/100 s units; a zero delay is treated as 1 (the GIF
     * spec's minimum), matching how every real player (and ffmpeg's gif
     * demuxer) interprets it. Frame N's start time is the cumulative sum
     * of delays of frames 0..N-1.
     *
     * Returns an empty list when the stream carries no GCE blocks — the
     * caller falls back to fixed-interval sampling.
     */
    private fun parseFrameTimestamps(bytes: ByteArray): List<Long> {
        val starts = mutableListOf<Long>()
        var i = 0
        var cumulativeMs = 0L
        val n = bytes.size

        // Header check — must be GIF87a/GIF89a to parse safely.
        if (n < 13) return starts
        val sig = String(bytes, 0, 6, Charsets.US_ASCII)
        if (!sig.startsWith("GIF8")) return starts

        // Walk blocks: skip the logical screen descriptor + global color
        // table, then scan block-by-block for extension 0x21 0xF9.
        var pos = 6
        val flags = bytes[pos + 4].toInt() and 0xFF
        pos += 7  // logical screen descriptor is 7 bytes after the signature
        if (flags and 0x80 != 0) {
            pos += 3 shl (flags and 0x07)  // global color table size
        }

        var guard = 0
        while (pos < n && guard < 5_000_000) {
            guard++
            when (bytes[pos].toInt() and 0xFF) {
                0x21 -> {  // extension introducer
                    val label = bytes.getOrNull(pos + 1)?.toInt()?.and(0xFF) ?: break
                    if (label == 0xF9 && pos + 8 <= n &&
                        (bytes[pos + 2].toInt() and 0xFF) == 0x04
                    ) {
                        // Graphic Control Extension — read the delay.
                        val delayCs = ((bytes[pos + 4].toInt() and 0xFF)) or
                            ((bytes[pos + 5].toInt() and 0xFF) shl 8)
                        starts.add(cumulativeMs)
                        cumulativeMs += (if (delayCs <= 0) 1 else delayCs) * 10L
                        pos += 8  // 21 F9 04 … 00 — fixed 8-byte block
                    } else {
                        // Other extension (plain text / application / comment):
                        // skip its sub-blocks.
                        pos += 2
                        pos = skipDataSubBlocks(bytes, pos)
                    }
                }
                0x2C -> {  // image descriptor — skip it + local color table + data
                    if (pos + 10 > n) break
                    val localFlags = bytes[pos + 9].toInt() and 0xFF
                    pos += 10
                    if (localFlags and 0x80 != 0) {
                        pos += 3 shl (localFlags and 0x07)
                    }
                    pos += 1  // LZW minimum code size byte
                    pos = skipDataSubBlocks(bytes, pos)
                }
                0x3B -> break  // trailer — done
                else -> break   // desynchronized — bail out, caller falls back
            }
        }
        return starts
    }

    /** Skip a chain of data sub-blocks (len byte + payload; 0x00 ends it). */
    private fun skipDataSubBlocks(bytes: ByteArray, start: Int): Int {
        var pos = start
        val n = bytes.size
        var guard = 0
        while (pos < n && guard < 5_000_000) {
            guard++
            val len = bytes[pos].toInt() and 0xFF
            pos += 1
            if (len == 0) return pos
            pos += len
        }
        return pos
    }
}
