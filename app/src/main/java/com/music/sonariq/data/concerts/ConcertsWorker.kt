package com.music.sonariq.data.concerts

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
 * The daily concert-alerts check, run by WorkManager.
 *
 * A failed check is ordinary (bad key, no network, no listening history) and
 * is answered with success, not a retry: retrying in fifteen minutes learns
 * nothing, and the next daily run is the retry. Only an unexpected exception
 * asks WorkManager to try again.
 */
class ConcertsWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!AppSettings.concertAlertsEnabled.value ||
            AppSettings.ticketmasterApiKey.value.isBlank()
        ) {
            Log.d(TAG, "concerts worker: feature off or no key, nothing to do")
            return Result.success()
        }
        return try {
            val fresh = ConcertsRepository.refresh(applicationContext)
            Log.d(TAG, "concerts worker: $fresh new shows")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "concerts worker failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "Sonariq"
        private const val WORK_NAME = "concert_alerts_refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ConcertsWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
