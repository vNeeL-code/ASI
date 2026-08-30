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

            val now = java.time.ZonedDateTime.now()
            val nextNoon = now.withHour(12).withMinute(0).withSecond(0).withNano(0)
            val nextMidnight = (if (now.hour >= 12) now.plusDays(1) else now)
                .withHour(0).withMinute(0).withSecond(0).withNano(0)

            val nextTarget = when {
                now.isBefore(nextNoon) -> nextNoon
                now.isBefore(nextMidnight) -> nextMidnight
                else -> nextNoon.plusDays(1)
            }

            val initialDelayMs = java.time.Duration.between(now, nextTarget).toMillis().coerceAtLeast(0)

            val workRequest = PeriodicWorkRequestBuilder<DiaryWorker>(
                12, TimeUnit.HOURS,
                30, TimeUnit.MINUTES
            )
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            val mins = initialDelayMs / (1000 * 60)
            Timber.i("📔 DiaryWorker: Scheduled for 12-hour intervals targeting noon/midnight (next in $mins mins)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Timber.i("📔 DiaryWorker: Periodic work cancelled")
        }
    }
}
