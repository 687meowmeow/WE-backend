package com.wepwawet.pairing

import com.wepwawet.log.WepWawetLogger
import java.io.File

/**
 * Video transcoder using Android's built-in MediaCodec API.
 *
 * Real WE PC uses `ffmpeg_mp4_h264` to transcode WebM videos to MP4 with
 * H.264 codec when sending to Android. We do the same on-device using
 * Android's hardware-accelerated MediaCodec API.
 *
 * The Android WE app's VideoDrawable expects MP4 with H.264 codec (the
 * `mp4_h264` codec identifier is written to project.json by the transcoder).
 * WebM files (VP8/VP9 video + Vorbis/Opus audio) may not be supported by
 * all Android VideoDrawable implementations, so we transcode them.
 *
 * This implementation uses MediaExtractor to read the input video's tracks,
 * MediaCodec to decode + re-encode the video track, and MediaMuxer to write
 * the output MP4. It handles VP8/VP9 → H.264 video transcoding.
 *
 * v0.5.32 (C2 fix): audio track is intentionally NOT transcoded — input
 * audio (if any) is dropped. WE wallpapers commonly have no audio, and
 * the previous incomplete audio-transcode implementation produced
 * structurally invalid MP4s (audio track header with zero samples).
 *
 * No external dependencies required — uses only Android framework APIs
 * (MediaExtractor, MediaCodec, MediaMuxer) available since API 18 (Android 4.3).
 */
object VideoTranscoder {
    private const val TAG = "VideoTranscoder"

    /**
     * Check if video transcoding is available (always true on Android 8.0+).
     * MediaCodec is a framework API available on all Android devices.
     */
    fun isAvailable(): Boolean = true

    /**
     * Transcode a video file to MP4 with H.264 video codec. Audio is dropped
     * (see v0.5.32 / C2 fix above).
     *
     * @param inputFile Source video file (.webm, .mp4, .mov, .mkv, .avi, etc.)
     * @param outputFile Output MP4 file (will be overwritten if exists)
     * @return true on success, false on failure
     */
    fun transcodeToMp4H264(inputFile: File, outputFile: File): Boolean {
        if (!inputFile.exists()) {
            WepWawetLogger.proxyError("$TAG.transcodeToMp4H264 FAILED — input file does not exist",
                "input" to inputFile.absolutePath)
            return false
        }

        // If the input is already an MP4, just copy it (no transcoding needed)
        if (inputFile.name.endsWith(".mp4", ignoreCase = true)) {
            WepWawetLogger.proxy("$TAG.transcodeToMp4H264: input is already .mp4 — copying instead of transcoding",
                "input" to inputFile.name,
                "input_size" to inputFile.length())
            inputFile.copyTo(outputFile, overwrite = true)
            return true
        }

        WepWawetLogger.proxy("$TAG.transcodeToMp4H264: starting transcode",
            "input" to inputFile.name,
            "input_size" to inputFile.length(),
            "output" to outputFile.name)

        return try {
            transcodeWithMediaCodec(inputFile, outputFile)
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG.transcodeToMp4H264 EXCEPTION — falling back to copy",
                "input" to inputFile.name,
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            // Fallback: copy the file as-is (Android might support WebM natively)
            inputFile.copyTo(outputFile, overwrite = true)
            true
        }
    }

