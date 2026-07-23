package com.vaultnote.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.vaultnote.core.common.model.SyncOperationState
import com.vaultnote.core.common.model.SyncOperationType
import com.vaultnote.core.database.entity.SyncOperationEntity
import com.vaultnote.core.database.model.SyncQueueStatusRow
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncOperationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(operation: SyncOperationEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(operation: SyncOperationEntity): Int

    @Query("SELECT * FROM sync_operations WHERE operation_id = :operationId LIMIT 1")
    suspend fun getById(operationId: String): SyncOperationEntity?

    @Query("SELECT * FROM sync_operations WHERE dedupe_key = :dedupeKey LIMIT 1")
    suspend fun getByDedupeKey(dedupeKey: String): SyncOperationEntity?

    @Query(
        """
        UPDATE sync_operations SET
            operation_id = :newOperationId,
            item_id = :itemId,
            attachment_id = :attachmentId,
            operation_type = :operationType,
            target_revision = :targetRevision,
            state = :state,
            attempt_count = 0,
            next_attempt_at = :now,
            lease_token = NULL,
            lease_expires_at = NULL,
            created_at = :now,
            updated_at = :now,
            last_error_code = NULL
        WHERE dedupe_key = :dedupeKey
        """,
    )
    suspend fun rotateAndRefresh(
        dedupeKey: String,
        newOperationId: String,
        itemId: String?,
        attachmentId: String?,
        operationType: SyncOperationType,
        targetRevision: Long,
        state: SyncOperationState,
        now: Long,
    ): Int

    @Query(
        """
        SELECT * FROM sync_operations
        WHERE state IN (:states) AND next_attempt_at <= :now
        ORDER BY created_at ASC, operation_id ASC
        LIMIT :limit
        """,
    )
    suspend fun getReadyOperations(
        states: List<SyncOperationState>,
        now: Long,
        limit: Int,
    ): List<SyncOperationEntity>

    @Query(
        """
        UPDATE sync_operations SET
            state = 'RETRY_WAIT',
            lease_token = NULL,
            lease_expires_at = NULL,
            next_attempt_at = :now,
            updated_at = :now,
            last_error_code = 'worker_interrupted'
        WHERE state = 'RUNNING' AND lease_expires_at IS NOT NULL AND lease_expires_at <= :now
        """,
    )
    suspend fun recoverExpiredLeases(now: Long): Int

    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN state = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending_count,
            COALESCE(SUM(CASE WHEN state = 'RUNNING' THEN 1 ELSE 0 END), 0) AS running_count,
            COALESCE(SUM(CASE WHEN state = 'RETRY_WAIT' THEN 1 ELSE 0 END), 0) AS retry_count,
            COALESCE(SUM(CASE WHEN state = 'FAILED_PERMANENT' THEN 1 ELSE 0 END), 0) AS failed_count
        FROM sync_operations
        WHERE state != 'COMPLETED'
        """,
    )
    fun observeQueueStatus(): Flow<SyncQueueStatusRow>

    @Query(
        """
        SELECT COUNT(*) FROM sync_operations
        WHERE item_id = :itemId AND state != 'COMPLETED'
        """,
    )
    suspend fun countOutstandingForItem(itemId: String): Long

    @Query(
        """
        UPDATE sync_operations SET
            state = :state,
            attempt_count = :attemptCount,
            next_attempt_at = :nextAttemptAt,
            lease_token = :leaseToken,
            lease_expires_at = :leaseExpiresAt,
            updated_at = :updatedAt,
            last_error_code = :lastErrorCode
        WHERE operation_id = :operationId
        """,
    )
    suspend fun updateAttemptState(
        operationId: String,
        state: SyncOperationState,
        attemptCount: Int,
        nextAttemptAt: Long,
        leaseToken: String?,
        leaseExpiresAt: Long?,
        updatedAt: Long,
        lastErrorCode: String?,
    ): Int

    @Query("SELECT COUNT(*) FROM sync_operations WHERE state != :completedState")
    fun observeOutstandingCount(completedState: SyncOperationState): Flow<Long>

    @Query("DELETE FROM sync_operations WHERE operation_id = :operationId")
    suspend fun deleteById(operationId: String): Int

    @Query("DELETE FROM sync_operations WHERE item_id = :itemId")
    suspend fun deleteForItem(itemId: String): Int

    @Query(
        """
        DELETE FROM sync_operations
        WHERE item_id = :itemId AND operation_type IN ('UPSERT_ITEM', 'DELETE_ITEM')
        """,
    )
    suspend fun deleteItemMetadataOperations(itemId: String): Int
}
