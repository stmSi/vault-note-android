package com.vaultnote.core.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.vaultnote.worker.AppUpdateCheckWorker
import java.util.concurrent.TimeUnit

class WorkManagerAppUpdateScheduler(context: Context) : AppUpdateScheduler {
    private val applicationContext = context.applicationContext

    override fun setAutomaticChecksEnabled(enabled: Boolean): AppUpdateScheduleResult =
        try {
            val workManager = WorkManager.getInstance(applicationContext)
            if (!enabled) {
                workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            } else {
                workManager.enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<AppUpdateCheckWorker>(24L, TimeUnit.HOURS)
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .setRequiresBatteryNotLow(true)
                                .build(),
                        )
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                        .addTag(WORK_TAG)
                        .build(),
                )
            }
            AppUpdateScheduleResult.SCHEDULED
        } catch (_: IllegalStateException) {
            AppUpdateScheduleResult.REJECTED
        } catch (_: RuntimeException) {
            AppUpdateScheduleResult.REJECTED
        }

    companion object {
        const val PERIODIC_WORK_NAME = "vaultnote-app-update-check"
        const val WORK_TAG = "vaultnote-app-update"
    }
}
