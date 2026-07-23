package com.vaultnote.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.vaultnote.core.common.model.SyncOperationState
import com.vaultnote.core.common.model.SyncOperationType

@Entity(
    tableName = "sync_operations",
    indices = [
        Index(value = ["dedupe_key"], name = "index_sync_operations_dedupe_key", unique = true),
        Index(value = ["item_id"], name = "index_sync_operations_item_id"),
        Index(value = ["attachment_id"], name = "index_sync_operations_attachment_id"),
        Index(
            value = ["state", "next_attempt_at"],
            name = "index_sync_operations_state_next_attempt_at",
        ),
    ],
)
data class SyncOperationEntity(
    @PrimaryKey
    @ColumnInfo(name = "operation_id")
    val operationId: String,
    @ColumnInfo(name = "dedupe_key")
    val dedupeKey: String,
    @ColumnInfo(name = "item_id")
    val itemId: String?,
    @ColumnInfo(name = "attachment_id")
    val attachmentId: String?,
    @ColumnInfo(name = "operation_type")
    val operationType: SyncOperationType,
    @ColumnInfo(name = "target_revision")
    val targetRevision: Long,
    @ColumnInfo(name = "state")
    val state: SyncOperationState,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,
    @ColumnInfo(name = "next_attempt_at")
    val nextAttemptAt: Long,
    @ColumnInfo(name = "lease_token")
    val leaseToken: String?,
    @ColumnInfo(name = "lease_expires_at")
    val leaseExpiresAt: Long?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String?,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "scope")
    val scope: String,
    @ColumnInfo(name = "incremental_cursor")
    val incrementalCursor: String?,
    @ColumnInfo(name = "last_success_at")
    val lastSuccessAt: Long?,
    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Long?,
    @ColumnInfo(name = "server_revision")
    val serverRevision: Long?,
)
