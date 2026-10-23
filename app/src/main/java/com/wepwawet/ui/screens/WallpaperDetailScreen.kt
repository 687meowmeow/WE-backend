package com.wepwawet.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wepwawet.data.WorkshopRepository
import com.wepwawet.data.model.WorkshopItemTyped
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.pairing.PairingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Wallpaper detail screen.
 *
 * Shows:
 *   - Full preview image (16:9)
 *   - Title, description, tags (chips), type, resolution guess, subs count, file size
 *   - "Download" button — downloads the preview image to a local cache (the actual
 *     workshop content requires the Wine+Steam backend which is out of scope for v1;
 *     downloading the preview at least gives the user visible feedback)
 *   - "Send to Phone" button — if a phone is paired via [PairingService], queues the
 *     cached file for transfer via the pairing protocol
 *   - Progress indicator (linear) during download/transfer
 *
 * All actions are logged via [WepWawetLogger.workshop].
 *
 * @param repo shared workshop repository — used for getById() lookups + subscribe()
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WallpaperDetailScreen(
    itemId: String,
    onBack: () -> Unit,
    repo: WorkshopRepository,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var item by remember { mutableStateOf<WorkshopItemTyped?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isPaired by remember { mutableStateOf(PairingService.isPaired()) }

    // Download / transfer state
    var downloadProgress by remember { mutableStateOf(0f) }  // 0..1
    var isDownloading by remember { mutableStateOf(false) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var isSending by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // File picker state — for picking local .pkg, image, or video files
    var pickedPkgUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pickedImageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pickedVideoUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // File picker launchers — uses ActivityResultContracts.OpenDocument for
    // arbitrary MIME types, with persistent permission so we can read the file
    // asynchronously after the picker returns.
    val pkgPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            WepWawetLogger.workshop("File picker: .pkg picked",
                "uri" to uri.toString(),
                "host" to uri.host,
                "path" to uri.path)
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some providers don't allow persistable permission
                WepWawetLogger.workshopDebug("File picker: persistable permission denied — using one-shot")
            }
            pickedPkgUri = uri
            // Trigger the actual send flow
            item?.let {
                onPickLocalPkgClicked(context, it, scope, uri,
                    { isSending = true; statusMessage = it },
                    { isSending = false; statusMessage = it })
            }
        } else {
            WepWawetLogger.workshopWarn("File picker: no .pkg selected")
        }
    }

    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            WepWawetLogger.workshop("File picker: image picked",
                "uri" to uri.toString())
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {}
            pickedImageUri = uri
            item?.let {
                onPickLocalImageClicked(context, it, scope, uri,
                    { isSending = true; statusMessage = it },
                    { isSending = false; statusMessage = it })
            }
        } else {
            WepWawetLogger.workshopWarn("File picker: no image selected")
        }
    }

    val videoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            WepWawetLogger.workshop("File picker: video picked",
                "uri" to uri.toString())
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {}
            pickedVideoUri = uri
            item?.let {
                onPickLocalVideoClicked(context, it, scope, uri,
                    { isSending = true; statusMessage = it },
                    { isSending = false; statusMessage = it })
            }
        } else {
            WepWawetLogger.workshopWarn("File picker: no video selected")
        }
    }

    LaunchedEffect(itemId) {
        loading = true
        errorMessage = null
        WepWawetLogger.workshop("WallpaperDetailScreen load", "id" to itemId)
        try {
            val loaded = repo.getById(itemId)
            item = loaded
            if (loaded == null) {
                errorMessage = "Wallpaper not found."
                WepWawetLogger.workshopWarn("WallpaperDetailScreen: item not found",
                    "id" to itemId)
            }
        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to load wallpaper."
            WepWawetLogger.workshopWarn("WallpaperDetailScreen load failed",
                "id" to itemId, "exception" to e.javaClass.name)
        } finally {
            loading = false
        }
    }

    // Periodically poll pairing state (companion flag is cheap)
    LaunchedEffect(Unit) {
        while (true) {
            isPaired = PairingService.isPaired()
            kotlinx.coroutines.delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.title ?: "Loading…", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Scaffold
        }
        val current = item ?: run {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(errorMessage ?: "Wallpaper not found.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // === Preview image ===
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    AsyncImage(
                        model = current.previewUrl,
                        contentDescription = current.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // === Title ===
            item {
                Text(current.title, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
            }

            // === Metadata block ===
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MetadataRow("Type", current.type.displayName)
                        MetadataRow("Creator", current.creator.ifBlank { "Unknown" })
                        MetadataRow("Subscriptions", current.subscriptionsFormatted)
                        MetadataRow("Favorites", current.favorited.toString())
                        MetadataRow("Views", current.views.toString())
                        MetadataRow("File size", current.fileSizeFormatted)
                        MetadataRow("Created", current.createdDateFormatted)
                        MetadataRow("Resolution", guessResolution(current))
                    }
                }
            }

            // === Tags ===
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tags", style = MaterialTheme.typography.titleSmall)
                    if (current.tags.isEmpty()) {
                        Text("No tags", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            current.tags.forEach { tag ->
                                AssistChip(
                                    onClick = {},
                                    label = { Text(tag) },
                                )
                            }
                        }
                    }
                }
            }

            // === Description ===
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Description", style = MaterialTheme.typography.titleSmall)
                    Text(
                        current.description.ifBlank { "No description available." },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // === Status message (download/send feedback) ===
            item {
                statusMessage?.let {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            it,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // === Progress ===
            if (isDownloading || isSending) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(
                            progress = { if (isDownloading) downloadProgress else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            if (isDownloading) "Downloading… ${(downloadProgress * 100).toInt()}%"
                            else "Sending to phone…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // === Send to phone button (downloads content) ===
            item {
                Button(
                    onClick = {
                        if (!isSending) {
                            onSendToPhoneClicked(context, current, scope,
                                { isSending = true; statusMessage = it },
                                { isSending = false; statusMessage = it })
                        }
                    },
                    enabled = !isSending,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Send, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isSending) "Sending…"
                        else if (!isPaired) "Send to phone (pair first)"
                        else "Send to phone (download .pkg from Steam)"
                    )
                }
            }

            // === Pick local .pkg file (best quality — real WE content) ===
            item {
                OutlinedButton(
                    onClick = {
                        if (!isSending) {
                            pkgPicker.launch(arrayOf("application/octet-stream", "application/x-pkg", "*/*"))
                        }
                    },
                    enabled = !isSending,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Upload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Pick local .pkg file (best quality)")
                }
            }

            // === Pick local image file (Scene) ===
            item {
                OutlinedButton(
                    onClick = {
                        if (!isSending) {
                            imagePicker.launch(arrayOf("image/*"))
                        }
                    },
                    enabled = !isSending,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Image, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Pick local image (Scene wallpaper)")
                }
            }

            // === Pick local video file (Video) ===
            item {
                OutlinedButton(
                    onClick = {
                        if (!isSending) {
                            videoPicker.launch(arrayOf("video/*"))
                        }
                    },
                    enabled = !isSending,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.VideoCall, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Pick local video (Video wallpaper)")
                }
            }

            // === Debug: Save .mpkg to Downloads for inspection ===
            // Lets the user (and us) inspect the actual .mpkg file we generate
            // via `xxd`, `binwalk`, `strings`, etc. If WE rejects the .mpkg,
            // comparing our output to a real .mpkg from a Windows PC will
            // reveal exactly which bytes are wrong.
            item {
                OutlinedButton(
                    onClick = {
                        if (!isSending) {
                            onDownloadMpkgClicked(context, current, scope,
                                { isSending = true; statusMessage = it },
                                { isSending = false; statusMessage = it })
                        }
                    },
                    enabled = !isSending,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Save .mpkg to Downloads (debug)")
                }
            }

            // === Bottom spacer ===
            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

// ====== Composable sub-parts ======

/**
 * Send-to-phone click handler. Extracted from the Button onClick lambda so it
 * can use early returns cleanly without label gymnastics.
 *
 * IMPORTANT: This is the LEGACY/BROKEN path that only sends the small
 * Steam Workshop preview thumbnail (192x192 GIF) packaged as a Scene .mpkg.
 * The actual workshop content is a multi-GB .pkg file that requires Steam
 * authentication to download. The WE Android app will display the thumbnail
 * as a static image — this is NOT the actual wallpaper.
 *
 * For REAL wallpaper content, use:
 *   - "Pick local .pkg file" button (best quality — needs user to download
 *     the .pkg from Steam on PC and copy it to the phone)
 *   - "Pick local image" button (Scene wallpaper from any JPEG/PNG)
 *   - "Pick local video" button (Video wallpaper from any MP4)
 *
 * We keep this button for backward compatibility but log a clear warning
 * explaining the limitation.
 *
 * Flow:
 *   1. Check paired state — abort with status if not paired
 *   2. Download the workshop preview image (cache hit if already downloaded)
 *   3. Pack the preview into a .mpkg file using MpkgPacker.packSceneWallpaper
 *      (builds a Scene wallpaper with the preview as the texture)
 *   4. Queue the .mpkg for transfer via PairingService.queueWallpaper
 *   5. The actual transfer happens on the next heartbeat in
 *      WePairingServer.commandLoop → transferWallpaper
 *
 * Every step is logged with structured key-value context so the user can
 * trace exactly where things go wrong in the Logs screen.
 *
 * @param onStart called when the operation begins (with a status message)
 * @param onEnd called when the operation ends (with a final status message)
 */
private fun onSendToPhoneClicked(
    context: Context,
    item: WorkshopItemTyped,
    scope: kotlinx.coroutines.CoroutineScope,
    onStart: (String) -> Unit,
    onEnd: (String) -> Unit,
) {
    WepWawetLogger.workshop("send-to-phone tapped",
        "id" to item.id,
        "is_paired" to PairingService.isPaired(),
        "title" to item.title.take(80),
        "tags" to item.tags.joinToString(","),
        "type" to item.type.displayName,
        "warning" to "preview-only mode — actual content requires local .pkg file")
    if (!PairingService.isPaired()) {
        val msg = "Not paired — open the Pairing tab first."
        WepWawetLogger.workshopWarn("send-to-phone: not paired, aborting",
            "id" to item.id)
        onEnd(msg)
        return
    }

    WepWawetLogger.workshopWarn("send-to-phone: WARNING — sending PREVIEW ONLY",
        "explanation" to "Steam hides the file_url for paid apps (Wallpaper Engine = app 431960). The 'preview' image is just a small 192x192 thumbnail (often an animated GIF). For the ACTUAL wallpaper content (real video file or full-resolution .tex textures), use 'Pick local .pkg file' button.",
        "alternative" to "Use 'Pick local .pkg file' to send real content.")

    onStart("Sending PREVIEW ONLY. For real content use 'Pick local .pkg' below.")
    scope.launch {
        try {
            // === Step 1: Download the preview image ===
            WepWawetLogger.workshop("send-to-phone: step 1/3 downloading preview",
                "id" to item.id,
                "preview_url" to (item.previewUrl ?: "null"),
                "preview_url_host" to (item.previewUrl?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() } ?: "null"))
            val previewFile = downloadWallpaper(context, item) { /* progress; ignored here */ }
            if (previewFile == null || !previewFile.exists()) {
                val msg = "Failed to download preview image."
                WepWawetLogger.workshopWarn("send-to-phone: preview download failed",
                    "id" to item.id,
                    "preview_url" to (item.previewUrl ?: "null"))
                onEnd(msg)
                return@launch
            }
            WepWawetLogger.workshop("send-to-phone: step 1/3 preview downloaded",
                "file" to previewFile.absolutePath,
                "size" to previewFile.length(),
                "size_human" to formatBytes(previewFile.length()))

            // Detect file type (GIF, JPEG, PNG, etc.)
            val fileType = detectFileType(previewFile)
            WepWawetLogger.workshop("send-to-phone: detected file type",
                "file_type" to fileType,
                "file_name" to previewFile.name)

            // === Step 2: Pack the preview into a Scene-type .mpkg file ===
            WepWawetLogger.workshop("send-to-phone: step 2/3 packing Scene .mpkg",
                "out" to "${item.id}_tmp.mpkg",
                "image_file" to previewFile.name,
                "image_size" to previewFile.length(),
                "tex_format" to "RGBA8888 (format=0)",
                "blending" to "normal",
                "depth_test" to "enabled",
                "schemecolor" to "computed average color")
            val sendId = System.currentTimeMillis()
            val mpkgFile = java.io.File(context.cacheDir, "${item.id}_$sendId.mpkg")
            try {
                com.wepwawet.pairing.MpkgPacker.packSceneWallpaper(
                    item = item,
                    imageFile = previewFile,
                    outputFile = mpkgFile,
                )
            } catch (e: Exception) {
                WepWawetLogger.workshopWarn("send-to-phone: step 2/3 packing failed",
                    "exception" to e.javaClass.name,
                    "message" to (e.message ?: ""))
                onEnd("Failed to pack wallpaper: ${e.message}")
                return@launch
            }
            if (!mpkgFile.exists() || mpkgFile.length() == 0L) {
                WepWawetLogger.workshopWarn("send-to-phone: step 2/3 mpkg empty",
                    "path" to mpkgFile.absolutePath,
                    "exists" to mpkgFile.exists(),
                    "size" to mpkgFile.length())
                onEnd("Failed to create .mpkg file (empty)")
                return@launch
            }
            WepWawetLogger.workshop("send-to-phone: step 2/3 mpkg ready",
                "file" to mpkgFile.name,
                "size" to mpkgFile.length(),
                "size_human" to formatBytes(mpkgFile.length()))

            // === Step 3: Queue for transfer ===
            WepWawetLogger.workshop("send-to-phone: step 3/3 queueing for transfer",
                "file" to mpkgFile.name,
                "size" to mpkgFile.length())
            val deviceName = PairingService.pairedDeviceName ?: "phone"
            PairingService.queueWallpaper(mpkgFile, item.title)
            WepWawetLogger.workshop("send-to-phone: QUEUED",
                "file" to mpkgFile.name,
                "size" to mpkgFile.length(),
                "title" to item.title.take(80),
                "device" to deviceName,
                "next_action" to "WePairingServer will pick this up on next heartbeat (~3s) and transfer via AES-encrypted chunks")
            onEnd("Queued for transfer to $deviceName (PREVIEW ONLY — for real content, use 'Pick local .pkg'). Transfer starts on next heartbeat (~3s).")
        } catch (e: Exception) {
            WepWawetLogger.workshopWarn("send-to-phone: unhandled exception",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            onEnd("Error: ${e.message}")
        }
    }
}

/**
 * Format a byte count as a human-readable string (B, KB, MB, GB).
 */
private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}

/**
 * Detect file type from magic bytes.
 * Returns "GIF", "JPEG", "PNG", "MP4", or "unknown".
 */
private fun detectFileType(file: java.io.File): String {
    try {
        val raf = java.io.RandomAccessFile(file, "r")
        val header = ByteArray(12)
        raf.readFully(header)
        raf.close()
        // GIF: starts with "GIF87a" or "GIF89a"
        if (header.size >= 6 && (header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte())) {
            return "GIF"
        }
        // JPEG: starts with 0xFF 0xD8 0xFF
        if (header.size >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
            return "JPEG"
        }
        // PNG: starts with 0x89 P N G \r \n 0x1A \n
        if (header.size >= 8 && header[0] == 0x89.toByte() && header[1] == 'P'.code.toByte() && header[2] == 'N'.code.toByte() && header[3] == 'G'.code.toByte()) {
            return "PNG"
        }
        // MP4: starts with "ftyp" at offset 4
        if (header.size >= 8 && header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() && header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()) {
            return "MP4"
        }
        // WebP: starts with "RIFF" then ?? "WEBP"
        if (header.size >= 12 && header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() && header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()) {
            return "WebP"
        }
        return "unknown"
    } catch (e: Exception) {
        return "unknown (${e.message})"
    }
}

/**
 * Pick a local .pkg file (downloaded by user via Steam on PC, copied to phone).
 *
 * This is the BEST-QUALITY path: the user manually downloads the actual
 * workshop content via Steam on their PC, then copies the .pkg file to their
 * phone. We extract all assets (.tex files for Scene, .mp4 for Video) and
 * repackage them into a .mpkg.
 *
 * The .pkg file is typically located at:
 *   `Steam/steamapps/workshop/content/431960/<workshop_id>/scene.pkg`
 * (or similar — may also be named with the workshop ID directly).
 *
 * @param uri the content:// URI returned by the file picker
 * @param onStart called when the operation begins (with a status message)
 * @param onEnd called when the operation ends (with a final status message)
 */
private fun onPickLocalPkgClicked(
    context: Context,
    item: WorkshopItemTyped,
    scope: kotlinx.coroutines.CoroutineScope,
    uri: android.net.Uri,
    onStart: (String) -> Unit,
    onEnd: (String) -> Unit,
) {
    WepWawetLogger.workshop("pick-local-pkg tapped",
        "id" to item.id,
        "title" to item.title.take(80),
        "uri" to uri.toString(),
        "is_paired" to PairingService.isPaired())

    if (!PairingService.isPaired()) {
        WepWawetLogger.workshopWarn("pick-local-pkg: not paired, aborting",
            "id" to item.id)
        onEnd("Not paired — open the Pairing tab first.")
        return
    }

    onStart("Reading .pkg file…")
    scope.launch {
        try {
            // Step 1: Copy the picked file to a local cache file (so we can
            // use RandomAccessFile + seek). The picked URI may be a content://
            // URI that we can only read via ContentResolver.
            val sendId = System.currentTimeMillis()
            val pkgCacheFile = java.io.File(context.cacheDir, "${item.id}_$sendId.pkg")
            WepWawetLogger.workshop("pick-local-pkg: copying picked file to cache",
                "uri" to uri.toString(),
                "cache_path" to pkgCacheFile.absolutePath)

            val bytesCopied = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    pkgCacheFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var total = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            total += read
                        }
                        total
                    }
                } ?: 0L
            }

            if (bytesCopied == 0L) {
                WepWawetLogger.workshopWarn("pick-local-pkg: failed to copy picked file",
                    "uri" to uri.toString(),
                    "bytes_copied" to bytesCopied)
                onEnd("Failed to read picked .pkg file (empty?)")
                return@launch
            }
            WepWawetLogger.workshop("pick-local-pkg: file copied",
                "bytes_copied" to bytesCopied,
                "cache_path" to pkgCacheFile.absolutePath)

            // Step 2: Pack the .pkg into a .mpkg
            val mpkgFile = java.io.File(context.cacheDir, "${item.id}_$sendId.mpkg")
            WepWawetLogger.workshop("pick-local-pkg: packing .mpkg from .pkg",
                "pkg_file" to pkgCacheFile.name,
                "pkg_size" to pkgCacheFile.length(),
                "mpkg_out" to mpkgFile.name)

            val mpkgSize = withContext(Dispatchers.IO) {
                com.wepwawet.pairing.MpkgPacker.packFromPkg(
                    item = item,
                    pkgFile = pkgCacheFile,
                    outputFile = mpkgFile,
                )
            }

            if (mpkgSize == 0L || !mpkgFile.exists()) {
                WepWawetLogger.workshopWarn("pick-local-pkg: packFromPkg failed",
                    "mpkg_exists" to mpkgFile.exists(),
                    "mpkg_size" to mpkgSize)
                onEnd("Failed to pack .pkg → .mpkg (see logs for details)")
                return@launch
            }
            WepWawetLogger.workshop("pick-local-pkg: .mpkg ready",
                "mpkg_file" to mpkgFile.name,
                "mpkg_size" to mpkgSize)

            // Step 3: Queue for transfer
            val deviceName = PairingService.pairedDeviceName ?: "phone"
            PairingService.queueWallpaper(mpkgFile, item.title)
            WepWawetLogger.workshop("pick-local-pkg: queued for transfer",
                "file" to mpkgFile.name,
                "size" to mpkgFile.length(),
                "title" to item.title.take(80),
                "device" to deviceName)
            onEnd("Queued for transfer to $deviceName. Transfer starts on next heartbeat (~3s).")

            // Clean up the .pkg cache file (the .mpkg is now in cache)
            pkgCacheFile.delete()
        } catch (e: Exception) {
            WepWawetLogger.workshopWarn("pick-local-pkg: unhandled exception",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            onEnd("Error: ${e.message}")
        }
    }
}

