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
    fun observeForItem(itemId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE sha256_checksum = :checksum ORDER BY created_at ASC")
    suspend fun findByChecksum(checksum: String): List<AttachmentEntity>

    @Query("DELETE FROM attachments WHERE id = :attachmentId")
    suspend fun deleteById(attachmentId: String): Int
}
