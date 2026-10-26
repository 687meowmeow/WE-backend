package com.wepwawet.pairing

import android.content.Context
import com.wepwawet.WepWawetApplication
import com.wepwawet.data.model.WorkshopItemTyped
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Singleton holding the state of an in-flight (or last-completed) workshop
 * download. The state is exposed as a [StateFlow] so any Composable can
 * observe it — including after the user has navigated away from the screen
 * that originally started the download.
 *
 * The download itself is launched on [WepWawetApplication.appScope], which
 * survives Activity/Composable destruction. So the user can:
 *  - Start a download from the detail screen
 *  - Navigate back to the browse list
 *  - Come back later and the download will either still be running, or have
 *    finished and saved the .mpkg to Downloads.
 *
 * Only ONE download at a time is allowed — calling [launchDebugDownload] while
 * another is running will fail-fast with `isRunning = true`.
 */
object WorkshopDownloadJob {

    /** Snapshot of the current (or last) download's state. */
    data class State(
        val isRunning: Boolean = false,
        /** 0..1 — chunk-level progress reported by JavaSteam. May stay at 0
         *  for small downloads where the entire payload fits in one chunk. */
        val progress: Float = 0f,
        /** Human-readable status line (e.g. "Connected to Steam — authenticating..."). */
        val status: String = "",
        /** Workshop item id this download is for. */
        val workshopId: String? = null,
        /** Workshop item title — for the UI to show. */
        val workshopTitle: String? = null,
        /** Set when the download succeeded — final user-facing message. */
        val resultMessage: String? = null,
        /** Set when the download failed — final user-facing error message. */
        val errorMessage: String? = null,
        /** Epoch ms when the download started (for elapsed-time UI). */
        val startedAt: Long = 0L,
        /** Epoch ms when the download ended (success or failure). */
        val endedAt: Long = 0L,
        /** True after at least one chunk-level progress callback has fired —
         *  used by the UI to decide between determinate vs indeterminate bar. */
        val hasChunkProgress: Boolean = false,
        /** Final saved file path (success only) — informational. */
        val savedFilePath: String? = null,
        /** Final saved file size in bytes (success only). */
        val savedFileSize: Long = 0L,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    // === v0.5.9-alpha (R8 fix): mirror state into DownloadManager so the
    //     Downloads screen + WallpaperCard "Downloading…" overlay + "✓" badge
    //     actually populate. Previously (v0.5.0-v0.5.8) WallpaperDetailScreen
    //     used inline scope.launch coroutines and NEVER called
    //     DownloadManager.start/complete/fail — the Downloads top-bar icon
    //     navigated to an empty screen. Now that the screen delegates to
    //     launchSendToPhone/launchDebugDownload (v0.4.14 pattern, restored
    //     in R1 fix), we wire DownloadManager at these strategic points.
    //     Progress mirroring is intentionally minimal (start/complete/fail/
    //     cancel only) — the granular progress bar is already shown on the
    //     detail screen via state.collectAsState(); the Downloads screen just
    //     needs to know WHICH items are in flight vs done vs failed. ===
    private fun onDownloadStarted(item: WorkshopItemTyped) {
        com.wepwawet.data.DownloadManager.startDownload(item)
    }

    private fun onDownloadCompleted(item: WorkshopItemTyped, fileSizeBytes: Long, deviceName: String) {
        com.wepwawet.data.DownloadManager.completeDownload(item.id, fileSizeBytes, deviceName)
    }

    private fun onDownloadFailed(item: WorkshopItemTyped, errorMessage: String) {
        com.wepwawet.data.DownloadManager.failDownload(item.id, errorMessage)
    }

    private fun onDownloadCancelled(itemId: String?) {
        if (itemId != null) {
            com.wepwawet.data.DownloadManager.cancelDownload(itemId)
        }
    }

    /**
     * Start (or queue) the "Save .mpkg to Downloads (debug)" download.
     *
     * Returns `false` if another download is already running (caller should
     * show a "download in progress" toast or just ignore the tap).
     *
     * @param context app/context (used for cache dir, Steam credentials)
     * @param item workshop item to download
     * @return true if a new download was started
     */
    fun launchDebugDownload(
        context: Context,
        item: WorkshopItemTyped,
    ): Boolean {
        val current = _state.value
        if (current.isRunning) {
            WepWawetLogger.workshop("WorkshopDownloadJob: launch rejected — already running",
                "current_id" to (current.workshopId ?: "null"),
                "requested_id" to item.id)
            return false
        }

        val appContext = context.applicationContext
        val steamUser = SteamCredentialsStore.getUsername(appContext)
        val steamPass = SteamCredentialsStore.getPassword(appContext)
        if (steamUser == null || steamPass == null) {
            _state.update { it.copy(
                errorMessage = "Steam login not set. Open Settings → Steam account first.",
                endedAt = System.currentTimeMillis(),
            ) }
            return false
        }

        val sendId = System.currentTimeMillis()
        val pkgFile = File(appContext.cacheDir, "${item.id}_${sendId}_debug.pkg")
        val downloader = WorkshopDownloader(steamUser, steamPass)

        _state.update { it.copy(
            isRunning = true,
            progress = 0f,
            status = "Downloading real .pkg via JavaSteam (debug)…",
            workshopId = item.id,
            workshopTitle = item.title,
            resultMessage = null,
            errorMessage = null,
            startedAt = System.currentTimeMillis(),
            endedAt = 0L,
            hasChunkProgress = false,
            savedFilePath = null,
            savedFileSize = 0L,
        ) }
        // v0.5.9-alpha (R8): mirror the start into DownloadManager so the
        // Downloads screen + WallpaperCard "Downloading…" overlay populate.
        onDownloadStarted(item)

        WepWawetLogger.workshop("WorkshopDownloadJob: launching debug download",
            "id" to item.id,
            "title" to item.title.take(80),
            "pkg_cache_path" to pkgFile.absolutePath)

        job = WepWawetApplication.appScope.launchSafely {
            var downloaderRef: WorkshopDownloader? = downloader
            try {
                WepWawetLogger.workshop("WorkshopDownloadJob: coroutine STARTED",
                    "id" to item.id,
                    "thread" to Thread.currentThread().name)
            val downloadedPkg = downloader.downloadWorkshopPkg(
                context = appContext,
                workshopId = item.id,
                outputFile = pkgFile,
                onProgress = { pct ->
                    val coerced = pct.coerceIn(0f, 1f)
                    _state.update { s -> s.copy(
                        progress = coerced,
                        hasChunkProgress = true,
                    ) }
                    // Log every 10% increment so we can see progress in logs
                    // (not every callback, which would be very spammy for 6MB
                    // files arriving in many small chunks).
                    val pctInt = (coerced * 100).toInt()
                    if (pctInt % 10 == 0) {
                        WepWawetLogger.workshop("WorkshopDownloadJob: progress",
                            "pct" to pctInt,
                            "raw" to pct)
                    }
                },
                onStatusUpdate = { status ->
                    _state.update { s -> s.copy(status = status) }
                    WepWawetLogger.workshop("WorkshopDownloadJob: status update",
                        "status" to status.take(150))
                },
            )

            WepWawetLogger.workshop("WorkshopDownloadJob: downloadWorkshopPkg RETURNED",
                "id" to item.id,
                "result_null" to (downloadedPkg == null),
                "result_exists" to (downloadedPkg?.exists() ?: false),
                "result_size" to (downloadedPkg?.length() ?: -1L),
                "output_path" to (downloadedPkg?.absolutePath ?: "null"))

            if (downloadedPkg == null || !downloadedPkg.exists() || downloadedPkg.length() == 0L) {
                val err = "Failed to download .pkg via Steam (see logs)"
                WepWawetLogger.workshopWarn("WorkshopDownloadJob: pkg download failed/null",
                    "id" to item.id)
                _state.update { s -> s.copy(
                    isRunning = false,
                    status = err,
                    errorMessage = err,
                    endedAt = System.currentTimeMillis(),
                ) }
                onDownloadFailed(item, err)
                return@launchSafely
            }

            _state.update { s -> s.copy(status = "Packing content → .mpkg…", progress = 0.85f) }
            val cacheMpkg = File(appContext.cacheDir, "${item.id}_${sendId}_debug.mpkg")
            val installDir = File(appContext.cacheDir, "workshop_downloads/${item.id}")
            // === v0.4.9: Detect video via MAGIC BYTES, not file extension.
            // The downloaded file is always named *.pkg by WorkshopDownloader
            // (the outputFile parameter is ${item.id}_${sendId}_debug.pkg), so
            // checking the extension .mp4 NEVER works. Instead, check the
            // file's magic bytes:
            //   - MP4 files have "ftyp" at offset 4
            //   - PKGV (.pkg) files have "PKGV" at offset 4
            val isVideo = detectVideoByMagicBytes(downloadedPkg)
            WepWawetLogger.workshop("WorkshopDownloadJob: packing content → .mpkg",
                "input_file" to downloadedPkg.absolutePath,
                "input_size" to downloadedPkg.length(),
                "mpkg_output" to cacheMpkg.absolutePath,
                "is_video" to isVideo)
            val mpkgSize = runCatching {
                if (isVideo) {
                    WepWawetLogger.workshop("WorkshopDownloadJob: calling packVideoWallpaper",
                        "video_file" to downloadedPkg.name,
                        "video_size" to downloadedPkg.length())
                    // For video, look for preview.jpg in install dir
                    val previewFile = File(installDir, "preview.jpg")
                        .takeIf { it.exists() }
                        ?: File(installDir, "preview.gif")
                            .takeIf { it.exists() }
                    com.wepwawet.pairing.MpkgPacker.packVideoWallpaper(
                        item = item,
                        videoFile = downloadedPkg,
                        outputFile = cacheMpkg,
                        previewFile = previewFile,
                    )
                } else {
                    WepWawetLogger.workshop("WorkshopDownloadJob: calling packFromPkg",
                        "pkg_file" to downloadedPkg.name,
                        "pkg_size" to downloadedPkg.length())
                    com.wepwawet.pairing.MpkgPacker.packFromPkg(
                        item = item,
                        pkgFile = downloadedPkg,
                        outputFile = cacheMpkg,
                        installDir = installDir,
                    )
                }
            }.getOrElse { e ->
                val err = "Failed to pack → .mpkg: ${e.message}"
                WepWawetLogger.workshopWarn("WorkshopDownloadJob: pack failed",
                    "exception" to e.javaClass.name, "message" to (e.message ?: ""),
                    "is_video" to isVideo)
                _state.update { s -> s.copy(
                    isRunning = false,
                    status = err,
                    errorMessage = err,
                    endedAt = System.currentTimeMillis(),
                ) }
                onDownloadFailed(item, err)
                runCatching { downloadedPkg.delete() }
                return@launchSafely
            }

            WepWawetLogger.workshop("WorkshopDownloadJob: pack done",
                "mpkg_output_size" to mpkgSize,
                "mpkg_output_exists" to cacheMpkg.exists(),
                "is_video" to isVideo)

            if (mpkgSize == 0L || !cacheMpkg.exists()) {
                val err = "Failed to pack .pkg → .mpkg (empty output)"
                WepWawetLogger.workshopWarn("WorkshopDownloadJob: empty mpkg output",
                    "size" to mpkgSize,
                    "exists" to cacheMpkg.exists(),
                    "pkg_input_size" to downloadedPkg.length())
                _state.update { s -> s.copy(
                    isRunning = false,
                    status = err,
                    errorMessage = err,
                    endedAt = System.currentTimeMillis(),
                ) }
                onDownloadFailed(item, err)
                runCatching { downloadedPkg.delete() }
                return@launchSafely
            }

            _state.update { s -> s.copy(status = "Saving to Downloads…", progress = 0.95f) }
            WepWawetLogger.workshop("WorkshopDownloadJob: reading mpkg bytes",
                "file" to cacheMpkg.absolutePath,
                "expected_size" to mpkgSize)
            val bytes = runCatching { cacheMpkg.readBytes() }.getOrElse { e ->
                val err = "Failed to read packed .mpkg: ${e.message}"
                WepWawetLogger.workshopWarn("WorkshopDownloadJob: read failed",
                    "exception" to e.javaClass.name, "message" to (e.message ?: ""))
                _state.update { s -> s.copy(
                    isRunning = false,
                    status = err,
                    errorMessage = err,
                    endedAt = System.currentTimeMillis(),
                ) }
                onDownloadFailed(item, err)
                runCatching { downloadedPkg.delete(); cacheMpkg.delete() }
                return@launchSafely
            }
            WepWawetLogger.workshop("WorkshopDownloadJob: read mpkg bytes done",
                "bytes_read" to bytes.size)

            val downloadsName = "${item.id}_debug.mpkg"
            WepWawetLogger.workshop("WorkshopDownloadJob: writing to Downloads",
                "filename" to downloadsName,
                "bytes" to bytes.size,
                "target_dir" to "Download/WepWawet")
            val uri = com.wepwawet.log.PublicDownloadsWriter.writeBytes(
                appContext, downloadsName, bytes)
            WepWawetLogger.workshop("WorkshopDownloadJob: PublicDownloadsWriter returned",
                "uri_null" to (uri == null),
                "uri" to (uri?.toString() ?: "null"))
            runCatching { downloadedPkg.delete() }
            runCatching { cacheMpkg.delete() }
            // === Delete install directory (was deferred from WorkshopDownloader
            // so packFromPkg/packVideoWallpaper could read standalone files like
            // project.json and preview.jpg from it) ===
            runCatching { installDir.deleteRecursively() }
            WepWawetLogger.workshop("WorkshopDownloadJob: install dir cleaned up",
                "path" to installDir.absolutePath)

            if (uri != null) {
                val msg = "Saved ${formatBytes(bytes.size.toLong())} to Downloads/WepWawet/$downloadsName"
                WepWawetLogger.workshop("WorkshopDownloadJob: saved to Downloads",
                    "file" to downloadsName,
                    "size" to bytes.size,
                    "uri" to uri.toString())
                _state.update { s -> s.copy(
                    isRunning = false,
                    progress = 1f,
                    status = msg,
                    resultMessage = msg,
                    endedAt = System.currentTimeMillis(),
                    savedFilePath = uri.toString(),
                    savedFileSize = bytes.size.toLong(),
                ) }
                onDownloadCompleted(item, bytes.size.toLong(), "Downloads")
            } else {
                val err = "Failed to write .mpkg to Downloads (see logs)"
                _state.update { s -> s.copy(
                    isRunning = false,
                    status = err,
                    errorMessage = err,
                    endedAt = System.currentTimeMillis(),
                ) }
                onDownloadFailed(item, err)
            }
            } finally {
                // === CRITICAL (v0.4.7): ALWAYS call cleanup() on the WorkshopDownloader,
                // even on success — otherwise the SteamClient's TCP connection to Steam
                // stays alive, blocking subsequent download attempts (Steam rejects
                // the second login with the same loginID from the same IP → AccessDenied).
                // ===
                WepWawetLogger.workshop("WorkshopDownloadJob: cleaning up SteamClient",
                    "id" to item.id)
                runCatching { downloaderRef?.cleanup() }
                downloaderRef = null
            }
        }
        return true
    }

    /**
     * Start "Send to phone" — produces the EXACT same .mpkg as [launchDebugDownload]
     * (same installDir handling, same MpkgPacker.packFromPkg call signature) and queues
     * it for transfer to the paired phone via [PairingService.queueWallpaper].
     *
     * === CRITICAL (v0.4.7): Previously, send-to-phone used a SEPARATE code path
     * in `WallpaperDetailScreen.onSendToPhoneClicked` that did NOT pass `installDir`
     * to `MpkgPacker.packFromPkg`. The debug download DID pass installDir, so the
     * two flows produced DIFFERENT .mpkg files — debug worked, send-to-phone
     * produced a black wallpaper on the phone (missing project.json + preview.jpg
     * standalone files that the WE Android app's renderer needs).
     *
     * Now: send-to-phone uses the SAME code path as debug download (this method),
     * just with a different final step (queue for transfer vs. save to Downloads).
     * The .mpkg byte content is identical, so the rendering is identical.
     *
     * User explicitly said: "send to phone should work smoothly without compromises.
     * it should make the same thing the debug download makes and sends it perfectly."
     *
     * NOTE: We do NOT save a backup .mpkg to Downloads (the user said "saving it
     * downloads defeats the point thats what debug download works" — the debug
     * download button exists separately for that purpose).
     *
     * @param context app/context (used for cache dir, Steam credentials)
     * @param item workshop item to download + send
     * @return true if a new download was started
     */
    fun launchSendToPhone(
        context: Context,
        item: WorkshopItemTyped,
    ): Boolean {
        val current = _state.value
        if (current.isRunning) {
            WepWawetLogger.workshop("WorkshopDownloadJob: launch rejected — already running",
                "current_id" to (current.workshopId ?: "null"),
                "requested_id" to item.id)
            return false
        }

        val appContext = context.applicationContext
        val steamUser = SteamCredentialsStore.getUsername(appContext)
        val steamPass = SteamCredentialsStore.getPassword(appContext)
        if (steamUser == null || steamPass == null) {
            _state.update { it.copy(
                errorMessage = "Steam login not set. Open Settings → Steam account first.",
                endedAt = System.currentTimeMillis(),
            ) }
            return false
        }
        if (!PairingService.isPaired()) {
            _state.update { it.copy(
                errorMessage = "Not paired — open the Pairing tab first.",
                endedAt = System.currentTimeMillis(),
            ) }
            return false
        }

        val sendId = System.currentTimeMillis()
        val pkgFile = File(appContext.cacheDir, "${item.id}_${sendId}_send.pkg")
        val downloader = WorkshopDownloader(steamUser, steamPass,
            SteamCredentialsStore.getGuardCode(appContext))

        _state.update { it.copy(
            isRunning = true,
            progress = 0f,
            status = "Sending to phone — downloading real .pkg via JavaSteam…",
            workshopId = item.id,
            workshopTitle = item.title,
            resultMessage = null,
            errorMessage = null,
            startedAt = System.currentTimeMillis(),
            endedAt = 0L,
            hasChunkProgress = false,
            savedFilePath = null,
            savedFileSize = 0L,
        ) }
        // v0.5.9-alpha (R8): mirror the start into DownloadManager so the
        // Downloads screen + WallpaperCard "Downloading…" overlay populate.
        onDownloadStarted(item)

        WepWawetLogger.workshop("WorkshopDownloadJob: launching send-to-phone",
            "id" to item.id,
            "title" to item.title.take(80),
            "pkg_cache_path" to pkgFile.absolutePath,
            "paired_device" to (PairingService.pairedDeviceName ?: "null"))

        job = WepWawetApplication.appScope.launchSafely {
            var downloaderRef: WorkshopDownloader? = downloader
            try {
                WepWawetLogger.workshop("WorkshopDownloadJob.send: coroutine STARTED",
                    "id" to item.id,
                    "thread" to Thread.currentThread().name)
                val downloadedPkg = downloader.downloadWorkshopPkg(
                    context = appContext,
                    workshopId = item.id,
                    outputFile = pkgFile,
                    onProgress = { pct ->
                        val coerced = pct.coerceIn(0f, 1f)
                        _state.update { s -> s.copy(
                            progress = (coerced * 0.8f),
                            hasChunkProgress = true,
                        ) }
                        val pctInt = (coerced * 100).toInt()
                        if (pctInt % 10 == 0) {
                            WepWawetLogger.workshop("WorkshopDownloadJob.send: progress",
                                "pct" to pctInt)
                        }
                    },
                    onStatusUpdate = { status ->
                        _state.update { s -> s.copy(status = status) }
                        WepWawetLogger.workshop("WorkshopDownloadJob.send: status",
                            "status" to status.take(150))
                    },
                )

                WepWawetLogger.workshop("WorkshopDownloadJob.send: downloadWorkshopPkg RETURNED",
                    "id" to item.id,
                    "result_null" to (downloadedPkg == null),
                    "result_size" to (downloadedPkg?.length() ?: -1L))

                if (downloadedPkg == null || !downloadedPkg.exists() || downloadedPkg.length() == 0L) {
                    val err = "Failed to download .pkg via Steam (see logs)"
                    WepWawetLogger.workshopWarn("WorkshopDownloadJob.send: pkg download failed/null",
                        "id" to item.id)
                    _state.update { s -> s.copy(
                        isRunning = false,
                        status = err,
                        errorMessage = err,
                        endedAt = System.currentTimeMillis(),
                    ) }
                    onDownloadFailed(item, err)
                    return@launchSafely
                }

                // === Pack to .mpkg using SAME code path as debug download
                // (with installDir!) so the resulting .mpkg is byte-equivalent
                // to what debug download produces. The installDir contains
                // project.json + preview.jpg standalone files that the WE
                // Android app's renderer needs — without them, the wallpaper
                // renders as a black image.
                //
                // v0.4.9: Detect video via MAGIC BYTES (not extension — the
                // outputFile is always .pkg-named by WorkshopDownloader).
                //   - MP4 files have "ftyp" at offset 4
                //   - PKGV (.pkg) files have "PKGV" at offset 4
                // ===
                _state.update { s -> s.copy(
                    status = "Packing content → .mpkg (same as debug download)…",
                    progress = 0.85f,
                ) }
                val cacheMpkg = File(appContext.cacheDir, "${item.id}_${sendId}_send.mpkg")
                val installDir = File(appContext.cacheDir, "workshop_downloads/${item.id}")
                val isVideo = detectVideoByMagicBytes(downloadedPkg)
                WepWawetLogger.workshop("WorkshopDownloadJob.send: packing content → .mpkg",
                    "input_file" to downloadedPkg.absolutePath,
                    "input_size" to downloadedPkg.length(),
                    "mpkg_output" to cacheMpkg.absolutePath,
                    "install_dir" to installDir.absolutePath,
                    "install_dir_exists" to installDir.exists(),
                    "is_video" to isVideo)
                val mpkgSize = runCatching {
                    if (isVideo) {
                        WepWawetLogger.workshop("WorkshopDownloadJob.send: calling packVideoWallpaper",
                            "video_file" to downloadedPkg.name,
                            "video_size" to downloadedPkg.length())
                        val previewFile = File(installDir, "preview.jpg")
                            .takeIf { it.exists() }
                            ?: File(installDir, "preview.gif")
                                .takeIf { it.exists() }
                        com.wepwawet.pairing.MpkgPacker.packVideoWallpaper(
                            item = item,
                            videoFile = downloadedPkg,
                            outputFile = cacheMpkg,
                            previewFile = previewFile,
                        )
                    } else {
                        WepWawetLogger.workshop("WorkshopDownloadJob.send: calling packFromPkg",
                            "pkg_file" to downloadedPkg.name,
                            "pkg_size" to downloadedPkg.length())
                        com.wepwawet.pairing.MpkgPacker.packFromPkg(
                            item = item,
                            pkgFile = downloadedPkg,
                            outputFile = cacheMpkg,
                            installDir = installDir,  // <-- SAME as debug download
                        )
                    }
                }.getOrElse { e ->
                    val err = "Failed to pack → .mpkg: ${e.message}"
                    WepWawetLogger.workshopWarn("WorkshopDownloadJob.send: pack failed",
                        "exception" to e.javaClass.name, "message" to (e.message ?: ""),
                        "is_video" to isVideo)
                    _state.update { s -> s.copy(
                        isRunning = false,
                        status = err,
                        errorMessage = err,
                        endedAt = System.currentTimeMillis(),
                    ) }
                    onDownloadFailed(item, err)
                    runCatching { downloadedPkg.delete() }
                    return@launchSafely
                }

                WepWawetLogger.workshop("WorkshopDownloadJob.send: pack done",
                    "mpkg_output_size" to mpkgSize,
                    "mpkg_output_exists" to cacheMpkg.exists(),
                    "is_video" to isVideo)

                if (mpkgSize == 0L || !cacheMpkg.exists()) {
                    val err = "Failed to pack .pkg → .mpkg (empty output)"
                    WepWawetLogger.workshopWarn("WorkshopDownloadJob.send: empty mpkg output",
                        "size" to mpkgSize, "exists" to cacheMpkg.exists())
                    _state.update { s -> s.copy(
                        isRunning = false,
                        status = err,
                        errorMessage = err,
                        endedAt = System.currentTimeMillis(),
                    ) }
                    onDownloadFailed(item, err)
                    runCatching { downloadedPkg.delete() }
                    return@launchSafely
                }

                // === Queue for transfer to phone ===
                _state.update { s -> s.copy(
                    status = "Queuing for transfer to phone…",
                    progress = 0.95f,
                ) }
                val deviceName = PairingService.pairedDeviceName ?: "phone"
                WepWawetLogger.workshop("WorkshopDownloadJob.send: queueing for transfer",
                    "file" to cacheMpkg.name,
                    "size" to cacheMpkg.length(),
                    "device" to deviceName)
                PairingService.queueWallpaper(cacheMpkg, item.title)

                // === IMPORTANT: do NOT delete cacheMpkg here! ===
                // The PairingService.queueWallpaper only holds a File reference,
                // not the bytes — the file must still exist when the WePairingServer
                // command loop picks it up on the next heartbeat (~3s) and
                // transfers it via AES-encrypted chunks. Deleting it here would
                // cause "file not readable" in queueWallpaper → silent transfer
                // failure → black wallpaper on phone.
                //
                // The cache file will be cleaned up:
                //   - When the next send-to-phone for the SAME item starts
                //     (cacheDir reuses the path prefix, so the old file is
                //     overwritten naturally on the next pack)
                //   - When Android's cache cleaner runs (cacheDir is auto-cleaned
                //     under memory pressure)
                //   - Manually by the user via "Clear cache" in Android Settings

                // Clean up the .pkg cache (no longer needed)
                runCatching { downloadedPkg.delete() }

                val msg = "Queued ${formatBytes(cacheMpkg.length())} for transfer to $deviceName"
                WepWawetLogger.workshop("WorkshopDownloadJob.send: QUEUED",
                    "file" to cacheMpkg.name,
                    "size" to cacheMpkg.length(),
                    "device" to deviceName)
                _state.update { s -> s.copy(
                    isRunning = false,
                    progress = 1f,
                    status = msg,
                    resultMessage = msg,
                    endedAt = System.currentTimeMillis(),
                    savedFilePath = cacheMpkg.absolutePath,
                    savedFileSize = cacheMpkg.length(),
                ) }
                onDownloadCompleted(item, cacheMpkg.length(), deviceName)
            } finally {
                // === CRITICAL (v0.4.7): ALWAYS call cleanup() on the WorkshopDownloader,
                // even on success — otherwise the SteamClient's TCP connection to Steam
                // stays alive, blocking subsequent download attempts (Steam rejects
                // the second login with the same loginID from the same IP → AccessDenied).
                // ===
                WepWawetLogger.workshop("WorkshopDownloadJob.send: cleaning up SteamClient",
                    "id" to item.id)
                runCatching { downloaderRef?.cleanup() }
                downloaderRef = null
            }
        }
        return true
    }

    fun cancel() {
        WepWawetLogger.workshop("WorkshopDownloadJob: cancelling",
            "id" to (_state.value.workshopId ?: "null"))
        // v0.5.9-alpha (R8): mirror the cancel into DownloadManager so the
        // Downloads screen "Cancel" button works (it calls WorkshopDownloadJob.cancel
        // via the DownloadManagerScreen → activeDownloads subscription).
        onDownloadCancelled(_state.value.workshopId)
        job?.cancel()
        job = null
        _state.update { s -> s.copy(
            isRunning = false,
            status = "Cancelled",
            errorMessage = "Cancelled",
            endedAt = System.currentTimeMillis(),
        ) }
    }

    /** Mark the current download as failed with the given message. Called by
     *  [launchSafely] if the coroutine throws — keeps the UI from hanging on
     *  "Running…" forever after a crash. */
    fun markCrashed(message: String) {
        WepWawetLogger.workshopWarn("WorkshopDownloadJob: coroutine crashed",
            "msg" to message)
        val crashedItemId = _state.value.workshopId
        _state.update { s -> s.copy(
            isRunning = false,
            status = message,
            errorMessage = message,
            endedAt = System.currentTimeMillis(),
        ) }
        // v0.5.9-alpha (R8): mirror the crash into DownloadManager so the
        // Downloads screen shows the failure instead of a stale "Downloading…".
        // Only fire if we have a workshopId (otherwise the crash happened
        // before any launch started, which shouldn't happen, but be safe).
        if (crashedItemId != null) {
            com.wepwawet.data.DownloadManager.failDownload(
                crashedItemId, "Coroutine crashed: $message")
        }
    }

    /** Reset to a clean idle state (called by UI when user dismisses the result). */
    fun reset() {
        _state.value = State()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}

/**
 * Detect whether a downloaded file is a video (MP4) or a scene (.pkg) by
 * checking its magic bytes.
 *
 * v0.4.9: Previously we checked the file EXTENSION (.mp4 vs .pkg), but
 * WorkshopDownloader always names the output file `*.pkg` regardless of
 * content type (the outputFile parameter is `${item.id}_${sendId}_send.pkg`).
 * So the extension check ALWAYS returned false for videos, causing
 * packFromPkg to be called on a .mp4 file, which failed with
 * "PkgReader implausible magic_len magic_len=469762048".
 *
 * Now we check the actual file content:
 *   - MP4/M4V/MOV files have "ftyp" box at offset 4 → bytes[4..7] = "ftyp"
 *   - PKGV (.pkg) files have "PKGV" at offset 4 → bytes[4..7] = "PKGV"
 *
 * @return true if the file is a video, false if it's a scene .pkg
 */
private fun detectVideoByMagicBytes(file: File): Boolean {
    if (!file.exists() || file.length() < 12) return false
    return try {
        val raf = java.io.RandomAccessFile(file, "r")
        val header = ByteArray(12)
        raf.readFully(header)
        raf.close()
        // MP4: bytes 4-7 are ASCII "ftyp"
        val isMp4 = header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
            header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
        // PKGV: bytes 4-7 are ASCII "PKGV"
        val isPkgv = header[4] == 'P'.code.toByte() && header[5] == 'K'.code.toByte() &&
            header[6] == 'G'.code.toByte() && header[7] == 'V'.code.toByte()
        WepWawetLogger.workshop("detectVideoByMagicBytes",
            "file" to file.name,
            "header_hex" to header.joinToString("") { "%02x".format(it) },
            "is_mp4" to isMp4,
            "is_pkgv" to isPkgv)
        isMp4
    } catch (e: Exception) {
        WepWawetLogger.workshopWarn("detectVideoByMagicBytes: exception",
            "exception" to e.javaClass.name,
            "message" to (e.message ?: ""))
        false
    }
}

/**
 * Launches a coroutine on this scope but captures any uncaught exception into
 * the [WorkshopDownloadJob] state (so a crash doesn't leave the UI showing
 * "Running…" forever).
 */
private fun CoroutineScope.launchSafely(block: suspend CoroutineScope.() -> Unit): Job =
    this.launch {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Coop-cancelled — WorkshopDownloadJob.cancel already updated state
            throw e
        } catch (e: Throwable) {
            WorkshopDownloadJob.markCrashed("Crashed: ${e.message ?: e.javaClass.name}")
        }
    }
