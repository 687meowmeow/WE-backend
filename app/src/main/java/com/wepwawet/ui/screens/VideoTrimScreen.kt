package com.wepwawet.ui.screens

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.wepwawet.data.model.WallpaperType
import com.wepwawet.data.model.WorkshopItem
import com.wepwawet.data.model.WorkshopItemTyped
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.pairing.PairingService
import com.wepwawet.pairing.VideoTrimmer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Video trim screen — a standard mobile video trimmer UI:
 *
 *   ┌──────────────────────────────────────────────────────┐
 *   │  Video preview (matches video's actual aspect ratio) │
 *   └──────────────────────────────────────────────────────┘
 *
 *   start                                              end
 *   00:03                                            00:14
 *
 *   ┌─▼─┬────────────────────────────────────────────┬─▼─┐
 *   │░░░│ [thumb][thumb][thumb][thumb][thumb][thumb] │░░░│
 *   │░░░│ [thumb][thumb][thumb][thumb][thumb][thumb] │░░░│
 *   └───┴────────────────────────────────────────────┴───┘
 *
 *   ↑ dimmed    ↑ thumbnails form the bar background     ↑ dimmed
 *               (selected region — frames visible)
 *
 *   [ Trim & Send ] [ Send Full Video (No Trim) ]
 *
 * The video frames ARE the bar. Two dim overlays cover the parts
 * outside the selected range. The RangeSlider sits on top with
 * transparent track colors — only the thumb handles are visible,
 * sitting on top of the strip like every other mobile video trimmer
 * (WhatsApp / iMovie / CapCut / etc.). 16 frame thumbnails evenly
 * spaced across the bar so the user has a clear visual sense of the
 * video's content as they drag.
 *
 * "Trim & Send" trims the video (lossless stream copy via
 * [VideoTrimmer.trim]) and queues it for transfer.
 * "Send Full Video" skips trimming and sends the full video
 * (same pack + queue code as the pre-trim send-to-phone).
 *
 * @param videoFile The downloaded video file (already exists on disk).
 * @param itemTitle Wallpaper title.
 * @param itemId Workshop item ID.
 * @param onBack Navigate back.
 * @param onSendComplete Called after the video has been queued.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoTrimScreen(
    videoFile: File,
    itemTitle: String,
    itemId: String,
    onBack: () -> Unit,
    onSendComplete: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val videoUri = remember { Uri.fromFile(videoFile).toString() }

    // ExoPlayer — preview loop of the video.
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    // Video metadata + trimmer state.
    var durationMs by remember { mutableLongStateOf(0L) }
    var videoWidth by remember { mutableStateOf(0) }
    var videoHeight by remember { mutableStateOf(0) }
    var startMs by remember { mutableLongStateOf(0L) }
    var endMs by remember { mutableLongStateOf(0L) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var isTrimming by remember { mutableStateOf(false) }
    var trimStatus by remember { mutableStateOf("") }

    // v0.7.3: 16 frame thumbnails (was 8). More frames = clearer
    // visual sense of the video's content as the user drags. The
    // thumbnails form the BACKGROUND of the trim bar (not a separate
    // row above it) — the standard pattern used by every mobile video
    // trimmer (WhatsApp, iMovie, CapCut, VN, etc.).
    val thumbnails = remember { mutableStateListOf<android.graphics.Bitmap>() }

    // Get video metadata (duration, width, height) + generate thumbnails.
    LaunchedEffect(videoFile) {
        withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(videoFile.absolutePath)
                val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
                durationMs = dur
                endMs = dur
                videoWidth = w
                videoHeight = h
                WepWawetLogger.workshop("VideoTrimScreen: metadata loaded",
                    "duration_ms" to dur, "width" to w, "height" to h)

                // Generate 16 frame thumbnails evenly spaced across the
                // video duration. Using OPTION_CLOSEST_SYNC (sync frames
                // only) is fast — each call is ~10-30ms even for big videos,
                // so 16 calls add up to well under a second.
                val thumbCount = 16
                for (i in 0 until thumbCount) {
                    val t = if (thumbCount > 1) dur * i / (thumbCount - 1) else 0
                    val bmp = retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bmp != null) {
                        // Scale down to height=160px (crisp at 72dp on xhdpi+)
                        // and width proportional to the video's aspect ratio.
                        // ContentScale.FillBounds in the strip Row will then
                        // stretch/squash each frame to its uniform-weight slot,
                        // giving the filmstrip look.
                        val targetH = 160
                        val targetW = (targetH * w / h).toInt().coerceAtLeast(1)
                        val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
                        if (scaled != bmp) bmp.recycle()
                        thumbnails.add(scaled)
                    }
                }
                retriever.release()
                WepWawetLogger.workshop("VideoTrimScreen: thumbnails generated",
                    "count" to thumbnails.size)
            } catch (e: Exception) {
                WepWawetLogger.workshopWarn("VideoTrimScreen: metadata/thumbnail failed",
                    "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            }
        }
    }

    // Poll current position for the progress bar.
    LaunchedEffect(player) {
        while (true) {
            currentPosition = player.currentPosition
            delay(200)
        }
    }

    val aspectRatio = if (videoWidth > 0 && videoHeight > 0) {
        videoWidth.toFloat() / videoHeight.toFloat()
    } else {
        16f / 9f
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trim Video", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // === Video preview (matches video's actual aspect ratio) ===
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio),
                shape = RoundedCornerShape(12.dp),
                color = Color.Black,
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = true
                            setShowNextButton(false)
                            setShowPreviousButton(false)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // === Time labels ===
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${formatTime(startMs)}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text("Duration: ${formatTime(endMs - startMs)}", style = MaterialTheme.typography.labelMedium)
                Text("${formatTime(endMs)}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }

            // === v0.7.3: Trimmer bar with frames INSIDE the bar ===
            // Layout (bottom to top in a Box):
            //   1. Row of 16 frame thumbnails filling the box (the strip
            //      background — frames ARE the bar, not above it).
            //   2. Row with three weight-based Boxes: left-dim |
            //      transparent-selected | right-dim. The dimmed Boxes are
            //      semi-transparent black overlays covering the frames
            //      OUTSIDE the selected range (the standard "dim the
            //      unselected portion" look used by every mobile video
            //      trimmer — WhatsApp, iMovie, CapCut, etc.).
            //   3. Material3 RangeSlider on top with transparent track
            //      colors. The slider's track is invisible (so the frames
            //      show through); only the two thumb handles are visible,
            //      sitting on top of the strip at the start/end positions.
            // The whole Box is clipped to a rounded rectangle for a
            // nice filmstrip look.
            if (durationMs > 0) {
                val startRange = startMs.toFloat() / durationMs
                val endRange = endMs.toFloat() / durationMs
                val leftDimWeight = startRange.coerceIn(0f, 1f)
                val middleWeight = (endRange - startRange).coerceIn(0f, 1f)
                val rightDimWeight = (1f - endRange).coerceIn(0f, 1f)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clip(RoundedCornerShape(6.dp)),
                ) {
                    // 1) Thumbnail strip background (frames ARE the bar)
                    if (thumbnails.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            thumbnails.forEach { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.FillBounds,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxSize(),
                                )
                            }
                        }
                    } else {
                        // Fallback while thumbnails load
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.DarkGray),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }

                    // 2) Dimmed overlays outside the selected region.
                    // Three weighted Boxes — when startRange=0 the left
                    // dim is invisible (weight 0 = 0 width); same for the
                    // right dim when endRange=1. The middle Box is
                    // transparent so the frames show through.
                    Row(modifier = Modifier.fillMaxSize()) {
                        if (leftDimWeight > 0f) {
                            Box(
                                modifier = Modifier
                                    .weight(leftDimWeight)
                                    .fillMaxHeight()
                                    .background(Color.Black.copy(alpha = 0.55f)),
                            )
                        }
                        if (middleWeight > 0f) {
                            Box(modifier = Modifier.weight(middleWeight))
                        }
                        if (rightDimWeight > 0f) {
                            Box(
                                modifier = Modifier
                                    .weight(rightDimWeight)
                                    .fillMaxHeight()
                                    .background(Color.Black.copy(alpha = 0.55f)),
                            )
                        }
                    }

                    // 3) RangeSlider on top — transparent track colors so
                    //    only the two thumb handles are visible, sitting
                    //    on top of the strip. The handles' positions match
                    //    the dimmed-overlay boundaries (modulo the slider's
                    //    internal half-thumb padding, which is small and
                    //    visually unnoticeable on a 72dp-tall strip).
                    RangeSlider(
                        value = startRange..endRange,
                        onValueChange = { range ->
                            // Enforce a 500ms minimum selection so the
                            // start/end can't collapse onto each other.
                            val newStart = (range.start * durationMs).toLong()
                                .coerceAtMost((endMs - 500).coerceAtLeast(0))
                            val newEnd = (range.endInclusive * durationMs).toLong()
                                .coerceAtLeast(startMs + 500).coerceAtMost(durationMs)
                            startMs = newStart
                            endMs = newEnd
                        },
                        onValueChangeFinished = {
                            // Seek the preview to the start handle when the
                            // user lifts their finger — gives a "preview
                            // from the trim point" effect.
                            player.seekTo(startMs)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center),
                        colors = SliderDefaults.colors(
                            // Both tracks transparent so the frames show
                            // through; only the thumbs (white) are visible.
                            activeTrackColor = Color.Transparent,
                            inactiveTrackColor = Color.Transparent,
                            thumbColor = Color.White,
                        ),
                    )
                }
            } else {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }

            // === Progress bar (current playback position) ===
            if (durationMs > 0 && !isTrimming) {
                LinearProgressIndicator(
                    progress = { currentPosition.toFloat() / durationMs.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // === Trim status (during Trim & Send / Send Full) ===
            if (isTrimming) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(trimStatus, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.weight(1f))

            // === Trim & Send + Send Full Video side by side ===
            // Both buttons in a single Row (side by side, not stacked
            // vertically) per the v0.7.2 dialog redesign.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        if (isTrimming) return@Button
                        isTrimming = true
                        trimStatus = "Trimming video…"
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                try {
                                    val trimmedFile = File(context.cacheDir, "${itemId}_trimmed.mp4")
                                    trimStatus = "Trimming ${formatTime(startMs)}–${formatTime(endMs)}…"
                                    val success = VideoTrimmer.trim(videoFile, trimmedFile, startMs, endMs)
                                    val packedFile = if (success) trimmedFile else videoFile
                                    trimStatus = if (success) "Packing trimmed video…" else "Trim failed — packing full video…"

                                    val mpkgFile = File(context.cacheDir, "${itemId}_${System.currentTimeMillis()}_send.mpkg")
                                    val item = WorkshopItemTyped(
                                        item = WorkshopItem(publishedfileid = itemId, title = itemTitle),
                                        type = WallpaperType.VIDEO,
                                    )
                                    val installDir = File(context.cacheDir, "workshop_downloads/$itemId")
                                    val previewFile = File(installDir, "preview.jpg").takeIf { it.exists() }
                                        ?: File(installDir, "preview.gif").takeIf { it.exists() }
                                    com.wepwawet.pairing.MpkgPacker.packVideoWallpaper(
                                        item = item, videoFile = packedFile,
                                        outputFile = mpkgFile, previewFile = previewFile,
                                    )
                                    trimStatus = "Queuing for transfer…"
                                    PairingService.queueWallpaper(mpkgFile, itemTitle)
                                    PairingService.launchWallpaperEngineApp(context)
                                    runCatching { trimmedFile.delete() }
                                    runCatching { videoFile.delete() }
                                    WepWawetLogger.workshop("VideoTrimScreen: trim + send complete",
                                        "item_id" to itemId, "mpkg_size" to mpkgFile.length())
                                    isTrimming = false
                                    onSendComplete()
                                } catch (e: Exception) {
                                    isTrimming = false
                                    trimStatus = "Failed: ${e.message}"
                                }
                            }
                        }
                    },
                    enabled = !isTrimming && durationMs > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (isTrimming) "Processing…" else "Trim & Send")
                }

                Button(
                    onClick = {
                        if (isTrimming) return@Button
                        isTrimming = true
                        trimStatus = "Packing full video…"
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                try {
                                    val mpkgFile = File(context.cacheDir, "${itemId}_${System.currentTimeMillis()}_send.mpkg")
                                    val item = WorkshopItemTyped(
                                        item = WorkshopItem(publishedfileid = itemId, title = itemTitle),
                                        type = WallpaperType.VIDEO,
                                    )
                                    val installDir = File(context.cacheDir, "workshop_downloads/$itemId")
                                    val previewFile = File(installDir, "preview.jpg").takeIf { it.exists() }
                                        ?: File(installDir, "preview.gif").takeIf { it.exists() }
                                    com.wepwawet.pairing.MpkgPacker.packVideoWallpaper(
                                        item = item, videoFile = videoFile,
                                        outputFile = mpkgFile, previewFile = previewFile,
                                    )
                                    trimStatus = "Queuing for transfer…"
                                    PairingService.queueWallpaper(mpkgFile, itemTitle)
                                    PairingService.launchWallpaperEngineApp(context)
                                    runCatching { videoFile.delete() }
                                    isTrimming = false
                                    onSendComplete()
                                } catch (e: Exception) {
                                    isTrimming = false
                                    trimStatus = "Failed: ${e.message}"
                                }
                            }
                        }
                    },
                    enabled = !isTrimming,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Send Full Video")
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val sec = totalSec % 60
    val min = (totalSec / 60) % 60
    val hr = totalSec / 3600
    return if (hr > 0) "%d:%02d:%02d".format(hr, min, sec) else "%d:%02d".format(min, sec)
}