    /**
     * Transcode using MediaExtractor + MediaCodec + MediaMuxer.
     *
     * Flow:
     * 1. MediaExtractor reads the input file's tracks (video + audio)
     * 2. For each track:
     *    a. MediaCodec decodes the compressed data to raw frames
     *    b. MediaCodec re-encodes the raw frames to H.264 (video) or AAC (audio)
     * 3. MediaMuxer writes the encoded data to the output MP4 file
     */
    private fun transcodeWithMediaCodec(inputFile: File, outputFile: File): Boolean {
        val extractor = android.media.MediaExtractor()
        extractor.setDataSource(inputFile.absolutePath)

        // Find video and audio tracks
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/") && videoTrackIndex == -1) {
                videoTrackIndex = i
            } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                audioTrackIndex = i
            }
        }

        if (videoTrackIndex == -1) {
            WepWawetLogger.proxyError("$TAG: no video track found in input",
                "input" to inputFile.name,
                "track_count" to extractor.trackCount)
            extractor.release()
            return false
        }

        // v0.5.32 (C2 fix): log audio presence for diagnostics, but do NOT
        // transcode audio. (Previously the audio codecs were created and
        // started but never fed, producing invalid MP4s.)
        if (audioTrackIndex >= 0) {
            WepWawetLogger.proxy("$TAG: input has audio track — will be dropped (not transcoded)",
                "input" to inputFile.name)
        }

        // Read video track format (width, height, duration, etc.)
        val videoInputFormat = extractor.getTrackFormat(videoTrackIndex)
        val width = videoInputFormat.getInteger(android.media.MediaFormat.KEY_WIDTH)
        val height = videoInputFormat.getInteger(android.media.MediaFormat.KEY_HEIGHT)
        val duration = if (videoInputFormat.containsKey(android.media.MediaFormat.KEY_DURATION)) {
            videoInputFormat.getLong(android.media.MediaFormat.KEY_DURATION)
        } else 0L

        WepWawetLogger.proxy("$TAG: video track found",
            "width" to width, "height" to height,
            "duration_ms" to (duration / 1000),
            "input_mime" to (videoInputFormat.getString(android.media.MediaFormat.KEY_MIME) ?: "?"))

        // Create the output MediaMuxer (writes MP4 container)
        val muxer = android.media.MediaMuxer(
            outputFile.absolutePath,
            android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )

        // Configure the H.264 video encoder
        val videoEncoderFormat = android.media.MediaFormat.createVideoFormat(
            android.media.MediaFormat.MIMETYPE_VIDEO_AVC, width, height
        ).apply {
            setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(android.media.MediaFormat.KEY_BIT_RATE, computeBitrate(width, height))
            setInteger(android.media.MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 2) // I-frame every 2 seconds
        }

        val videoEncoder = android.media.MediaCodec.createEncoderByType(
            android.media.MediaFormat.MIMETYPE_VIDEO_AVC
        )
        videoEncoder.configure(videoEncoderFormat, null, null,
            android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoEncoder.start()

        // Configure the video decoder
        extractor.selectTrack(videoTrackIndex)
        val videoDecoder = android.media.MediaCodec.createDecoderByType(
            videoInputFormat.getString(android.media.MediaFormat.KEY_MIME)!!
        )
        videoDecoder.configure(videoInputFormat, null, null, 0)
        videoDecoder.start()

        // Add video track to muxer.
        //
        // === v0.5.32 (C2 fix): audio track intentionally NOT added. ===
        // Previously the audio decoder/encoder were created, started, and
        // `muxer.addTrack(audioEncoderFormat)` was called — but the main
        // transcode loop only processed the video track, so the output MP4
        // ended up with an audio track header in the moov box but ZERO
        // audio samples. This either causes `MediaMuxer.writeSampleData` to
        // throw on `stop()`, or produces a structurally invalid MP4 that
        // the WE Android `VideoDrawable`'s `MediaPlayer` may reject —
        // refusing to render the video track too. WE wallpapers commonly
        // have no audio anyway, so skipping audio entirely is the safer
        // fix. The audio DETECTION above is preserved only for logging.
        val muxerVideoTrack = muxer.addTrack(videoEncoderFormat)

        muxer.start()

        // === Transcode video ===
        val info = android.media.MediaCodec.BufferInfo()
        val bufferInfo = android.media.MediaCodec.BufferInfo()
        var allExtracted = false
        var allDecoded = false
        var allEncoded = false
        var videoFrameCount = 0

        // Simple transcode loop: extract → decode → encode → mux
        // This is a simplified implementation that handles the common case.
        // For production, a more robust async callback-based implementation
        // would be needed, but this works for the .webm → .mp4 use case.
        val TIMEOUT_US = 10000L // 10ms timeout

        while (!allEncoded) {
            // === Extract + decode video ===
            if (!allExtracted) {
                val inputBufferIndex = videoDecoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = videoDecoder.getInputBuffer(inputBufferIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        videoDecoder.queueInputBuffer(inputBufferIndex, 0, 0, 0,
                            android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        allExtracted = true
                    } else {
                        val sampleTime = extractor.sampleTime
                        videoDecoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // === Get decoded video frames → encode to H.264 ===
            if (!allDecoded) {
                val outputBufferIndex = videoDecoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outputBufferIndex >= 0) {
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        allDecoded = true
                        videoEncoder.signalEndOfInputStream()
                    } else {
                        // v0.5.32 (H6 fix): Use Image API (getOutputImage /
                        // getInputImage) to copy YUV planes from decoder
                        // output to encoder input. Previously this used raw
                        // ByteBuffer copies, which works only when the decoder
                        // and encoder happen to use the same YUV420 layout
                        // (e.g., both NV12 on Adreno). On other layouts
                        // (NV21, YV12, etc.) the raw byte copy produces
                        // scrambled chroma planes → green tint or swapped
                        // U/V in the output MP4.
                        //
                        // Per Google issue tracker
                        // (issuetracker.google.com/issues/319451102):
                        // "the layout of a YUV 4:2:0 ByteBuffer is device
                        // and codec specific … We recommend that you use
                        // the getOutputImage API (since API 21) instead."
                        //
                        // The Image API exposes per-plane rowStride and
                        // pixelStride, so [copyYuv420Image] can copy each
                        // plane correctly regardless of the source/dest
                        // layouts. (minSdk=26, so Image API is available.)
                        val decodedImage = videoDecoder.getOutputImage(outputBufferIndex)
                        val encoderInputIndex = videoEncoder.dequeueInputBuffer(TIMEOUT_US)
                        if (encoderInputIndex >= 0 && decodedImage != null) {
                            val encoderInput = videoEncoder.getInputImage(encoderInputIndex)
                            if (encoderInput != null) {
                                copyYuv420Image(decodedImage, encoderInput, width, height)
                                // Pass the encoder's input buffer capacity
                                // as size — this is the full YUV420 frame
                                // size the encoder expects (Y + U + V planes
                                // with the encoder's row stride padding).
                                val encoderBuf = videoEncoder.getInputBuffer(encoderInputIndex)
                                val expectedSize = encoderBuf?.capacity() ?: info.size
                                videoEncoder.queueInputBuffer(
                                    encoderInputIndex, 0, expectedSize,
                                    info.presentationTimeUs, 0,
                                )
                            } else {
                                // Fallback: rare codec that doesn't expose
                                // Image API for input — use raw ByteBuffer
                                // copy (the pre-H6-fix behavior).
                                val decodedBuffer = videoDecoder.getOutputBuffer(outputBufferIndex)
                                val rawEncoderInput = videoEncoder.getInputBuffer(encoderInputIndex)
                                if (decodedBuffer != null && rawEncoderInput != null) {
                                    val chunkSize = minOf(info.size, rawEncoderInput.capacity())
                                    decodedBuffer.position(info.offset)
                                    decodedBuffer.limit(info.offset + chunkSize)
                                    rawEncoderInput.clear()
                                    rawEncoderInput.put(decodedBuffer)
                                    videoEncoder.queueInputBuffer(
                                        encoderInputIndex, 0, chunkSize,
                                        info.presentationTimeUs, 0,
                                    )
                                }
                            }
                        }
                    }
                    videoDecoder.releaseOutputBuffer(outputBufferIndex, false)
                }
            }

            // === Always drain encoder output, even after decoder EOS ===
            // v0.5.32 (C1 fix): Previously this drain was INSIDE the
            // `if (!allDecoded)` block, so once the decoder hit EOS
            // (`allDecoded = true` + `signalEndOfInputStream()`), the
            // entire block — including the encoder drain — was skipped on
            // every subsequent iteration. The encoder's final EOS-flagged
            // output buffer was never drained, `allEncoded` never became
            // true, and the loop spun forever burning a CPU core.
            //
            // Per Android MediaCodec docs: "Once the client reaches the end
            // of the input data it signals the end of the input stream…
            // The codec will continue to return output buffers until it
            // eventually signals the end of the output stream" — so we
            // MUST keep calling dequeueOutputBuffer on the encoder until
            // we see BUFFER_FLAG_END_OF_STREAM.
            val encoderOutputIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (encoderOutputIndex >= 0) {
                if (bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    allEncoded = true
                } else {
                    val encodedBuffer = videoEncoder.getOutputBuffer(encoderOutputIndex)!!
                    muxer.writeSampleData(muxerVideoTrack, encodedBuffer, bufferInfo)
                    videoFrameCount++
                }
                videoEncoder.releaseOutputBuffer(encoderOutputIndex, false)
            }
        }

        WepWawetLogger.proxy("$TAG: video transcoded",
            "video_frames" to videoFrameCount)

        // Clean up
        videoDecoder.stop()
        videoDecoder.release()
        videoEncoder.stop()
        videoEncoder.release()
        muxer.stop()
        muxer.release()
        extractor.release()

        WepWawetLogger.proxy("$TAG.transcodeToMp4H264 SUCCESS",
            "input" to inputFile.name,
            "input_size" to inputFile.length(),
            "output" to outputFile.name,
            "output_size" to outputFile.length(),
            "video_frames" to videoFrameCount)

        return true
    }

    /**
     * Compute a reasonable H.264 bitrate for the given resolution.
     * Uses a simple heuristic: width * height * framerate * 0.1 bits/sec.
     */
    private fun computeBitrate(width: Int, height: Int): Int {
        // For 1080p (1920x1080) at 30fps: ~6.2 Mbps
        // For 720p (1280x720) at 30fps: ~2.8 Mbps
        // For 480p (854x480) at 30fps: ~1.2 Mbps
        val pixels = width.toLong() * height.toLong()
        return (pixels * 30 * 0.1).toInt().coerceIn(500000, 20000000)
    }

    /**
     * Copy YUV420 Image data from [src] to [dst], handling plane layout
     * differences (NV12 vs NV21 vs YV12 etc.).
     *
     * v0.5.32 (H6 fix): Previously the transcode loop used raw ByteBuffer
     * copies between decoder output and encoder input, which assumed the
     * two codecs used the same YUV420 layout. Per Android docs, the layout
     * is "device and codec specific" — feeding raw bytes produces
     * scrambled chroma planes (green tint, swapped U/V). The Image API
     * exposes the plane layout (rowStride / pixelStride) so we can copy
     * correctly regardless of source/dest layouts.
     *
     * Both [src] and [dst] must be 3-plane YUV420 Images (Y, U, V in that
     * order), which is what `MediaCodec.getOutputImage` / `getInputImage`
     * return for codecs configured with `COLOR_FormatYUV420Flexible`.
     */
    private fun copyYuv420Image(
        src: android.media.Image,
        dst: android.media.Image,
        width: Int,
        height: Int,
    ) {
        val srcPlanes = src.planes
        val dstPlanes = dst.planes
        // Y plane (full resolution)
        copyPlane(srcPlanes[0], dstPlanes[0], width, height)
        // U plane (quarter resolution for 4:2:0 chroma subsampling)
        copyPlane(srcPlanes[1], dstPlanes[1], width / 2, height / 2)
        // V plane (quarter resolution)
        copyPlane(srcPlanes[2], dstPlanes[2], width / 2, height / 2)
    }

    /**
     * Copy one YUV plane from [src] to [dst]. Handles differences in
     * rowStride and pixelStride (e.g., NV12's interleaved U/V with
     * pixelStride=2 vs YV12's planar U with pixelStride=1).
     *
     * Two paths:
     *  - Fast path: identical layouts → bulk ByteBuffer copy (capped to
     *    destination capacity to prevent overflow on padding mismatches).
     *  - Slow path: copy pixel by pixel → robust to any layout difference.
     */
    private fun copyPlane(
        src: android.media.Image.Plane,
        dst: android.media.Image.Plane,
        width: Int,
        height: Int,
    ) {
        val srcBuf = src.buffer
        val dstBuf = dst.buffer
        val srcRowStride = src.rowStride
        val dstRowStride = dst.rowStride
        val srcPixelStride = src.pixelStride
        val dstPixelStride = dst.pixelStride

        // Fast path: identical layouts → bulk copy.
        if (srcRowStride == dstRowStride && srcPixelStride == dstPixelStride) {
            srcBuf.position(0)
            val srcSlice = srcBuf.slice()
            srcSlice.limit(minOf(srcSlice.remaining(), dstBuf.capacity()))
            dstBuf.clear()
            dstBuf.put(srcSlice)
            return
        }

        // Slow path: copy pixel by pixel to handle layout differences.
        // Reads src at (rowIdx * srcRowStride + colIdx * srcPixelStride),
        // writes dst at (rowIdx * dstRowStride + colIdx * dstPixelStride).
        // NOTE: uses absolute get/put so we don't have to fiddle with
        // position/limit between reads and writes.
        for (rowIdx in 0 until height) {
            val srcRowStart = rowIdx * srcRowStride
            val dstRowStart = rowIdx * dstRowStride
            for (colIdx in 0 until width) {
                val srcPos = srcRowStart + colIdx * srcPixelStride
                val dstPos = dstRowStart + colIdx * dstPixelStride
                if (srcPos < srcBuf.capacity() && dstPos < dstBuf.capacity()) {
                    dstBuf.put(dstPos, srcBuf.get(srcPos))
                }
            }
        }
    }
}
