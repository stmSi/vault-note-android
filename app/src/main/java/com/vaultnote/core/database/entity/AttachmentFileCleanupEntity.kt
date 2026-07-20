package com.vaultnote.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable intent to remove attachment files that are not backed by committed attachment metadata.
 * Paths are relative and must still be confined by AttachmentFileManager before use.
 */
@Entity(
    tableName = "attachment_file_cleanup_journal",
    indices = [
        Index(value = ["created_at"], name = "index_attachment_file_cleanup_journal_created_at"),
    ],
)
data class AttachmentFileCleanupEntity(
    @PrimaryKey
    @ColumnInfo(name = "cleanup_id")
    val cleanupId: String,
    @ColumnInfo(name = "local_relative_path")
    val localRelativePath: String,
    @ColumnInfo(name = "thumbnail_relative_path")
    val thumbnailRelativePath: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,
    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Long?,
)
