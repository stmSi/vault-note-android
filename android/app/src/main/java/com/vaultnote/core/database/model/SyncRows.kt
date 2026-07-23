package com.vaultnote.core.database.model

import androidx.room.ColumnInfo

data class SyncQueueStatusRow(
    @ColumnInfo(name = "pending_count") val pendingCount: Long,
    @ColumnInfo(name = "running_count") val runningCount: Long,
    @ColumnInfo(name = "retry_count") val retryCount: Long,
    @ColumnInfo(name = "failed_count") val failedCount: Long,
)
