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

        WepWawetLogger.workshop("WorkshopDownloadJob: launching debug download",
            "id" to item.id,
            "title" to item.title.take(80),
            "pkg_cache_path" to pkgFile.absolutePath)

        job = WepWawetApplication.appScope.launchSafely {
            val downloadedPkg = downloader.downloadWorkshopPkg(
                context = appContext,
                workshopId = item.id,
                outputFile = pkgFile,
                onProgress = { pct ->
                    _state.update { s -> s.copy(
                        progress = pct.coerceIn(0f, 1f),
                        hasChunkProgress = true,
                    ) }
                },
                onStatusUpdate = { status ->
                    _state.update { s -> s.copy(status = status) }
                },
            )

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
                return@launchSafely
            }

            _state.update { s -> s.copy(status = "Packing .pkg → .mpkg…", progress = 0.85f) }
            val cacheMpkg = File(appContext.cacheDir, "${item.id}_${sendId}_debug.mpkg")
            val mpkgSize = runCatching {
                com.wepwawet.pairing.MpkgPacker.packFromPkg(
                    item = item,
                    pkgFile = downloadedPkg,
                    outputFile = cacheMpkg,
                )
            }.getOrElse { e ->
                val err = "Failed to pack .pkg → .mpkg: ${e.message}"
                WepWawetLogger.workshopWarn("WorkshopDownloadJob: pack failed",
                    "exception" to e.javaClass.name, "message" to (e.message ?: ""))
                _state.update { s -> s.copy(
                    isRunning = false,
                    status = err,
                    errorMessage = err,
                    endedAt = System.currentTimeMillis(),
                ) }
                runCatching { downloadedPkg.delete() }
                return@launchSafely
            }

            if (mpkgSize == 0L || !cacheMpkg.exists()) {
                val err = "Failed to pack .pkg → .mpkg (empty output)"
                _state.update { s -> s.copy(
                    isRunning = false,
                    status = err,
                    errorMessage = err,
                    endedAt = System.currentTimeMillis(),
                ) }
                runCatching { downloadedPkg.delete() }
                return@launchSafely
            }

            _state.update { s -> s.copy(status = "Saving to Downloads…", progress = 0.95f) }
            val bytes = runCatching { cacheMpkg.readBytes() }.getOrElse { e ->
                val err = "Failed to read packed .mpkg: ${e.message}"
                _state.update { s -> s.copy(
                    isRunning = false,
                    status = err,
                    errorMessage = err,
                    endedAt = System.currentTimeMillis(),
                ) }
                runCatching { downloadedPkg.delete(); cacheMpkg.delete() }
                return@launchSafely
            }

            val downloadsName = "${item.id}_debug.mpkg"
            val uri = com.wepwawet.log.PublicDownloadsWriter.writeBytes(
                appContext, downloadsName, bytes)
            runCatching { downloadedPkg.delete() }
            runCatching { cacheMpkg.delete() }

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
            } else {
                val err = "Failed to write .mpkg to Downloads (see logs)"
                _state.update { s -> s.copy(
                    isRunning = false,
                    status = err,
                    errorMessage = err,
                    endedAt = System.currentTimeMillis(),
                ) }
            }
        }
        return true
    }

    fun cancel() {
        WepWawetLogger.workshop("WorkshopDownloadJob: cancelling",
            "id" to (_state.value.workshopId ?: "null"))
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
        _state.update { s -> s.copy(
            isRunning = false,
            status = message,
            errorMessage = message,
            endedAt = System.currentTimeMillis(),
        ) }
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
