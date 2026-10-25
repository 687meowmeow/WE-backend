package com.wepwawet

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.wepwawet.log.CrashHandler
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * WepWawet Application entry point.
 *
 * Responsibilities (each step is independently try-caught so one failure does
 * not block the rest — this is critical because if any of these throw, the
 * app would never finish starting and the user would see "crashes on boot
 * without logs being made"):
 *
 *  1. Install the global CrashHandler FIRST so we capture any subsequent crash
 *  2. Write a "boot breadcrumb" to Downloads/WepWawet/boot.txt so the user
 *     can verify the app actually started onCreate — if even this fails, we
 *     know the crash is in the Application.attachBaseContext or class loading
 *     (which we can't catch here).
 *  3. Initialize the WepWawetLogger ring buffer + WorkManager job
 *  4. Create the foreground service notification channels
 *  5. Log a startup-complete event with build info
 *
 * Every step writes to logcat (Log.i / Log.e) as a fallback if file IO fails,
 * so even if our logger can't initialize we still have a breadcrumb in logcat
 * that the user can capture via `adb logcat` or bugreport.
 */
class WepWawetApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    /**
     * v0.5.0: Custom Coil ImageLoader with GIF playback + disk cache.
     *
     * - GIF decoder: enables animated GIF preview thumbnails (Steam preview URLs
     *   are often .gif files — without this, only the first frame is shown).
     * - Disk cache: 100 MB persistent cache in app cache dir → faster re-loads.
     * - Memory cache: 25% of available app memory → fast scrolling.
     * - Crossfade: 200ms fade-in animation for smoother image loading.
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                // GIF decoder for Android 9.0+ (ImageDecoder-based, API 28+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                }
                // GIF decoder for all versions (movie-based fallback)
                add(GifDecoder.Factory())
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100 MB
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // 25% of available memory
                    .build()
            }
            .crossfade(true)
            .crossfade(200) // 200ms fade-in
            .build()
    }

    override fun onCreate() {
        // Step 1: Install crash handler FIRST so we never miss a crash from the rest of onCreate.
        // This must succeed even if the rest of onCreate fails — wrap in try-catch and log
        // via logcat (the only always-available sink at this point).
        runCatching {
            CrashHandler.install(this)
        }.onFailure { t ->
            Log.e(TAG, "CrashHandler.install failed", t)
        }

        super.onCreate()

        // Step 2: Write a boot breadcrumb to public Downloads/WepWawet/boot.txt
        // so the user can verify the app actually reached onCreate — if even
        // this file is missing, the crash is happening BEFORE Application.onCreate
        // (which means class-loading failure, manifest merge issue, or native
        // init crash — none of which the Java CrashHandler can capture).
        runCatching {
            writeBootBreadcrumb()
        }.onFailure { t ->
            Log.e(TAG, "writeBootBreadcrumb failed", t)
        }

        // Step 3: Initialize the logger. If this fails, we can't use WepWawetLogger
        // for the rest of onCreate — fall back to logcat.
        var loggerOk = false
        runCatching {
            WepWawetLogger.init(this)
            loggerOk = true
        }.onFailure { t ->
            Log.e(TAG, "WepWawetLogger.init failed", t)
        }

        // Step 4: Create notification channels for foreground services.
        // Required for Android 8.0+ — without a channel, foreground service
        // notifications silently fail to display, and the system kills the
        // service after 5 seconds for not showing a notification.
        runCatching {
            createNotificationChannels()
        }.onFailure { t ->
            if (loggerOk) WepWawetLogger.appError("createNotificationChannels failed",
                exception = t)
            else Log.e(TAG, "createNotificationChannels failed", t)
        }

        // Step 5: Log a startup-complete event with build info.
        if (loggerOk) {
            runCatching {
                WepWawetLogger.appInfo("WepWawetApplication.onCreate complete",
                    "app_version" to BuildConfig.VERSION_NAME,
                    "git_sha" to BuildConfig.GIT_SHA,
                    "build_time" to BuildConfig.BUILD_TIME,
                    "device_model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "android_version" to Build.VERSION.SDK_INT.toString(),
                    "abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
                )
            }
        } else {
            // Logger failed — write directly to logcat as a last resort
            Log.i(TAG, "onCreate complete (logger failed — logcat only): " +
                "version=${BuildConfig.VERSION_NAME} sha=${BuildConfig.GIT_SHA}")
        }

        // Final breadcrumb: confirm we got to the end of onCreate
        runCatching {
            appendToBootBreadcrumb("onCreate complete\n")
        }

        // Initialize the application-scoped coroutine supervisor. This survives
        // Activity/Fragment/Composable destruction — used by WorkshopDownloadJob
        // so that downloads continue even if the user navigates away from the
        // detail screen while a download is in flight.
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        WepWawetLogger.appInfo("appScope initialized")
    }

    /**
     * Write a boot breadcrumb to Downloads/WepWawet/boot.txt. This proves the
     * app actually reached Application.onCreate() — if this file is missing,
     * the crash is happening BEFORE the Application class even runs (class
     * loading failure, manifest merge issue, or native init crash).
     */
    private fun writeBootBreadcrumb() {
        val ts = System.currentTimeMillis()
        val sb = StringBuilder(2048)
        sb.append("=== WepWawet boot ===\n")
        sb.append("Time: ").append(java.time.Instant.ofEpochMilli(ts)).append("\n")
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ")
            .append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n")
        sb.append("Android: ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("ABI: ").append(Build.SUPPORTED_ABIS.joinToString(",")).append("\n")
        runCatching {
            sb.append("App version: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            sb.append("Git sha: ").append(BuildConfig.GIT_SHA).append("\n")
            sb.append("Build time: ").append(BuildConfig.BUILD_TIME).append("\n")
        }
        sb.append("--- onCreate starting ---\n")

        val content = sb.toString()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ — MediaStore.Downloads collection (no permission)
            runCatching {
                val resolver = contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "boot.txt")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/WepWawet")
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                // Try to find an existing boot.txt and overwrite; if not found, insert
                val existing = findBootFile(collection)
                val uri: Uri = if (existing != null) {
                    // Overwrite by writing a fresh row with the same display name
                    // (we delete + re-insert to avoid IS_PENDING update issues on
                    // legacy Android 10 implementations)
                    runCatching { resolver.delete(existing, null, null) }
                    resolver.insert(collection, values) ?: return@runCatching
                } else {
                    resolver.insert(collection, values) ?: return@runCatching
                }
                resolver.openOutputStream(uri, "w")?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }
        } else {
            // Android 9 and below — legacy File API to public Downloads
            @Suppress("DEPRECATION")
            runCatching {
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dir = File(downloads, "WepWawet").apply { mkdirs() }
                File(dir, "boot.txt").writeText(content)
            }
        }
    }

    private fun appendToBootBreadcrumb(extra: String) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val existing = findBootFile(collection) ?: return@runCatching
                resolver.openOutputStream(existing, "wa")?.use { out ->
                    out.write(extra.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } else {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloads, "WepWawet/boot.txt")
                if (file.exists()) {
                    file.appendText(extra)
                }
            }
        }
    }

    private fun findBootFile(collection: Uri): Uri? {
        val resolver = contentResolver
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val args = arrayOf("boot.txt")
        resolver.query(collection, projection, selection, args, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        return null
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROXY,
                getString(R.string.notif_proxy_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_proxy_channel_desc)
                setShowBadge(false)
            }
        )
    }

    /**
     * WorkManager Configuration — uses our explicit min-shred-logging initializer
     * (we don't want WorkManager's default to spam logcat).
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    companion object {
        private const val TAG = "WepWawet-App"
        const val CHANNEL_PROXY = "wepwawet.proxy"

        /**
         * App-scoped CoroutineScope. Initialized in onCreate. Use for
         * background work that must outlive any Activity/Composable
         * (e.g. WorkshopDownloadJob launches downloads here so the user
         * can navigate away without cancelling the download).
         */
        lateinit var appScope: CoroutineScope
            private set
    }
}
