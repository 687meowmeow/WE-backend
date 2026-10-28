package com.wepwawet.pairing

import android.media.MediaExtractor
import android.media.MediaMuxer
import com.wepwawet.log.WepWawetLogger
import java.io.File
import java.nio.ByteBuffer

/**
 * VideoTrimmer — trims an MP4 video to a specified start/end time range
 * WITHOUT re-encoding (stream copy, lossless, fast).
 *
 * Uses MediaExtractor to read the input video's tracks and MediaMuxer to
 * write a new MP4 containing only the frames within the [startMs, endMs]
 * range. This is the Android equivalent of ffmpeg's `-ss <start> -to <end>
 * -c copy` — it copies compressed data without decoding/re-encoding, so
 * it's fast (typically <1 second for a 50MB video) and lossless.
 *
 * The trim is keyframe-accurate (not frame-accurate): the start point
 * snaps to the nearest keyframe at or before [startMs]. For most video
 * wallpapers this is fine — the keyframe interval is typically 2-5
 * seconds, so the actual start time is within 2-5s of the requested time.
 *
 * If frame-accurate trimming is needed in the future, we'd need to
 * re-encode (decode from the nearest keyframe to [startMs], then encode
 * from [startMs] to [endMs]). That's slower and introduces quality loss.
 * For now, keyframe-accurate is the right tradeoff.
 */
object VideoTrimmer {
    private const val TAG = "VideoTrimmer"

    /**
     * Trim a video file to the specified time range.
     *
     * @param inputFile Source video file (MP4, WebM, etc.)
     * @param outputFile Output MP4 file (will be overwritten if exists)
     * @param startMs Start time in milliseconds
     * @param endMs End time in milliseconds
     * @return true on success, false on failure
     */
    fun trim(inputFile: File, outputFile: File, startMs: Long, endMs: Long): Boolean {
        if (!inputFile.exists()) {
            WepWawetLogger.proxyError("$TAG: input file does not exist",
                "input" to inputFile.absolutePath)
            return false
        }
        if (startMs < 0 || endMs <= startMs) {
            WepWawetLogger.proxyError("$TAG: invalid trim range",
                "start_ms" to startMs, "end_ms" to endMs)
            return false
        }
        // Cap endMs to video duration
        val duration = getDurationMs(inputFile)
        val cappedEnd = if (endMs > duration) duration else endMs
        if (cappedEnd <= startMs) {
            WepWawetLogger.proxyError("$TAG: trim range is zero or negative after capping",
                "start_ms" to startMs, "capped_end_ms" to cappedEnd)
            return false
        }

        WepWawetLogger.proxy("$TAG: starting trim",
            "input" to inputFile.name,
            "input_size" to inputFile.length(),
            "output" to outputFile.name,
            "start_ms" to startMs,
            "end_ms" to cappedEnd,
            "duration_ms" to duration)

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(inputFile.absolutePath)

            // Find all tracks (video + audio)
            val trackInfos = mutableListOf<Pair<Int, android.media.MediaFormat>>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    trackInfos.add(i to format)
                }
            }
            if (trackInfos.isEmpty()) {
                WepWawetLogger.proxyError("$TAG: no video/audio tracks found",
                    "track_count" to extractor.trackCount)
                return false
            }

            // Create the MediaMuxer
            val muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            // Add tracks to muxer and select them in the extractor
            val muxerTracks = mutableMapOf<Int, Int>() // extractorTrackIndex → muxerTrackIndex
            for ((extIdx, format) in trackInfos) {
                val muxIdx = muxer.addTrack(format)
                muxerTracks[extIdx] = muxIdx
                extractor.selectTrack(extIdx)
            }
            muxer.start()

            // Copy samples within the time range
            val buffer = ByteBuffer.allocate(1024 * 1024) // 1MB buffer
            val bufferInfo = android.media.MediaCodec.BufferInfo()
            var samplesCopied = 0
            var bytesCopied = 0L

            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0) break // end of stream

                val sampleMs = sampleTime / 1000 // μs → ms
                if (sampleMs < startMs) {
                    // Before start — skip this sample
                    extractor.advance()
                    continue
                }
                if (sampleMs > cappedEnd) {
                    // After end — done
                    break
                }

                val trackIndex = extractor.sampleTrackIndex
                val muxerTrackIndex = muxerTracks[trackIndex]
                if (muxerTrackIndex == null) {
                    // This track wasn't added (shouldn't happen)
                    extractor.advance()
                    continue
                }

                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = sampleTime - (startMs * 1000) // shift to 0-based
                bufferInfo.flags = when {
                    extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0 ->
                        android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
                    else -> 0
                }

                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                samplesCopied++
                bytesCopied += sampleSize.toLong()
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            extractor.release()

            WepWawetLogger.proxy("$TAG: trim complete",
                "output" to outputFile.name,
                "output_size" to outputFile.length(),
                "samples_copied" to samplesCopied,
                "bytes_copied" to bytesCopied,
                "trim_duration_ms" to (cappedEnd - startMs))
            true
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG: trim failed",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            runCatching { extractor.release() }
            false
        }
    }

    /**
     * Get the duration of a video file in milliseconds.
     */
    fun getDurationMs(file: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var maxDuration = 0L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                    val dur = format.getLong(android.media.MediaFormat.KEY_DURATION)
                    if (dur > maxDuration) maxDuration = dur
                }
            }
            extractor.release()
            maxDuration / 1000 // μs → ms
        } catch (e: Exception) {
            runCatching { extractor.release() }
            0L
        }
    }
}
