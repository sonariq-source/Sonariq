package com.music.sonariq.data.library

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.sonariq.data.DebugLog as Log
import com.music.sonariq.data.settings.AppSettings
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Downloads on a clock: the user names an hour, and the auto-download set
 * (liked songs plus chosen playlists — see [AutoDownloads]) is queued then,
 * e.g. overnight while the phone charges.
 *
 * WorkManager one-time work with an initial delay, rescheduled after every
 * run. Deliberately not an exact alarm: the point is "sometime overnight while
 * plugged in", and an exact alarm would need a permission and a wake lock for
 * a job that is perfectly happy to start at 2:47 instead of 2:30. The charging
 * constraint is what makes "overnight" true in practice.
 */
object ScheduledDownloads {

    private const val TAG = "Sonariq"
    const val WORK_NAME = "scheduled_downloads_overnight"

    /**
     * The next epoch-millis at which [hour]:[minute] occurs strictly after
     * [nowMs]. Pure, so the day-rollover is unit-testable.
     */
    fun nextRunMillis(hour: Int, minute: Int, nowMs: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= nowMs) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    /**
     * How long the worker waits from [nowMs] until the scheduled time, for
     * the delay on the work request. Never negative: a time that already
     * passed today means tomorrow.
     */
    fun delayUntilMillis(hour: Int, minute: Int, nowMs: Long): Long =
        (nextRunMillis(hour, minute, nowMs) - nowMs).coerceAtLeast(0L)

    fun schedule(context: Context) {
        val app = context.applicationContext
        val delay = delayUntilMillis(
            AppSettings.scheduledDownloadHour.value,
            AppSettings.scheduledDownloadMinute.value,
            System.currentTimeMillis(),
        )
        val request = OneTimeWorkRequestBuilder<ScheduledDownloadWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(constraints())
            .addTag(WORK_NAME)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        Log.d(TAG, "scheduled downloads: next run in ${delay / 60_000} min")
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        Log.d(TAG, "scheduled downloads: cancelled")
    }

    fun constraints(): Constraints {
        val network = if (AppSettings.scheduledDownloadWifiOnly.value) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }
        return Constraints.Builder()
            .setRequiredNetworkType(network)
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .build()
    }
}

/**
 * The overnight run: queue the auto-download set, then schedule the next
 * night. The reschedule happens even when the queue is empty — "nothing new
 * to download" is not a reason to stop checking tomorrow.
 */
class ScheduledDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            if (AppSettings.scheduledDownloadsEnabled.value) {
                val targets = AutoDownloads.collectTargets(applicationContext)
                AutoDownloads.enqueueAll(applicationContext, targets, from = "scheduled-downloads")
                Log.d(TAG, "scheduled downloads: queued ${targets.size} tracks")
            } else {
                Log.d(TAG, "scheduled downloads worker: feature off, not rescheduling")
                return Result.success()
            }
            // The next night, from now — so a run that slipped to 4am still
            // lands tomorrow night rather than drifting later each day.
            ScheduledDownloads.schedule(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "scheduled downloads worker failed: ${e.message}", e)
            // Still reschedule: one bad night must not kill the schedule.
            runCatching { ScheduledDownloads.schedule(applicationContext) }
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "Sonariq"
    }
}
