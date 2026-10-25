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
 *     workshop content download is handled separately via JavaSteam;
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
    var downloadProgress by remember { mutableStateOf(0f) }  // 0..1 for download progress bar
    var isDownloading by remember { mutableStateOf(false) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var isSending by remember { mutableStateOf(false) }
    var sendProgress by remember { mutableStateOf(0f) }  // progress for send-to-phone Steam download
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // Track whether the user has Steam credentials set up (for JavaSteam direct download)
    var hasSteamCredentials by remember { mutableStateOf(com.wepwawet.pairing.SteamCredentialsStore.hasCredentials(context)) }
    // Re-check whenever this screen is resumed
    LaunchedEffect(itemId) {
        hasSteamCredentials = com.wepwawet.pairing.SteamCredentialsStore.hasCredentials(context)
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

            // === Progress bar (shows during download + transfer) ===
            if (isSending) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(
                            progress = { sendProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            statusMessage ?: "Working...",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // === Send to phone button — downloads REAL .pkg via JavaSteam (no PC) ===
            item {
                Button(
                    onClick = {
                        if (!isSending) {
                            sendProgress = 0f
                            onSendToPhoneClicked(context, current, scope,
                                { isSending = true; statusMessage = it; sendProgress = 0f },
                                { isSending = false; statusMessage = it },
                                { progress -> sendProgress = progress },
                                { statusMessage = it })
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
                        else if (!hasSteamCredentials) "Send to phone (enter Steam login in Settings)"
                        else "Send to phone"
                    )
                }
            }

            // === Helper hint ===
            item {
                if (!hasSteamCredentials) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Steam login required",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                "To download the actual wallpaper content directly on your phone " +
                                "(no PC needed), enter your Steam username and password in Settings. " +
                                "Your Steam account must own Wallpaper Engine. Your credentials are " +
                                "stored only on this device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }

            // === Debug: Save .mpkg to Downloads — now downloads REAL .pkg via JavaSteam ===
            item {
                OutlinedButton(
                    onClick = {
                        if (!isSending) {
                            sendProgress = 0f
                            onDownloadMpkgClicked(context, current, scope,
                                { isSending = true; statusMessage = it; sendProgress = 0f },
                                { isSending = false; statusMessage = it },
                                { progress -> sendProgress = progress })
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
    onProgress: (Float) -> Unit,
    onStatusUpdate: (String) -> Unit = { _ -> },
) {
    WepWawetLogger.workshop("send-to-phone tapped",
        "id" to item.id,
        "is_paired" to PairingService.isPaired(),
        "title" to item.title.take(80),
        "tags" to item.tags.joinToString(","),
        "type" to item.type.displayName,
        "has_steam_credentials" to com.wepwawet.pairing.SteamCredentialsStore.hasCredentials(context))

    if (!PairingService.isPaired()) {
        val msg = "Not paired — open the Pairing tab first."
        WepWawetLogger.workshopWarn("send-to-phone: not paired, aborting", "id" to item.id)
        onEnd(msg)
        return
    }

    if (!com.wepwawet.pairing.SteamCredentialsStore.hasCredentials(context)) {
        val msg = "Steam login not set. Open Settings → Steam account → enter your Steam username and password."
        WepWawetLogger.workshopWarn("send-to-phone: no Steam credentials", "id" to item.id)
        onEnd(msg)
        return
    }

    val steamUser = com.wepwawet.pairing.SteamCredentialsStore.getUsername(context)!!
    val steamPass = com.wepwawet.pairing.SteamCredentialsStore.getPassword(context)!!
    val steamGuard = com.wepwawet.pairing.SteamCredentialsStore.getGuardCode(context)

    onStart("Downloading real .pkg via Steam (no PC needed, this may take 1-2 minutes)…")
    scope.launch {
        try {
            // Reset progress
            onProgress(0f)

            // === Step 1: Download the actual .pkg file via JavaSteam (direct on-device) ===
            WepWawetLogger.workshop("send-to-phone: step 1/4 downloading .pkg via JavaSteam",
                "workshop_id" to item.id,
                "steam_user" to steamUser,
                "has_guard_code" to !steamGuard.isNullOrBlank())

            val sendId = System.currentTimeMillis()
            val pkgFile = java.io.File(context.cacheDir, "${item.id}_$sendId.pkg")
            val downloader = com.wepwawet.pairing.WorkshopDownloader(steamUser, steamPass, steamGuard)
            val downloadedPkg = withContext(Dispatchers.IO) {
                downloader.downloadWorkshopPkg(
                    context = context,
                    workshopId = item.id,
                    outputFile = pkgFile,
                    onProgress = { pct ->
                        onProgress(pct * 0.8f)
                    },
                    onStatusUpdate = { status ->
                        WepWawetLogger.workshop("send-to-phone: JavaSteam status",
                            "status" to status)
                    },
                )
            }

            if (downloadedPkg == null || !downloadedPkg.exists() || downloadedPkg.length() == 0L) {
                WepWawetLogger.workshopWarn("send-to-phone: step 1/4 JavaSteam download failed",
                    "pkg_file" to pkgFile.absolutePath,
                    "exists" to pkgFile.exists(),
                    "size" to pkgFile.length())
                onEnd("Failed to download .pkg via Steam (see logs)")
                return@launch
            }
            WepWawetLogger.workshop("send-to-phone: step 1/4 .pkg downloaded",
                "file" to downloadedPkg.absolutePath,
                "size" to downloadedPkg.length(),
                "size_human" to formatBytes(downloadedPkg.length()))

            // === Step 2: Log file type for debugging (do NOT abort on unknown) ===
            // The PkgReader supports PKGV0001 through PKGV0024. The detectFileType
            // function below checks for ALL of these. Even if the file type is
            // "unknown", we proceed to step 3 — PkgReader.read() will throw
            // a descriptive exception if the file is truly invalid, and the
            // step 3 error handling will show it. This is better than aborting
            // prematurely with a generic "unexpected file type" error.
            val fileType = detectFileType(downloadedPkg)
            val firstBytes = runCatching {
                val raf = java.io.RandomAccessFile(downloadedPkg, "r")
                val buf = ByteArray(16)
                raf.readFully(buf)
                raf.close()
                buf.joinToString("") { "%02x".format(it) }
            }.getOrDefault("<unreadable>")
            WepWawetLogger.workshop("send-to-phone: step 2/4 file type detected",
                "file_type" to fileType,
                "file_name" to downloadedPkg.name,
                "file_size" to downloadedPkg.length(),
                "first_16_bytes_hex" to firstBytes)
            if (fileType == "unknown") {
                WepWawetLogger.workshopWarn("send-to-phone: step 2/4 unknown file type — proceeding anyway",
                    "first_16_bytes_hex" to firstBytes)
                onStatusUpdate("Downloaded .pkg has unknown format — attempting to pack anyway…")
            } else {
                onStatusUpdate("Downloaded $fileType .pkg (${formatBytes(downloadedPkg.length())}) — packing…")
            }

            // === Step 3: Pack the .pkg into a .mpkg ===
            WepWawetLogger.workshop("send-to-phone: step 3/4 packing .pkg → .mpkg",
                "pkg_file" to downloadedPkg.name,
                "pkg_size" to downloadedPkg.length())
            onStatusUpdate("Packing .pkg → .mpkg (${formatBytes(downloadedPkg.length())})…")
            onProgress(0.85f)
            val mpkgFile = java.io.File(context.cacheDir, "${item.id}_$sendId.mpkg")
            val mpkgSize = withContext(Dispatchers.IO) {
                com.wepwawet.pairing.MpkgPacker.packFromPkg(
                    item = item,
                    pkgFile = downloadedPkg,
                    outputFile = mpkgFile,
                )
            }
            if (mpkgSize == 0L || !mpkgFile.exists()) {
                WepWawetLogger.workshopWarn("send-to-phone: step 3/4 packing failed",
                    "mpkg_exists" to mpkgFile.exists(),
                    "mpkg_size" to mpkgSize,
                    "pkg_file_type" to fileType,
                    "pkg_first_16_bytes_hex" to firstBytes)
                onEnd("Packing failed — the downloaded .pkg may not be a valid Steam Workshop .pkg. Check logs for details (file_type=$fileType, first_bytes=$firstBytes).")
                return@launch
            }
            WepWawetLogger.workshop("send-to-phone: step 3/4 .mpkg ready",
                "mpkg_file" to mpkgFile.name,
                "mpkg_size" to mpkgSize,
                "size_human" to formatBytes(mpkgSize))
            onStatusUpdate("Packed .mpkg (${formatBytes(mpkgSize)}) — sending to phone…")

            // === Step 4: Queue for transfer ===
            WepWawetLogger.workshop("send-to-phone: step 4/4 queueing for transfer",
                "file" to mpkgFile.name,
                "size" to mpkgFile.length())
            onStatusUpdate("Sending to phone — queued for transfer…")
            onProgress(0.95f)
            val deviceName = PairingService.pairedDeviceName ?: "phone"
            PairingService.queueWallpaper(mpkgFile, item.title)
            onProgress(1f)
            WepWawetLogger.workshop("send-to-phone: QUEUED",
                "file" to mpkgFile.name,
                "size" to mpkgFile.length(),
                "title" to item.title.take(80),
                "device" to deviceName,
                "next_action" to "WePairingServer will pick this up on next heartbeat (~3s) and transfer via AES-encrypted chunks")
            onEnd("Queued for transfer to $deviceName (real .pkg content, ${formatBytes(mpkgFile.length())}). Transfer starts on next heartbeat (~3s).")

            // Clean up the .pkg cache file
            downloadedPkg.delete()
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
 * Returns "GIF", "JPEG", "PNG", "MP4", "PKGV0024", or "unknown".
 */
private fun detectFileType(file: java.io.File): String {
    try {
        val raf = java.io.RandomAccessFile(file, "r")
        // PKGV0024 is 8 bytes + a 4-byte length prefix = 12 bytes minimum
        // The .pkg format is: [u32 LE magic_len][magic bytes]...
        // For "PKGV0024" the magic_len field = 8 (4 bytes LE: 08 00 00 00)
        // followed by the 8 bytes "PKGV0024"
        val header = ByteArray(12)
        raf.readFully(header)
        raf.close()
        // Steam Workshop .pkg: starts with [u32 LE magic_len][magic bytes]
        // PkgReader supports PKGV0001 through PKGV0024. Check for ANY of them.
        // The magic_len field is always 8 (the string "PKGV00XX" is 8 bytes).
        if (header.size >= 12 &&
            header[0] == 0x08.toByte() && header[1] == 0x00.toByte() &&
            header[2] == 0x00.toByte() && header[3] == 0x00.toByte() &&
            header[4] == 'P'.code.toByte() && header[5] == 'K'.code.toByte() &&
            header[6] == 'G'.code.toByte() && header[7] == 'V'.code.toByte()) {
            // Extract the version string (bytes 8-11: "00XX")
            val verStr = String(header, 8, 4, Charsets.US_ASCII)
            return "PKGV$verStr"
        }
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
    onProgress: (Float) -> Unit,
) {
    WepWawetLogger.workshop("download-mpkg (debug) tapped",
        "id" to item.id,
        "title" to item.title.take(80))
    
    if (!com.wepwawet.pairing.SteamCredentialsStore.hasCredentials(context)) {
        onEnd("Steam login not set. Open Settings → Steam account first.")
        return
    }
    
    val steamUser = com.wepwawet.pairing.SteamCredentialsStore.getUsername(context)!!
    val steamPass = com.wepwawet.pairing.SteamCredentialsStore.getPassword(context)!!
    
    onStart("Downloading real .pkg via JavaSteam (debug)…")
    scope.launch {
        try {
            val sendId = System.currentTimeMillis()
            val pkgFile = java.io.File(context.cacheDir, "${item.id}_${sendId}_debug.pkg")
            val downloader = com.wepwawet.pairing.WorkshopDownloader(steamUser, steamPass)
            val downloadedPkg = withContext(Dispatchers.IO) {
                downloader.downloadWorkshopPkg(
                    context = context,
                    workshopId = item.id,
                    outputFile = pkgFile,
                    onProgress = { pct -> onProgress(pct * 0.8f) },
                    onStatusUpdate = { status ->
                        WepWawetLogger.workshop("download-mpkg: JavaSteam status", "status" to status)
                    },
                )
            }
            if (downloadedPkg == null || !downloadedPkg.exists() || downloadedPkg.length() == 0L) {
                onEnd("Failed to download .pkg via Steam (see logs)")
                return@launch
            }

            // Pack the .pkg into a .mpkg
            val cacheMpkg = java.io.File(context.cacheDir, "${item.id}_${sendId}_debug.mpkg")
            val mpkgSize = withContext(Dispatchers.IO) {
                com.wepwawet.pairing.MpkgPacker.packFromPkg(
                    item = item,
                    pkgFile = downloadedPkg,
                    outputFile = cacheMpkg,
                )
            }
            if (mpkgSize == 0L || !cacheMpkg.exists()) {
                onEnd("Failed to pack .pkg → .mpkg")
                return@launch
            }

            // Copy to public Downloads
            val bytes = cacheMpkg.readBytes()
            val downloadsName = "${item.id}_debug.mpkg"
            val uri = com.wepwawet.log.PublicDownloadsWriter.writeBytes(
                context, downloadsName, bytes)
            if (uri != null) {
                WepWawetLogger.workshop("download-mpkg: saved to Downloads",
                    "file" to downloadsName,
                    "size" to bytes.size,
                    "uri" to uri.toString())
                onEnd("Saved ${formatBytes(bytes.size.toLong())} to Downloads/WepWawet/$downloadsName")
            } else {
                onEnd("Failed to write .mpkg to Downloads (see logs)")
            }
            downloadedPkg.delete()
            cacheMpkg.delete()
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
 * (which is fetched separately via JavaSteam using the user's credentials).
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
