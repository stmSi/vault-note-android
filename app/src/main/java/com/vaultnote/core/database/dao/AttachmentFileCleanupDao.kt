package com.vaultnote.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vaultnote.core.database.entity.AttachmentFileCleanupEntity

@Dao
interface AttachmentFileCleanupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: AttachmentFileCleanupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<AttachmentFileCleanupEntity>)

    @Query(
        "SELECT * FROM attachment_file_cleanup_journal WHERE cleanup_id = :cleanupId LIMIT 1",
    )
    suspend fun getByCleanupId(cleanupId: String): AttachmentFileCleanupEntity?

    @Query("SELECT COUNT(*) FROM attachment_file_cleanup_journal")
    suspend fun count(): Int

    @Query(
        """
        SELECT * FROM attachment_file_cleanup_journal
        ORDER BY (last_attempt_at IS NOT NULL) ASC,
                 last_attempt_at ASC,
                 created_at ASC,
                 cleanup_id ASC
        LIMIT :limit
        """,
    )
    suspend fun getOldest(limit: Int): List<AttachmentFileCleanupEntity>

    @Query(
        """
        UPDATE attachment_file_cleanup_journal SET
            attempt_count = CASE
                WHEN attempt_count < 2147483647 THEN attempt_count + 1
                ELSE attempt_count
            END,
            last_attempt_at = :attemptedAt
        WHERE cleanup_id = :cleanupId
        """,
    )
    suspend fun recordAttempt(cleanupId: String, attemptedAt: Long): Int

    @Query("DELETE FROM attachment_file_cleanup_journal WHERE cleanup_id = :cleanupId")
    suspend fun deleteByCleanupId(cleanupId: String): Int

    @Query("DELETE FROM attachment_file_cleanup_journal WHERE cleanup_id IN (:cleanupIds)")
    suspend fun deleteByCleanupIds(cleanupIds: List<String>): Int
}
