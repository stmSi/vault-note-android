package com.vaultnote.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vaultnote.app.appContainer
import com.vaultnote.core.sync.SyncRunResult
import java.util.concurrent.CancellationException

class VaultSyncWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = try {
        when (val result = applicationContext.appContainer().syncRepository.synchronize()) {
            is SyncRunResult.Completed -> Result.success(
                workDataOf(KEY_PROCESSED_OPERATIONS to result.processedOperations),
            )
            is SyncRunResult.RetryRequired -> Result.retry()
            SyncRunResult.AuthenticationRequired -> Result.failure(
                workDataOf(KEY_FAILURE_CODE to FAILURE_AUTHENTICATION),
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        if (runAttemptCount >= MAX_UNEXPECTED_ATTEMPTS - 1) {
            Result.failure(workDataOf(KEY_FAILURE_CODE to FAILURE_INTERNAL))
        } else {
            Result.retry()
        }
    }

    private companion object {
        const val KEY_PROCESSED_OPERATIONS = "processed_operations"
        const val KEY_FAILURE_CODE = "failure_code"
        const val FAILURE_AUTHENTICATION = "authentication_expired"
        const val FAILURE_INTERNAL = "internal_sync_failure"
        const val MAX_UNEXPECTED_ATTEMPTS = 10
    }
}
