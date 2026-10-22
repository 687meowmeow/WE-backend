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

            // === Send to phone button (no download needed) ===
            item {
                Button(
                    onClick = {
                        WepWawetLogger.workshop("send-to-phone tapped",
                            "id" to current.id,
                            "is_paired" to PairingService.isPaired(),
                            "title" to current.title.take(80))
                        scope.launch {
                            isSending = true
                            statusMessage = null
                            try {
                                if (!PairingService.isPaired()) {
                                    statusMessage = "Not paired — open the Pairing tab first."
                                    WepWawetLogger.workshopWarn(
                                        "send-to-phone: not paired, aborting",
                                        "id" to current.id)
                                    return@launch
                                }

                                // Download the preview image and pack it as a .mpkg
                                // The WE Android app expects .mpkg files — we create a
                                // minimal .mpkg container with the preview as the file.
                                // This won't be a full wallpaper (we can't download the
                                // actual .pkg content without SteamCMD), but it tests
                                // the transfer pipeline.
                                statusMessage = "Preparing wallpaper for transfer…"
                                val previewFile = downloadWallpaper(context, current) { }
                                if (previewFile == null || !previewFile.exists()) {
                                    statusMessage = "Failed to download preview image."
                                    return@launch
                                }

                                // Create a minimal .mpkg file containing the preview
                                val mpkgFile = java.io.File(context.cacheDir, "${current.id}.mpkg")
                                try {
                                    val previewBytes = previewFile.readBytes()
                                    val projectName = "project.json"
                                    val projectJson = """{"file":"preview.jpg","title":"${current.title}","type":"Image","preview":"preview.jpg"}""".toByteArray()
                                    // Build .mpkg: header_marker(4) + magic(8) + count(4) + entries + data
                                    val out = java.io.ByteArrayOutputStream()
                                    out.write(byteArrayOf(8, 0, 0, 0)) // header_marker = 8
                                    out.write("PKGM0020".toByteArray()) // magic
                                    out.write(byteArrayOf(2, 0, 0, 0)) // entry_count = 2
                                    // Entry 0: project.json
                                    out.write(projectName.toByteArray().size) // name_len as 4-byte LE
                                    out.write(byteArrayOf(0, 0, 0))
                                    out.write(projectName.toByteArray())
                                    out.write(byteArrayOf(0, 0, 0, 0)) // offset = 0
                                    out.write(projectJson.size and 0xFF, (projectJson.size shr 8) and 0xFF, (projectJson.size shr 16) and 0xFF, (projectJson.size shr 24) and 0xFF) // size
                                    // Entry 1: preview.jpg
                                    val prevName = "preview.jpg"
                                    out.write(prevName.toByteArray().size)
                                    out.write(byteArrayOf(0, 0, 0))
                                    out.write(prevName.toByteArray())
                                    out.write(projectJson.size and 0xFF, (projectJson.size shr 8) and 0xFF, (projectJson.size shr 16) and 0xFF, (projectJson.size shr 24) and 0xFF) // offset
                                    out.write(previewBytes.size and 0xFF, (previewBytes.size shr 8) and 0xFF, (previewBytes.size shr 16) and 0xFF, (previewBytes.size shr 24) and 0xFF) // size
                                    // Data section
                                    out.write(projectJson)
                                    out.write(previewBytes)
                                    mpkgFile.writeBytes(out.toByteArray())
                                    WepWawetLogger.workshop("mpkg created",
                                        "file" to mpkgFile.name,
                                        "size" to mpkgFile.length())
                                } catch (e: Exception) {
                                    WepWawetLogger.workshopWarn("mpkg creation failed: ${e.message}")
                                    // Fallback: just send the preview file directly
                                    val renamed = java.io.File(context.cacheDir, "${current.id}.mpkg")
                                    previewFile.copyTo(renamed, overwrite = true)
                                    mpkgFile = renamed
                                }

                                PairingService.queueWallpaper(mpkgFile, current.title)
                                statusMessage = "Queued for transfer to " +
                                    (PairingService.pairedDeviceName ?: "phone")
                                WepWawetLogger.workshop("send-to-phone queued",
                                    "file" to mpkgFile.name,
                                    "size" to mpkgFile.length())
                            } finally {
                                isSending = false
                            }
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
                        else "Send to phone"
                    )
                }
            }

            // === Bottom spacer ===
            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

// ====== Composable sub-parts ======

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
