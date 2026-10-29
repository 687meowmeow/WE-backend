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

        // Take a snapshot (doesn't clear the ring buffer — the old drainTo() cleared
        // the buffer, which wiped the in-memory Logs screen every 15 minutes).
        val snapshot = WepWawetLogger.snapshot()
        // Only persist entries we haven't written yet (seq > lastPersistedSeq)
        val prefs = applicationContext.getSharedPreferences("wepwawet_log_persistence", android.content.Context.MODE_PRIVATE)
        val lastSeq = prefs.getLong("last_seq", 0L)
        val toWrite = snapshot.filter { it.seq > lastSeq }
        if (toWrite.isNotEmpty()) {
            val today = LocalDate.now(ZoneId.systemDefault())
            val dailyFile = File(logsDir, "wepwawet-${LOG_FMT.format(today)}.log")
            // Write each entry with an immediate flush so a process kill mid-batch
            // doesn't leave partial entries in the file.
            //
            // v0.7.4 fix (duplicate log lines after a crash mid-batch): the old
            // code wrote the whole batch first and persisted `last_seq` with a
            // single async apply() AFTER the loop. If the process was killed
            // after some entries were flushed but before the apply() landed on
            // disk, `last_seq` stayed at its previous value — the next worker
            // run re-filtered the same snapshot and re-appended the entries that
            // had already been written, producing duplicated lines in the log
            // file (the old comment claimed this couldn't happen; it could).
            //
            // Now the watermark is persisted SYNCHRONOUSLY (commit()) after
            // EACH entry is flushed. A kill can now only ever lose the entry
            // whose commit() hadn't finished — never duplicate a committed one.
            // The worker runs on a background thread, so the fsync cost per
            // entry (a few ms) never blocks the UI.
            java.io.FileWriter(dailyFile, true).use { fw ->
                toWrite.forEach { e ->
                    fw.write(e.format())
                    fw.write("\n")
                    fw.flush()
                    prefs.edit().putLong("last_seq", e.seq).commit()
                }
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
