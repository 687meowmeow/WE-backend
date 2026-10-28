package com.wepwawet.ui.screens

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
 * Video trim screen — a standard video trimmer UI with:
 *   - Video preview at the top (matching the video's actual aspect ratio)
 *   - A single timeline bar with frame preview thumbnails
 *   - Two draggable handles on the bar (start + end) like every other video
 *     trimming app
 *
 * The user drags the start/end handles to set the trim range. The preview
 * seeks to the handle position while dragging.
 *
 * When done, "Trim & Send" trims the video (lossless stream copy) and
 * queues it for transfer. "Send Full Video" skips trimming and sends the
 * full video (same pack + queue code as the pre-trim send-to-phone).
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

    // ExoPlayer
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    // Video metadata
    var durationMs by remember { mutableLongStateOf(0L) }
    var videoWidth by remember { mutableStateOf(0) }
    var videoHeight by remember { mutableStateOf(0) }
    var startMs by remember { mutableLongStateOf(0L) }
    var endMs by remember { mutableLongStateOf(0L) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var isTrimming by remember { mutableStateOf(false) }
    var trimStatus by remember { mutableStateOf("") }

    // Frame thumbnails for the timeline
    val thumbnails = remember { mutableStateListOf<android.graphics.Bitmap>() }

    // Get video metadata (duration, width, height) + generate thumbnails
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

                // Generate 8 frame thumbnails evenly spaced
                val thumbCount = 8
                for (i in 0 until thumbCount) {
                    val t = if (thumbCount > 1) dur * i / (thumbCount - 1) else 0
                    val bmp = retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bmp != null) {
                        // Scale down to ~80px wide
                        val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, 80, (80f * h / w).toInt(), true)
                        thumbnails.add(scaled)
                    }
                }
                retriever.release()
            } catch (e: Exception) {
                WepWawetLogger.workshopWarn("VideoTrimScreen: metadata/thumbnail failed",
                    "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            }
        }
    }

    // Poll current position
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

            // === Timeline with frame previews + range slider ===
            if (durationMs > 0) {
                // Thumbnail strip
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(Color.DarkGray, RoundedCornerShape(4.dp)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        thumbnails.forEach { bmp ->
                            androidx.compose.foundation.Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize(),
                            )
                        }
                    }
                }

                // Single RangeSlider with start + end handles
                val startRange = if (durationMs > 0) startMs.toFloat() / durationMs else 0f
                val endRange = if (durationMs > 0) endMs.toFloat() / durationMs else 1f
                androidx.compose.material3.RangeSlider(
                    value = startRange..endRange,
                    onValueChange = { range ->
                        val newStart = (range.start * durationMs).toLong()
                            .coerceAtMost((endMs - 500).coerceAtLeast(0))
                        val newEnd = (range.endInclusive * durationMs).toLong()
                            .coerceAtLeast(startMs + 500).coerceAtMost(durationMs)
                        startMs = newStart
                        endMs = newEnd
                    },
                    onValueChangeFinished = {
                        player.seekTo(startMs)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }

            // === Progress bar ===
            if (durationMs > 0 && !isTrimming) {
                LinearProgressIndicator(
                    progress = { currentPosition.toFloat() / durationMs.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // === Trim status ===
            if (isTrimming) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(trimStatus, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.weight(1f))

            // === Buttons ===
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
                modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Send Full Video (No Trim)")
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
