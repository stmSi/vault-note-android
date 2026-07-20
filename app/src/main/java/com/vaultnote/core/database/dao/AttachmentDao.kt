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
