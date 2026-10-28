package com.wepwawet.ui.screens

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.wepwawet.pairing.SteamCredentialsStore
import com.wepwawet.pairing.VideoTrimmer
import com.wepwawet.pairing.WorkshopDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Video trim screen — downloads a video wallpaper from Steam, then shows a
 * live ExoPlayer preview with range sliders for trim start/end.
 *
 * Flow:
 * 1. If [needsDownload] is true, download the video via WorkshopDownloader
 *    (using the user's stored Steam credentials). Show a progress indicator.
 * 2. Once the video file is available, create an ExoPlayer and show the
 *    preview with two range sliders (start + end).
 * 3. The user drags the sliders to set the trim range. The player seeks
 *    to the slider position for instant visual feedback.
 * 4. When the user taps "Trim & Send", the video is trimmed via
 *    [VideoTrimmer.trim] (lossless stream copy), packed into a .mpkg,
 *    and queued for transfer to the paired phone.
 *
 * @param videoFileUri URI string of the video file (empty if needs download).
 * @param videoFile The File object for the video (may not exist yet).
 * @param itemTitle Wallpaper title.
 * @param itemId Workshop item ID.
 * @param needsDownload If true, the video needs to be downloaded first.
 * @param onBack Navigate back.
 * @param onSendComplete Called after the trimmed video has been queued.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoTrimScreen(
    videoFileUri: String,
    videoFile: File,
    itemTitle: String,
    itemId: String,
    needsDownload: Boolean,
    onBack: () -> Unit,
    onSendComplete: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Download state
    var isDownloading by remember { mutableStateOf(needsDownload) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadStatus by remember { mutableStateOf("Connecting to Steam…") }
    var videoReady by remember { mutableStateOf(!needsDownload && videoFile.exists()) }
    var actualVideoFile by remember { mutableStateOf(if (videoFile.exists()) videoFile else null) }
    var actualVideoUri by remember { mutableStateOf(if (videoFileUri.isNotBlank() && videoFile.exists()) videoFileUri else "") }

    // Trim state
    var durationMs by remember { mutableLongStateOf(0L) }
    var startMs by remember { mutableLongStateOf(0L) }
    var endMs by remember { mutableLongStateOf(0L) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var isTrimming by remember { mutableStateOf(false) }
    var trimStatus by remember { mutableStateOf("") }

    // ExoPlayer — created lazily when the video is ready
    val player = remember(actualVideoUri) {
        if (actualVideoUri.isNotBlank()) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(actualVideoUri))
                prepare()
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_ONE
            }
        } else null
    }
    DisposableEffect(player) {
        onDispose { player?.release() }
    }

    // Download the video if needed
    LaunchedEffect(needsDownload) {
        if (needsDownload && isDownloading) {
            withContext(Dispatchers.IO) {
                try {
                    val appContext = context.applicationContext
                    val steamUser = SteamCredentialsStore.getUsername(appContext)
                    val steamPass = SteamCredentialsStore.getPassword(appContext)
                    if (steamUser == null || steamPass == null) {
                        downloadStatus = "Steam login not set. Go to Settings first."
                        isDownloading = false
                        return@withContext
                    }
                    val downloader = WorkshopDownloader(steamUser, steamPass,
                        SteamCredentialsStore.getGuardCode(appContext))
                    val outputFile = File(appContext.cacheDir, "${itemId}_video_download.pkg")
                    downloadStatus = "Downloading video from Steam…"
                    val result = downloader.downloadWorkshopPkg(
                        context = appContext,
                        workshopId = itemId,
                        outputFile = outputFile,
                        onProgress = { pct ->
                            downloadProgress = pct
                            downloadStatus = "Downloading… ${(pct * 100).toInt()}%"
                        },
                        onStatusUpdate = { status ->
                            downloadStatus = status
                        },
                    )
                    if (result != null && result.exists() && result.length() > 0) {
                        actualVideoFile = result
                        actualVideoUri = Uri.fromFile(result).toString()
                        videoReady = true
                        isDownloading = false
                        WepWawetLogger.workshop("VideoTrimScreen: video downloaded",
                            "file" to result.name,
                            "size" to result.length())
                    } else {
                        downloadStatus = "Download failed — check logs"
                        isDownloading = false
                    }
                    runCatching { downloader.cleanup() }
                } catch (e: Exception) {
                    downloadStatus = "Download failed: ${e.message}"
                    isDownloading = false
                    WepWawetLogger.workshopWarn("VideoTrimScreen: download failed",
                        "exception" to e.javaClass.name,
                        "message" to (e.message ?: ""))
                }
            }
        }
    }

    // Get duration once the player is prepared
    LaunchedEffect(player) {
        while (player != null && durationMs == 0L) {
            val d = player.duration
            if (d > 0) {
                durationMs = d
                endMs = d
                WepWawetLogger.workshop("VideoTrimScreen: video loaded",
                    "duration_ms" to d)
            }
            delay(100)
        }
    }

    // Poll current position for the progress bar
    LaunchedEffect(player) {
        while (player != null) {
            currentPosition = player.currentPosition
            delay(200)
        }
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (isDownloading) {
                // === Download progress ===
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            downloadStatus,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (downloadProgress > 0f) {
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            } else if (!videoReady) {
                // === Download failed ===
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        downloadStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else if (player != null) {
                // === Video preview ===
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
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

                // === Trim range display ===
                Text(
                    text = "Start: ${formatTime(startMs)} — End: ${formatTime(endMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Duration: ${formatTime(endMs - startMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                // === Start slider ===
                Text("Start Position", style = MaterialTheme.typography.labelMedium)
                androidx.compose.material3.Slider(
                    value = if (durationMs > 0) startMs.toFloat() / durationMs else 0f,
                    onValueChange = { ratio ->
                        val newPos = (ratio * durationMs).toLong()
                        startMs = newPos.coerceAtMost((endMs - 500).coerceAtLeast(0))
                        player.seekTo(startMs)
                    },
                    onValueChangeFinished = {
                        player.seekTo(startMs)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                // === End slider ===
                Text("End Position", style = MaterialTheme.typography.labelMedium)
                androidx.compose.material3.Slider(
                    value = if (durationMs > 0) endMs.toFloat() / durationMs else 1f,
                    onValueChange = { ratio ->
                        val newPos = (ratio * durationMs).toLong()
                        endMs = newPos.coerceAtLeast(startMs + 500).coerceAtMost(durationMs)
                    },
                    onValueChangeFinished = {
                        player.seekTo(endMs)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

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
                    Text(
                        trimStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.weight(1f))

                // === Trim & Send button ===
                Button(
                    onClick = {
                        if (isTrimming) return@Button
                        val videoFile = actualVideoFile ?: return@Button
                        isTrimming = true
                        trimStatus = "Trimming video…"
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                try {
                                    val trimmedFile = File(
                                        context.cacheDir,
                                        "${itemId}_trimmed.mp4"
                                    )
                                    trimStatus = "Trimming ${formatTime(startMs)}–${formatTime(endMs)}…"
                                    val success = VideoTrimmer.trim(
                                        videoFile, trimmedFile, startMs, endMs
                                    )
                                    val packedFile = if (success) trimmedFile else videoFile
                                    trimStatus = if (success) "Packing trimmed video…" else "Trim failed — packing full video…"

                                    val mpkgFile = File(
                                        context.cacheDir,
                                        "${itemId}_${System.currentTimeMillis()}_send.mpkg"
                                    )
                                    val item = WorkshopItemTyped(
                                        item = WorkshopItem(
                                            publishedfileid = itemId,
                                            title = itemTitle,
                                        ),
                                        type = WallpaperType.VIDEO,
                                    )
                                    com.wepwawet.pairing.MpkgPacker.packVideoWallpaper(
                                        item = item,
                                        videoFile = packedFile,
                                        outputFile = mpkgFile,
                                        previewFile = null,
                                    )
                                    trimStatus = "Queuing for transfer…"
                                    PairingService.queueWallpaper(mpkgFile, itemTitle)
                                    PairingService.launchWallpaperEngineApp(context)
                                    runCatching { trimmedFile.delete() }
                                    runCatching { videoFile.delete() }
                                    WepWawetLogger.workshop("VideoTrimScreen: trim + send complete",
                                        "item_id" to itemId,
                                        "trim_start_ms" to startMs,
                                        "trim_end_ms" to endMs,
                                        "mpkg_size" to mpkgFile.length())
                                    isTrimming = false
                                    onSendComplete()
                                } catch (e: Exception) {
                                    isTrimming = false
                                    trimStatus = "Failed: ${e.message}"
                                    WepWawetLogger.workshopWarn("VideoTrimScreen: trim + send failed",
                                        "exception" to e.javaClass.name,
                                        "message" to (e.message ?: ""))
                                }
                            }
                        }
                    },
                    enabled = !isTrimming && durationMs > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (isTrimming) "Processing…" else "Trim & Send")
                }

                // === Send Full Video button ===
                if (!isTrimming) {
                    Button(
                        onClick = {
                            val videoFile = actualVideoFile ?: return@Button
                            isTrimming = true
                            trimStatus = "Packing full video…"
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    try {
                                        val mpkgFile = File(
                                            context.cacheDir,
                                            "${itemId}_${System.currentTimeMillis()}_send.mpkg"
                                        )
                                        val item = WorkshopItemTyped(
                                            item = WorkshopItem(
                                                publishedfileid = itemId,
                                                title = itemTitle,
                                            ),
                                            type = WallpaperType.VIDEO,
                                        )
                                        com.wepwawet.pairing.MpkgPacker.packVideoWallpaper(
                                            item = item,
                                            videoFile = videoFile,
                                            outputFile = mpkgFile,
                                            previewFile = null,
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
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Send Full Video (No Trim)")
                    }
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
    return if (hr > 0) {
        "%d:%02d:%02d".format(hr, min, sec)
    } else {
        "%d:%02d".format(min, sec)
    }
}
