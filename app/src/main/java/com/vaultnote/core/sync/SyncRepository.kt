package com.vaultnote.core.sync

import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.VaultItemSummary
import kotlinx.coroutines.flow.Flow

data class SyncOverview(
    val pendingCount: Long,
    val runningCount: Long,
    val retryCount: Long,
    val failedCount: Long,
    val conflictCount: Int,
    val lastAttemptAtEpochMillis: Long?,
    val lastSuccessAtEpochMillis: Long?,
)

sealed interface SyncRunResult {
    data class Completed(val processedOperations: Int) : SyncRunResult
    data class RetryRequired(val processedOperations: Int) : SyncRunResult
    data object AuthenticationRequired : SyncRunResult
}

interface SyncRepository {
    fun observeOverview(): Flow<SyncOverview>

    fun observeConflicts(limit: Int = 100): Flow<List<VaultItemSummary>>

    suspend fun synchronize(maxOperations: Int = 32): SyncRunResult

    suspend fun resolveConflict(selectedItemId: String): RepositoryResult<Unit>
}
