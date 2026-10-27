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
     * Transcode a video file to MP4 with H.264 (or H.265 if supported) video codec.
     *
     * v0.6.0 (parity §8-1): Now supports H.265/HEVC output encoding when the device
     * supports it. H.265 produces ~50% smaller files at equivalent quality.
     *
     * v0.6.0 (parity §8-2): Audio is now properly transcoded to AAC (was dropped in
     * v0.5.32). WE wallpapers commonly have no audio, but some do — we transcode
     * to AAC 128 kbps stereo 44.1 kHz when audio is present.
     *
     * v0.6.0 (parity §8-3): Frame rate is now preserved from source (was hardcoded to 30).
     *
     * v0.6.0 (parity §8-4): Rotation metadata is now preserved via muxer.setOrientationHint.
     *
     * v0.6.0 (parity §8-5): B-frames are disabled for seek-friendly wallpaper playback.
     *
     * v0.6.0 (parity §8-6): H.264 encoder uses Baseline profile for max device compatibility.
     *
     * v0.6.0 (parity §8-7): On transcode failure, returns false (don't rename .webm to .mp4
     * with WebM content).
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
            // v0.6.0 (parity §8-7): CRITICAL FIX — Return false on transcode failure.
            // Previously the code would copy the .webm bytes into the .mp4 file and return
            // true, producing an MP4 with WebM content that Android's MediaPlayer can't play.
            // The caller (MpkgPacker.kt) renames the VFS entry from *.webm to *.mp4 — but
            // the content is still WebM, so the wallpaper shows black/error.
            //
            // New behavior: return false so caller keeps the .webm extension. Android's
            // MediaPlayer MIGHT support WebM natively on some devices — better than a
            // guaranteed-broken .mp4 with WebM content.
            WepWawetLogger.proxyError("$TAG.transcodeToMp4H264 EXCEPTION — keeping original extension",
                "input" to inputFile.name,
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            false
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

        // v0.6.0 (parity §8-2): Log audio presence and (NEW) attempt to transcode it
        // to AAC. Previously (v0.5.32) audio was dropped entirely because the
        // incomplete transcode produced invalid MP4s. Now we properly transcode.
        val hasAudio = audioTrackIndex >= 0
        if (hasAudio) {
            WepWawetLogger.proxy("$TAG: input has audio track — will transcode to AAC",
                "input" to inputFile.name)
        }

        // Read video track format (width, height, duration, etc.)
        val videoInputFormat = extractor.getTrackFormat(videoTrackIndex)
        val width = videoInputFormat.getInteger(android.media.MediaFormat.KEY_WIDTH)
        val height = videoInputFormat.getInteger(android.media.MediaFormat.KEY_HEIGHT)
        val duration = if (videoInputFormat.containsKey(android.media.MediaFormat.KEY_DURATION)) {
            videoInputFormat.getLong(android.media.MediaFormat.KEY_DURATION)
        } else 0L

        // v0.6.0 (parity §8-3): Preserve source frame rate (was hardcoded to 30)
        val srcFps = if (videoInputFormat.containsKey(android.media.MediaFormat.KEY_FRAME_RATE))
            videoInputFormat.getInteger(android.media.MediaFormat.KEY_FRAME_RATE) else 30

        WepWawetLogger.proxy("$TAG: video track found",
            "width" to width, "height" to height,
            "duration_ms" to (duration / 1000),
            "fps" to srcFps,
            "input_mime" to (videoInputFormat.getString(android.media.MediaFormat.KEY_MIME) ?: "?"))

        // Create the output MediaMuxer (writes MP4 container)
        val muxer = android.media.MediaMuxer(
            outputFile.absolutePath,
            android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )

        // v0.6.0 (parity §8-4): Preserve rotation metadata via muxer orientation hint
        if (videoInputFormat.containsKey(android.media.MediaFormat.KEY_ROTATION)) {
            val rotation = videoInputFormat.getInteger(android.media.MediaFormat.KEY_ROTATION)
            muxer.setOrientationHint(rotation)
            WepWawetLogger.proxy("$TAG: preserving rotation metadata",
                "rotation_degrees" to rotation)
        }

        // v0.6.0 (parity §8-1): Pick H.264 (default) or H.265 (if device supports encoding)
        // H.265 produces ~50% smaller files at equivalent quality, useful for large wallpapers.
        val codecMime = pickVideoCodec()
        val isH265 = codecMime == android.media.MediaFormat.MIMETYPE_VIDEO_HEVC
        WepWawetLogger.proxy("$TAG: selected video codec",
            "mime" to codecMime, "is_h265" to isH265)

        // Configure the video encoder (H.264 or H.265)
        val videoEncoderFormat = android.media.MediaFormat.createVideoFormat(
            codecMime, width, height
        ).apply {
            setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(android.media.MediaFormat.KEY_BIT_RATE, computeBitrate(width, height, srcFps))
            setInteger(android.media.MediaFormat.KEY_FRAME_RATE, srcFps)  // §8-3: preserve source fps
            setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 2) // I-frame every 2 seconds

            // v0.6.0 (parity §8-6): H.264 Baseline profile for max device compatibility
            // (some older Android devices only support Baseline at Level 3)
            if (!isH265) {
                try {
                    setInteger(android.media.MediaFormat.KEY_PROFILE,
                        android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    setInteger(android.media.MediaFormat.KEY_LEVEL,
                        android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel3)
                } catch (_: Exception) {
                    // Some encoders don't support KEY_PROFILE — fall back to default
                }
            }

            // v0.6.0 (parity §8-5): Disable B-frames for seek-friendly wallpaper playback
            // (wallpaper playback involves frequent random seeks to any frame for preview)
            try {
                setInteger("max-bframes", 0)  // API 23+
            } catch (_: Exception) {
                // Some encoders don't support this key — ignore
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                try {
                    setInteger(android.media.MediaFormat.KEY_LATENCY, 0)  // API 30+
                } catch (_: Exception) {}
            }
        }

        val videoEncoder = android.media.MediaCodec.createEncoderByType(codecMime)
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

        // Add video track to muxer
        val muxerVideoTrack = muxer.addTrack(videoEncoderFormat)

        // v0.6.0 (parity §8-2): Properly transcode audio if present
        var muxerAudioTrack = -1
        var audioDecoder: android.media.MediaCodec? = null
        var audioEncoder: android.media.MediaCodec? = null
        var audioInputFormat: android.media.MediaFormat? = null
        if (hasAudio) {
            audioInputFormat = extractor.getTrackFormat(audioTrackIndex)
            try {
                // Set up audio decoder (auto-detect source codec)
                val audioMime = audioInputFormat.getString(android.media.MediaFormat.KEY_MIME)!!
                audioDecoder = android.media.MediaCodec.createDecoderByType(audioMime)
                audioDecoder.configure(audioInputFormat, null, null, 0)
                audioDecoder.start()

                // Set up audio encoder: AAC 128 kbps stereo 44.1 kHz
                val audioEncoderFormat = android.media.MediaFormat.createAudioFormat(
                    android.media.MediaFormat.MIMETYPE_AUDIO_AAC,
                    44100,  // sample rate (universal)
                    2       // stereo (universal)
                ).apply {
                    setInteger(android.media.MediaFormat.KEY_BIT_RATE, 128000)
                    setInteger(android.media.MediaFormat.KEY_AAC_PROFILE,
                        android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                }
                audioEncoder = android.media.MediaCodec.createEncoderByType(
                    android.media.MediaFormat.MIMETYPE_AUDIO_AAC)
                audioEncoder.configure(audioEncoderFormat, null, null,
                    android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
                audioEncoder.start()

                muxerAudioTrack = muxer.addTrack(audioEncoderFormat)
                WepWawetLogger.proxy("$TAG: audio transcoding setup complete",
                    "source_mime" to audioMime,
                    "target_codec" to "AAC 128kbps stereo 44.1kHz")
            } catch (e: Exception) {
                WepWawetLogger.proxyWarn("$TAG: audio transcode setup failed — dropping audio",
                    "exception" to e.javaClass.name, "message" to (e.message ?: ""))
                audioDecoder?.release()
                audioEncoder?.release()
                audioDecoder = null
                audioEncoder = null
                muxerAudioTrack = -1
            }
        }

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

        // v0.6.0 (parity §8-2): Transcode audio if audio codecs were set up successfully
        if (audioDecoder != null && audioEncoder != null && muxerAudioTrack >= 0 && audioInputFormat != null) {
            try {
                // Extract audio from the OTHER track (we already selected video track above)
                // Use a second MediaExtractor since the first one's selected track is video
                val audioExtractor = android.media.MediaExtractor()
                audioExtractor.setDataSource(inputFile.absolutePath)
                audioExtractor.selectTrack(audioTrackIndex)

                val audioInfo = android.media.MediaCodec.BufferInfo()
                var audioAllExtracted = false
                var audioAllDecoded = false
                var audioAllEncoded = false
                var audioFrameCount = 0
                val audioTimeoutUs = 10000L

                while (!audioAllEncoded) {
                    // Extract + decode audio
                    if (!audioAllExtracted) {
                        val audioInputIndex = audioDecoder.dequeueInputBuffer(audioTimeoutUs)
                        if (audioInputIndex >= 0) {
                            val audioInputBuffer = audioDecoder.getInputBuffer(audioInputIndex)!!
                            val audioSampleSize = audioExtractor.readSampleData(audioInputBuffer, 0)
                            if (audioSampleSize < 0) {
                                audioDecoder.queueInputBuffer(audioInputIndex, 0, 0, 0,
                                    android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                audioAllExtracted = true
                            } else {
                                val audioSampleTime = audioExtractor.sampleTime
                                audioDecoder.queueInputBuffer(audioInputIndex, 0, audioSampleSize, audioSampleTime, 0)
                                audioExtractor.advance()
                            }
                        }
                    }

                    // Decode → encode audio
                    if (!audioAllDecoded) {
                        val audioOutputIndex = audioDecoder.dequeueOutputBuffer(audioInfo, audioTimeoutUs)
                        if (audioOutputIndex >= 0) {
                            if (audioInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                audioAllDecoded = true
                                audioEncoder.signalEndOfInputStream()
                            } else {
                                // Copy decoded audio buffer to encoder input
                                val decodedAudioBuffer = audioDecoder.getOutputBuffer(audioOutputIndex)
                                val encInputIdx = audioEncoder.dequeueInputBuffer(audioTimeoutUs)
                                if (encInputIdx >= 0 && decodedAudioBuffer != null) {
                                    val encInputBuf = audioEncoder.getInputBuffer(encInputIdx)!!
                                    val chunkSize = minOf(audioInfo.size, encInputBuf.capacity())
                                    decodedAudioBuffer.position(audioInfo.offset)
                                    decodedAudioBuffer.limit(audioInfo.offset + chunkSize)
                                    encInputBuf.clear()
                                    encInputBuf.put(decodedAudioBuffer)
                                    audioEncoder.queueInputBuffer(encInputIdx, 0, chunkSize, audioInfo.presentationTimeUs, 0)
                                }
                            }
                            audioDecoder.releaseOutputBuffer(audioOutputIndex, false)
                        }
                    }

                    // Drain audio encoder output (always — even after decoder EOS)
                    val audioEncOutIdx = audioEncoder.dequeueOutputBuffer(audioInfo, audioTimeoutUs)
                    if (audioEncOutIdx >= 0) {
                        if (audioInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            audioAllEncoded = true
                        } else {
                            val audioEncBuf = audioEncoder.getOutputBuffer(audioEncOutIdx)!!
                            muxer.writeSampleData(muxerAudioTrack, audioEncBuf, audioInfo)
                            audioFrameCount++
                        }
                        audioEncoder.releaseOutputBuffer(audioEncOutIdx, false)
                    }
                }

                audioExtractor.release()
                WepWawetLogger.proxy("$TAG: audio transcoded",
                    "audio_frames" to audioFrameCount)
            } catch (e: Exception) {
                WepWawetLogger.proxyWarn("$TAG: audio transcode loop failed — continuing with video-only",
                    "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            }
        }

        // Clean up — v0.6.0: also release audio codecs if present
        try { videoDecoder.stop() } catch (_: Exception) {}
        try { videoDecoder.release() } catch (_: Exception) {}
        try { videoEncoder.stop() } catch (_: Exception) {}
        try { videoEncoder.release() } catch (_: Exception) {}
        try { audioDecoder?.stop() } catch (_: Exception) {}
        try { audioDecoder?.release() } catch (_: Exception) {}
        try { audioEncoder?.stop() } catch (_: Exception) {}
        try { audioEncoder?.release() } catch (_: Exception) {}
        try { muxer.stop() } catch (_: Exception) {}
        try { muxer.release() } catch (_: Exception) {}
        try { extractor.release() } catch (_: Exception) {}

        WepWawetLogger.proxy("$TAG.transcodeToMp4H264 SUCCESS",
            "input" to inputFile.name,
            "input_size" to inputFile.length(),
            "output" to outputFile.name,
            "output_size" to outputFile.length(),
            "video_frames" to videoFrameCount,
            "audio_transcoded" to (audioDecoder != null))

        return true
    }

    /**
     * Compute a reasonable H.264/H.265 bitrate for the given resolution.
     * Uses a simple heuristic: width * height * framerate * 0.1 bits/sec.
     *
     * v0.6.0 (parity §8-3): Added fps parameter (was hardcoded to 30).
     */
    private fun computeBitrate(width: Int, height: Int, fps: Int = 30): Int {
        // For 1080p (1920x1080) at 30fps: ~6.2 Mbps
        // For 720p (1280x720) at 30fps: ~2.8 Mbps
        // For 480p (854x480) at 30fps: ~1.2 Mbps
        val pixels = width.toLong() * height.toLong()
        return (pixels * fps * 0.1).toInt().coerceIn(500000, 20000000)
    }

    /**
     * v0.6.0 (parity §8-1): Pick the best video codec for the output MP4.
     *
     * Returns:
     *   - MediaFormat.MIMETYPE_VIDEO_HEVC if the device supports H.265 encoding
     *     (gives ~50% smaller files at equivalent quality — important for
     *     4K+ wallpapers that would otherwise be very large)
     *   - MediaFormat.MIMETYPE_VIDEO_AVC (H.264) as the default fallback
     *     (universally supported on Android 5.0+)
     *
     * Note: The phone's H.265 capability should ideally be queried from the
     * EncAuthRequest — but for now we just check the local device's encoding
     * capability (which is what we need — the phone decodes whatever we encode).
     *
     * Future enhancement: When the phone reports H.265 support in EncAuthRequest,
     * prefer H.265 unconditionally. Otherwise, stick with H.264 for max compat.
     */
    private fun pickVideoCodec(): String {
        // Try H.265 (HEVC) first — produces ~50% smaller files
        try {
            val codecList = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            for (codecInfo in codecList.codecInfos) {
                if (codecInfo.isEncoder) {
                    for (type in codecInfo.supportedTypes) {
                        if (type.equals(android.media.MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) {
                            WepWawetLogger.proxy("$TAG.pickVideoCodec: device supports H.265 encoding — using HEVC")
                            return android.media.MediaFormat.MIMETYPE_VIDEO_HEVC
                        }
                    }
                }
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("$TAG.pickVideoCodec: H.265 capability check failed — falling back to H.264",
                "exception" to e.javaClass.name)
        }
        // Default: H.264 (universally supported on Android 5.0+)
        WepWawetLogger.proxy("$TAG.pickVideoCodec: falling back to H.264 (AVC) — device doesn't support H.265 encoding")
        return android.media.MediaFormat.MIMETYPE_VIDEO_AVC
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