/**
 * Pick a local image file (JPEG/PNG) and pack as a Scene wallpaper.
 *
 * This is the FALLBACK path: when the user can't get the actual .pkg file,
 * they can pick any image and we'll pack it as a Scene wallpaper. The image
 * is encoded as a .tex (RGBA8888 format=0) and the WE Android app's native
 * Scene renderer will display it.
 *
 * @param uri the content:// URI returned by the file picker
 * @param onStart called when the operation begins (with a status message)
 * @param onEnd called when the operation ends (with a final status message)
 */
private fun onPickLocalImageClicked(
    context: Context,
    item: WorkshopItemTyped,
    scope: kotlinx.coroutines.CoroutineScope,
    uri: android.net.Uri,
    onStart: (String) -> Unit,
    onEnd: (String) -> Unit,
) {
    WepWawetLogger.workshop("pick-local-image tapped",
        "id" to item.id,
        "title" to item.title.take(80),
        "uri" to uri.toString(),
        "is_paired" to PairingService.isPaired())

    if (!PairingService.isPaired()) {
        WepWawetLogger.workshopWarn("pick-local-image: not paired, aborting",
            "id" to item.id)
        onEnd("Not paired — open the Pairing tab first.")
        return
    }

    onStart("Reading image and packing as Scene .mpkg…")
    scope.launch {
        try {
            // Copy picked image to cache
            val sendId = System.currentTimeMillis()
            val imgCacheFile = java.io.File(context.cacheDir, "${item.id}_$sendId.img")
            val bytesCopied = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    imgCacheFile.outputStream().use { output -> input.copyTo(output) }
                } ?: 0L
            }
            if (bytesCopied == 0L) {
                WepWawetLogger.workshopWarn("pick-local-image: failed to copy picked file",
                    "uri" to uri.toString())
                onEnd("Failed to read picked image (empty?)")
                return@launch
            }
            WepWawetLogger.workshop("pick-local-image: image copied",
                "bytes_copied" to bytesCopied,
                "cache_path" to imgCacheFile.absolutePath)

            // Pack as Scene .mpkg
            val mpkgFile = java.io.File(context.cacheDir, "${item.id}_$sendId.mpkg")
            WepWawetLogger.workshop("pick-local-image: packing Scene .mpkg",
                "image_file" to imgCacheFile.name,
                "image_size" to imgCacheFile.length(),
                "mpkg_out" to mpkgFile.name)
            val mpkgSize = withContext(Dispatchers.IO) {
                com.wepwawet.pairing.MpkgPacker.packSceneWallpaper(
                    item = item,
                    imageFile = imgCacheFile,
                    outputFile = mpkgFile,
                )
            }
            if (mpkgSize == 0L || !mpkgFile.exists()) {
                WepWawetLogger.workshopWarn("pick-local-image: packing failed",
                    "mpkg_exists" to mpkgFile.exists(),
                    "mpkg_size" to mpkgSize)
                onEnd("Failed to pack Scene .mpkg (see logs)")
                return@launch
            }
            WepWawetLogger.workshop("pick-local-image: .mpkg ready",
                "mpkg_file" to mpkgFile.name,
                "mpkg_size" to mpkgSize)

            // Queue for transfer
            val deviceName = PairingService.pairedDeviceName ?: "phone"
            PairingService.queueWallpaper(mpkgFile, item.title)
            WepWawetLogger.workshop("pick-local-image: queued for transfer",
                "file" to mpkgFile.name,
                "size" to mpkgFile.length(),
                "device" to deviceName)
            onEnd("Queued for transfer to $deviceName. Transfer starts on next heartbeat (~3s).")
            imgCacheFile.delete()
        } catch (e: Exception) {
            WepWawetLogger.workshopWarn("pick-local-image: unhandled exception",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            onEnd("Error: ${e.message}")
        }
    }
}

