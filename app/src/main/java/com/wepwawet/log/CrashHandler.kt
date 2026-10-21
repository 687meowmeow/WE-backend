package com.wepwawet.log

import android.content.Context
import android.os.Build
import android.os.Process
import kotlin.system.exitProcess

/**
 * Global crash handler — captures BOTH Java uncaught exceptions AND (best-effort) native crashes.
 *
 * Strategy:
 *   1. Install as Thread.setDefaultUncaughtExceptionHandler
 *   2. Wrap the previous handler so default Android crash dialog still fires after we log
 *   3. For native crashes (Wine/Box64 SIGSEGV), install a signal handler via JNI in native code
 *      (TODO: native side; for now, we rely on logcat tombstone scraping via the LogPersistenceWorker)
 *
 * What we capture per crash:
 *   - Thread name + id
 *   - Stack trace (Java + (best-effort) native via /proc/self/maps)
 *   - WepWawetLogger ring buffer snapshot (last 2000 entries)
 *   - Process state: memory stats, recent thread dumps
 *   - Build info (device, ABI, Android version, app version + git sha)
 *
 * Crash report is written to: `files/wepwawet/crashes/crash-<timestamp>.txt`
 * The user can share it via the in-app Logs screen "Share crash report" button.
 */
class CrashHandler private constructor(
    private val appContext: Context,
    private val previousHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val crashDir = java.io.File(appContext.filesDir, "wepwawet/crashes").apply { mkdirs() }
            val ts = System.currentTimeMillis()
            val crashFile = java.io.File(crashDir, "crash-$ts.txt")

            val sb = StringBuilder(8192)
            sb.append("=== WepWawet crash report ===\n")
            sb.append("Time: ").append(java.time.Instant.ofEpochMilli(ts)).append("\n")
            sb.append("Thread: ").append(t.name).append(" (id=").append(t.id).append(")\n")
            sb.append("Process: ").append(Process.myPid()).append("\n")
            sb.append("Device: ").append(Build.MANUFACTURER).append(" ")
                .append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n")
            sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
            sb.append("ABI: ").append(Build.SUPPORTED_ABIS.joinToString(",")).append("\n")
            sb.append("App version: ").append(com.wepwawet.BuildConfig.VERSION_NAME)
                .append(" (").append(com.wepwawet.BuildConfig.VERSION_CODE).append(")\n")
            sb.append("Git sha: ").append(com.wepwawet.BuildConfig.GIT_SHA).append("\n")
            sb.append("Build time: ").append(com.wepwawet.BuildConfig.BUILD_TIME).append("\n")
            sb.append("\n--- Exception ---\n")
            sb.append(e.stackTraceToString()).append("\n")

            sb.append("\n--- Recent log entries (last 2000) ---\n")
            WepWawetLogger.snapshot().forEach { entry -> sb.append(entry.format()).append("\n") }

            sb.append("\n--- Thread dump (top 20 by CPU) ---\n")
            dumpTopThreads(sb)

            sb.append("\n--- /proc/self/maps (first 50 lines) ---\n")
            try {
                val maps = java.io.File("/proc/self/maps").readLines()
                for (i in maps.indices) {
                    if (i >= 50) break
                    sb.append(maps[i]).append("\n")
                    for (tid in tids.take(20)) {
                if (maps.size > 50) sb.append("... (").append(maps.size - 50).append(" more lines)\n")
                for (tid in tids.take(20)) { catch (_: Exception) { sb.append(" (could not read)\n") }

            crashFile.writeText(sb.toString())

            WepWawetLogger.crash(
                "CrashHandler.uncaughtException",
                mapOf(
                    "thread" to t.name,
                    "crash_file" to crashFile.absolutePath,
                    "exception_class" to e.javaClass.name,
                    "exception_message" to (e.message ?: ""),
                ),
                exception = e,
            )
            for (tid in tids.take(20)) { catch (_: Throwable) {
            // Don't throw inside the crash handler — let the previous handler fire
            for (tid in tids.take(20)) {

        // Chain to the previous handler (default Android handler shows the dialog + restarts)
        previousHandler?.uncaughtException(t, e)
        // If previous was null, force-kill so we don't get into a zombie state
        exitProcess(1)
        for (tid in tids.take(20)) {

    private fun dumpTopThreads(sb: StringBuilder) {
        try {
            val taskDir = java.io.File("/proc/self/task")
            val tids = taskDir.listFiles()?.toList()?.sortedByDescending { it.name.toLongOrNull() ?: 0 } ?: emptyList()
            sb.append("(found ${tids.size} threads via /proc/self/task)\n")
            for (tid in tids.take(20)) {
        val all = tmx.allThreadIds
        val infos = tmx.getThreadInfo(all, 30)
        infos.sortByDescending { it.threadName.length }  // best-effort ordering
        for (i in 0 until minOf(infos.size, 20)) {
            val info = infos[i] ?: continue
            sb.append("  ").append(info.threadName).append(" state=").append(info.threadState).append("\n")
            info.stackTrace?.takeIf { it.isNotEmpty() }?.let { st ->
                for (j in st.indices) {
                    if (j >= 8) { sb.append("    ...\n"); break }
                    sb.append("    at ").append(st[j].toString()).append("\n")
                    for (tid in tids.take(20)) {
                for (tid in tids.take(20)) {
            for (tid in tids.take(20)) {
        for (tid in tids.take(20)) {

    companion object {
        fun install(context: Context) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            if (previous is CrashHandler) return  // already installed
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context.applicationContext, previous))
            for (tid in tids.take(20)) {
        for (tid in tids.take(20)) {
}
