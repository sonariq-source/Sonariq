package com.music.sonariq.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.sonariq.data.DebugLog as Log
import com.music.sonariq.data.settings.AppSettings
import java.util.concurrent.TimeUnit

/**
 * The daily Smart Downloads refresh, run by WorkManager.
 *
 * Periodic rather than exact: the point is "the top tracks are on the
 * device", not "at 3am sharp". Constraints keep it off metered connections
 * when the user asked for Wi-Fi-only, and off a dying battery — a bulk
 * download is the wrong thing to start at 5%.
 *
 * Failures here are ordinary (no history yet, no space, offline) and are
 * answered with success, not a retry: retrying an empty history in fifteen
 * minutes learns nothing, and the next daily run is the retry. Only an
 * unexpected exception asks WorkManager to try again.
 */
class SmartDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!AppSettings.smartDownloadsEnabled.value) {
            Log.d(TAG, "smart downloads worker: feature off, nothing to do")
            return Result.success()
        }
        return try {
            val queued = SmartDownloads.refresh(applicationContext)
            Log.d(TAG, "smart downloads worker: queued $queued tracks")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "smart downloads worker failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "Sonariq"
        private const val WORK_NAME = "smart_downloads_refresh"

        /**
         * Constraints follow the settings: unmetered network when the
         * Wi-Fi-only switch is on (metered rather than literally Wi-Fi, the
         * same test downloads themselves make — a tethered hotspot is Wi-Fi
         * that costs money), any connection otherwise. Charging and a
         * non-low battery in both cases: this is a bulk job.
         */
        fun constraints(): Constraints {
            val network = if (AppSettings.smartDownloadsWifiOnly.value) {
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

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SmartDownloadWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints())
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            Log.d(TAG, "smart downloads: daily refresh scheduled")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "smart downloads: daily refresh cancelled")
        }
    }
}
