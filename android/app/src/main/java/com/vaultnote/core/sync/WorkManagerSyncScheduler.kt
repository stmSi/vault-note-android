package com.vaultnote.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.vaultnote.worker.VaultSyncWorker
import java.util.concurrent.TimeUnit

class WorkManagerSyncScheduler(context: Context) : SyncScheduler {
    private val applicationContext = context.applicationContext

    override fun requestSync(): SyncScheduleResult = enqueueSafely {
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<VaultSyncWorker>()
                .setConstraints(networkConstraints(requireBatteryNotLow = false))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .addTag(SYNC_WORK_TAG)
                .build(),
        )
    }

    override fun ensurePeriodicSync(): SyncScheduleResult = enqueueSafely {
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<VaultSyncWorker>(6L, TimeUnit.HOURS)
                .setConstraints(networkConstraints(requireBatteryNotLow = true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .addTag(SYNC_WORK_TAG)
                .build(),
        )
    }

    private fun enqueueSafely(enqueue: () -> Unit): SyncScheduleResult = try {
        enqueue()
        SyncScheduleResult.Scheduled
    } catch (_: IllegalStateException) {
        SyncScheduleResult.Rejected("work_manager_unavailable")
    } catch (_: RuntimeException) {
        SyncScheduleResult.Rejected("work_manager_enqueue_failed")
    }

    private fun networkConstraints(requireBatteryNotLow: Boolean): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(requireBatteryNotLow)
            .build()

    companion object {
        const val IMMEDIATE_WORK_NAME = "vaultnote-immediate-sync"
        const val PERIODIC_WORK_NAME = "vaultnote-periodic-sync"
        const val SYNC_WORK_TAG = "vaultnote-sync"
    }
}
