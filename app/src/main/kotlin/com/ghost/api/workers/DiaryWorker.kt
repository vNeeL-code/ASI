package com.ghost.api.workers

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ghost.api.Constants
import com.ghost.api.GemmaService
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.TimeUnit

class DiaryWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Timber.i("📔 DiaryWorker: doWork started")
        
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(Constants.PREF_AUTONOMOUS_DIARY, true)) {
            Timber.i("📔 DiaryWorker skipped: Disabled by user settings toggle")
            return Result.success()
        }

        val service = GemmaService.instance
        if (service == null) {
            Timber.w("📔 DiaryWorker skipped: GemmaService is not active")
            return Result.success()
        }

        return try {
            val success = withTimeoutOrNull(5 * 60 * 1000L) {
                service.runDiaryCycleSuspend()
            }
            
            if (success == true) {
                Timber.i("📔 DiaryWorker: doWork successful")
                Result.success()
            } else {
                Timber.w("📔 DiaryWorker: doWork completed without new entry")
                Result.success()
            }
        } catch (e: Exception) {
            Timber.e(e, "📔 DiaryWorker: doWork threw an exception")
            Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "com.ghost.api.DIARY_PERIODIC_WORK"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<DiaryWorker>(
                4, TimeUnit.HOURS,
                30, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
            Timber.i("📔 DiaryWorker: Periodic work scheduled every 4 hours")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Timber.i("📔 DiaryWorker: Periodic work cancelled")
        }
    }
}