/**
 * Pick a local video file (MP4) and pack as a Video wallpaper.
 *
 * This is the FALLBACK path for video wallpapers: when the user can't get the
 * actual .pkg file, they can pick any MP4 file and we'll pack it as a Video
 * wallpaper. The WE Android app's VideoDrawable will play the MP4 via
 * MediaPlayer + MPKGVideoReader.
 *
 * @param uri the content:// URI returned by the file picker
 * @param onStart called when the operation begins (with a status message)
 * @param onEnd called when the operation ends (with a final status message)
 */
private fun onPickLocalVideoClicked(
    context: Context,
    item: WorkshopItemTyped,
    scope: kotlinx.coroutines.CoroutineScope,
    uri: android.net.Uri,
    onStart: (String) -> Unit,
    onEnd: (String) -> Unit,
) {
    WepWawetLogger.workshop("pick-local-video tapped",
        "id" to item.id,
        "title" to item.title.take(80),
        "uri" to uri.toString(),
        "is_paired" to PairingService.isPaired())

    if (!PairingService.isPaired()) {
        WepWawetLogger.workshopWarn("pick-local-video: not paired, aborting",
            "id" to item.id)
        onEnd("Not paired — open the Pairing tab first.")
        return
    }

    onStart("Reading video and packing as Video .mpkg…")
    scope.launch {
        try {
            // Copy picked video to cache
            val sendId = System.currentTimeMillis()
            val videoCacheFile = java.io.File(context.cacheDir, "${item.id}_$sendId.mp4")
            val bytesCopied = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    videoCacheFile.outputStream().use { output -> input.copyTo(output) }
                } ?: 0L
            }
            if (bytesCopied == 0L) {
                WepWawetLogger.workshopWarn("pick-local-video: failed to copy picked file",
                    "uri" to uri.toString())
                onEnd("Failed to read picked video (empty?)")
                return@launch
            }
            WepWawetLogger.workshop("pick-local-video: video copied",
                "bytes_copied" to bytesCopied,
                "cache_path" to videoCacheFile.absolutePath)

            // Pack as Video .mpkg
            val mpkgFile = java.io.File(context.cacheDir, "${item.id}_$sendId.mpkg")
            WepWawetLogger.workshop("pick-local-video: packing Video .mpkg",
                "video_file" to videoCacheFile.name,
                "video_size" to videoCacheFile.length(),
                "mpkg_out" to mpkgFile.name)
            val mpkgSize = withContext(Dispatchers.IO) {
                com.wepwawet.pairing.MpkgPacker.packVideoWallpaper(
                    item = item,
                    videoFile = videoCacheFile,
                    outputFile = mpkgFile,
                )
            }
            if (mpkgSize == 0L || !mpkgFile.exists()) {
                WepWawetLogger.workshopWarn("pick-local-video: packing failed",
                    "mpkg_exists" to mpkgFile.exists(),
                    "mpkg_size" to mpkgSize)
                onEnd("Failed to pack Video .mpkg (see logs)")
                return@launch
            }
            WepWawetLogger.workshop("pick-local-video: .mpkg ready",
                "mpkg_file" to mpkgFile.name,
                "mpkg_size" to mpkgSize)

            // Queue for transfer
            val deviceName = PairingService.pairedDeviceName ?: "phone"
            PairingService.queueWallpaper(mpkgFile, item.title)
            WepWawetLogger.workshop("pick-local-video: queued for transfer",
                "file" to mpkgFile.name,
                "size" to mpkgFile.length(),
                "device" to deviceName)
            onEnd("Queued for transfer to $deviceName. Transfer starts on next heartbeat (~3s).")
            videoCacheFile.delete()
        } catch (e: Exception) {
            WepWawetLogger.workshopWarn("pick-local-video: unhandled exception",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            onEnd("Error: ${e.message}")
        }
    }
}

