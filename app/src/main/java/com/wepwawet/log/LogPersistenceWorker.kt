package com.wepwawet.log

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * WorkManager job that flushes the WepWawetLogger ring buffer to a daily file in
 * app-internal storage: `files/wepwawet/logs/wepwawet-YYYYMMDD.log`
 *
 * Scheduled by WepWawetLogger.init via WorkManager with:
 *   - 15-minute periodic interval (the minimum allowed)
 *   - KEEP existing work policy (so re-init on app start doesn't double-schedule)
 *   - Constraints: not low-battery (so we don't burn cycles when the user needs them)
 *
 * Pruning: any file older than 14 days is deleted.
 *
 * If the ring buffer is empty, the worker still runs (cheap no-op) — this is intentional,
 * we want the worker active so the pruning logic fires regularly.
 */
class LogPersistenceWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val logsDir = File(applicationContext.filesDir, "wepwawet/logs").apply { mkdirs() }

        // Drain
        val drained = mutableListOf<LogEntry>()
        val count = WepWawetLogger.drainTo(drained)
        if (count > 0) {
            val today = LocalDate.now(ZoneId.systemDefault())
            val dailyFile = File(logsDir, "wepwawet-${LOG_FMT.format(today)}.log")
            dailyFile.bufferedWriter(Charsets.UTF_8, 8192).use { w ->
                drained.forEach { e -> w.write(e.format()); w.newLine() }
            }
        }

        // Prune
        val cutoffMs = System.currentTimeMillis() - (PRUNE_DAYS.toLong() * 24L * 60L * 60L * 1000L)
        logsDir.listFiles { f ->
            f.isFile && f.name.matches(Regex("wepwawet-\\d{4}-\\d{2}-\\d{2}\\.log"))
        }?.forEach { f ->
            if (f.lastModified() < cutoffMs) f.delete()
        }

        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "wepwawet_log_persistence"
        const val PRUNE_DAYS = 14
        val LOG_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        fun schedule(context: Context) {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            val request = androidx.work.PeriodicWorkRequestBuilder<LogPersistenceWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES
            ).setConstraints(constraints).build()
            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
