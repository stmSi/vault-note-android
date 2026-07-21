package com.vaultnote.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.vaultnote.core.database.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(attachment: AttachmentEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(attachment: AttachmentEntity): Int

    @Query("SELECT * FROM attachments WHERE id = :attachmentId LIMIT 1")
    suspend fun getById(attachmentId: String): AttachmentEntity?

    @Query(
        """
        SELECT * FROM attachments
        WHERE parent_item_id = :itemId
        ORDER BY created_at ASC, id ASC
        """,
    )
    suspend fun getForItem(itemId: String): List<AttachmentEntity>

    @Query(
        """
        UPDATE attachments SET upload_status = :status, remote_path = :remotePath
        WHERE id = :attachmentId
        """,
    )
    suspend fun updateRemoteState(
        attachmentId: String,
        status: com.vaultnote.core.common.model.AttachmentUploadStatus,
        remotePath: String?,
    ): Int

    @Query("SELECT * FROM attachments WHERE id = :attachmentId LIMIT 1")
    fun observeById(attachmentId: String): Flow<AttachmentEntity?>

    @Query(
        """
        SELECT * FROM attachments
        WHERE encryption_format_version < :targetVersion
        ORDER BY created_at ASC, id ASC
        LIMIT :limit
        """,
    )
    suspend fun getLegacyEncryptionBatch(targetVersion: Int, limit: Int): List<AttachmentEntity>

    @Query(
        """
        UPDATE attachments SET encryption_format_version = :newVersion
        WHERE id = :attachmentId AND encryption_format_version = :expectedVersion
        """,
    )
    suspend fun updateEncryptionFormat(
        attachmentId: String,
        expectedVersion: Int,
        newVersion: Int,
    ): Int

    @Query(
        """
        SELECT * FROM attachments
        WHERE parent_item_id = :itemId AND sha256_checksum = :checksum
        ORDER BY created_at ASC, id ASC
        LIMIT 1
        """,
    )
    suspend fun findForItemByChecksum(itemId: String, checksum: String): AttachmentEntity?

    @Query(
        """
        SELECT COUNT(*) FROM attachments
        WHERE local_encrypted_path = :localRelativePath
           OR (:thumbnailRelativePath IS NOT NULL AND thumbnail_path = :thumbnailRelativePath)
        """,
    )
    suspend fun countPathReferences(
        localRelativePath: String,
        thumbnailRelativePath: String?,
    ): Int

    @Query(
        """
        SELECT group_concat(original_filename, char(10))
        FROM (
            SELECT original_filename FROM attachments
            WHERE parent_item_id = :itemId
            ORDER BY created_at ASC, id ASC
        )
        """,
    )
    suspend fun getSearchableFilenames(itemId: String): String?

    @Query(
        """
        SELECT group_concat(extracted_ocr_text, char(10))
        FROM (
            SELECT extracted_ocr_text FROM attachments
            WHERE parent_item_id = :itemId
              AND ocr_state = 'COMPLETE'
              AND extracted_ocr_text != ''
            ORDER BY created_at ASC, id ASC
        )
        """,
    )
    suspend fun getSearchableOcrText(itemId: String): String?

    @Query(
        """
        SELECT * FROM attachments
        WHERE encryption_format_version = :encryptionFormatVersion
          AND (
              ocr_state = 'PENDING'
              OR (ocr_state = 'PROCESSING' AND (ocr_updated_at IS NULL OR ocr_updated_at <= :staleBefore))
          )
        ORDER BY created_at ASC, id ASC
        LIMIT :limit
        """,
    )
    suspend fun getOcrCandidates(
        staleBefore: Long,
        encryptionFormatVersion: Int,
        limit: Int,
    ): List<AttachmentEntity>

    @Query(
        """
        UPDATE attachments
        SET ocr_state = 'PROCESSING',
            ocr_failure_code = NULL,
            ocr_updated_at = :now
        WHERE id = :attachmentId
          AND sha256_checksum = :sourceChecksum
          AND (
              ocr_state = 'PENDING'
              OR (ocr_state = 'PROCESSING' AND (ocr_updated_at IS NULL OR ocr_updated_at <= :staleBefore))
          )
        """,
    )
    suspend fun claimOcr(
        attachmentId: String,
        sourceChecksum: String,
        staleBefore: Long,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE attachments
        SET ocr_state = 'COMPLETE',
            extracted_ocr_text = :text,
            ocr_source_checksum = :sourceChecksum,
            ocr_failure_code = NULL,
            ocr_updated_at = :now
        WHERE id = :attachmentId
          AND sha256_checksum = :sourceChecksum
          AND ocr_state = 'PROCESSING'
        """,
    )
    suspend fun completeOcr(
        attachmentId: String,
        sourceChecksum: String,
        text: String,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE attachments
        SET ocr_state = 'FAILED',
            extracted_ocr_text = '',
            ocr_source_checksum = NULL,
            ocr_failure_code = :failureCode,
            ocr_updated_at = :now
        WHERE id = :attachmentId
          AND sha256_checksum = :sourceChecksum
          AND ocr_state = 'PROCESSING'
        """,
    )
    suspend fun failOcr(
        attachmentId: String,
        sourceChecksum: String,
        failureCode: String,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE attachments
        SET ocr_state = 'PENDING',
            extracted_ocr_text = '',
            ocr_source_checksum = NULL,
            ocr_failure_code = NULL,
            ocr_updated_at = NULL
        WHERE id = :attachmentId AND ocr_state = 'FAILED'
        """,
    )
    suspend fun retryOcr(attachmentId: String): Int

    @Query(
        """
        SELECT * FROM attachments
        WHERE parent_item_id = :itemId
        ORDER BY created_at ASC, id ASC
        """,
    )
    fun observeForItem(itemId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE sha256_checksum = :checksum ORDER BY created_at ASC")
    suspend fun findByChecksum(checksum: String): List<AttachmentEntity>

    @Query("DELETE FROM attachments WHERE id = :attachmentId")
    suspend fun deleteById(attachmentId: String): Int
}