/**
 * Debug download-.mpkg click handler. Generates the same .mpkg that
 * onSendToPhoneClicked would queue for transfer, but instead saves it
 * directly to the user's Downloads/WepWawet/ folder so they can inspect
 * the binary with `xxd`, `binwalk`, `strings`, etc.
 *
 * This is critical for debugging: if WE rejects the .mpkg, comparing our
 * output to a real .mpkg from a Windows PC will reveal exactly which bytes
 * are wrong (wrong magic, wrong project.json schema, missing fields, etc.).
 *
 * @param onStart called when the operation begins (with a status message)
 * @param onEnd called when the operation ends (with a final status message)
 */
private fun onDownloadMpkgClicked(
    context: Context,
    item: WorkshopItemTyped,
    scope: kotlinx.coroutines.CoroutineScope,
    onStart: (String) -> Unit,
    onEnd: (String) -> Unit,
) {
    WepWawetLogger.workshop("download-mpkg (debug) tapped",
        "id" to item.id,
        "title" to item.title.take(80))
    onStart("Preparing .mpkg for download…")
    scope.launch {
        try {
            // Step 1: Download the preview image
            val previewFile = downloadWallpaper(context, item) { }
            if (previewFile == null || !previewFile.exists()) {
                onEnd("Failed to download preview image.")
                return@launch
            }

            // Step 2: Pack the preview into a Scene-type .mpkg file in the cache dir
            val sendId = System.currentTimeMillis()
            val cacheMpkg = java.io.File(context.cacheDir, "${item.id}_$sendId.mpkg")
            com.wepwawet.pairing.MpkgPacker.packSceneWallpaper(
                item = item,
                imageFile = previewFile,
                outputFile = cacheMpkg,
            )
            if (!cacheMpkg.exists() || cacheMpkg.length() == 0L) {
                onEnd("Failed to create .mpkg file")
                return@launch
            }

            // Step 3: Copy the .mpkg to the public Downloads/WepWawet/ folder
            val bytes = cacheMpkg.readBytes()
            val downloadsName = "${item.id}_debug.mpkg"
            val uri = com.wepwawet.log.PublicDownloadsWriter.writeBytes(
                context, downloadsName, bytes)
            if (uri != null) {
                WepWawetLogger.workshop("download-mpkg: saved to Downloads",
                    "file" to downloadsName,
                    "size" to bytes.size,
                    "uri" to uri.toString())
                onEnd("Saved ${bytes.size} bytes to Downloads/WepWawet/$downloadsName")
            } else {
                onEnd("Failed to write .mpkg to Downloads (see logs)")
            }
        } catch (e: Exception) {
            WepWawetLogger.workshopWarn("download-mpkg: failed",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""))
            onEnd("Error: ${e.message}")
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ====== Side helper: download the preview image to a local cache ======

private val downloadHttpClient = OkHttpClient.Builder().build()

/**
 * Best-effort download of the workshop item's preview_url to the app's external
 * cache dir (`/sdcard/Android/data/com.wepwawet/cache/wallpapers/<id>.jpg`).
 *
 * NOTE: This downloads the *preview thumbnail*, not the actual wallpaper content
 * (which requires the Wine+Steam backend to fetch from Steam's CDN with auth).
 * For v1, we use the preview as a stand-in so the user gets visible feedback.
 *
 * @return the downloaded File, or `null` on failure
 */
private suspend fun downloadWallpaper(
    context: Context,
    item: WorkshopItemTyped,
    onProgress: (Float) -> Unit,
): File? = withContext(Dispatchers.IO) {
    val url = item.previewUrl ?: return@withContext null
    try {
        val outDir = File(context.externalCacheDir, "wallpapers").apply { mkdirs() }
        val outFile = File(outDir, "${item.id}.jpg")
        // If we've already downloaded it, just return it
        if (outFile.exists() && outFile.length() > 0) {
            onProgress(1f)
            WepWawetLogger.workshop("downloadWallpaper: cache hit",
                "file" to outFile.name, "size" to outFile.length())
            return@withContext outFile
        }

        val request = Request.Builder().url(url).build()
        val response = downloadHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            WepWawetLogger.workshopWarn("downloadWallpaper HTTP error",
                "code" to response.code, "url" to url.take(120))
            return@withContext null
        }
        val body = response.body ?: return@withContext null
        val totalBytes = body.contentLength().toFloat().coerceAtLeast(1f)
        body.byteStream().use { input ->
            outFile.outputStream().use { output ->
                val buffer = ByteArray(8 * 1024)
                var read: Int
                var downloaded = 0L
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    onProgress((downloaded / totalBytes).coerceIn(0f, 1f))
                }
            }
        }
        onProgress(1f)
        WepWawetLogger.workshop("downloadWallpaper done",
            "file" to outFile.name, "size" to outFile.length())
        outFile
    } catch (e: Exception) {
        WepWawetLogger.workshopWarn("downloadWallpaper failed",
            "exception" to e.javaClass.name, "message" to (e.message ?: ""))
        null
    }
}

/**
 * Guess the wallpaper resolution from the workshop tags.
 * Steam WE workshop items typically include resolution tags like "1920x1080" or "3840x2160".
 * Returns "Unknown" if no resolution tag is found.
 */
private fun guessResolution(item: WorkshopItemTyped): String {
    val pattern = Regex("(\\d{3,5})\\s*[xX×]\\s*(\\d{3,5})")
    item.tags.forEach { tag ->
        val m = pattern.find(tag)
        if (m != null) return "${m.groupValues[1]}x${m.groupValues[2]}"
    }
    return "Unknown"
}
