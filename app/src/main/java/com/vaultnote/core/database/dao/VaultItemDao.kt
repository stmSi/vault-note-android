package com.vaultnote.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.vaultnote.core.database.entity.VaultItemEntity
import com.vaultnote.core.database.model.VaultItemSummaryWithTags
import com.vaultnote.core.database.model.VaultItemWithTags
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultItemDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: VaultItemEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(item: VaultItemEntity): Int

    @Query("SELECT * FROM vault_items WHERE id = :itemId LIMIT 1")
    suspend fun getById(itemId: String): VaultItemEntity?

    @Query("UPDATE vault_items SET ocr_text = :ocrText WHERE id = :itemId")
    suspend fun updateOcrText(itemId: String, ocrText: String): Int

    @Transaction
    @Query("SELECT * FROM vault_items WHERE id = :itemId LIMIT 1")
    fun observeItemWithTags(itemId: String): Flow<VaultItemWithTags?>

    @Transaction
    @Query(
        """
        SELECT
            id,
            type,
            title,
            substr(body, 1, :previewCharacterLimit) AS body_preview,
            is_pinned,
            is_favorite,
            is_archived,
            created_at,
            updated_at,
            sync_status
        FROM vault_items
        WHERE deleted_at IS NULL AND is_archived = 0
        ORDER BY is_pinned DESC, updated_at DESC, id ASC
        LIMIT :limit
        OFFSET :offset
        """,
    )
    fun observeActiveSummaries(
        limit: Int,
        offset: Int,
        previewCharacterLimit: Int,
    ): Flow<List<VaultItemSummaryWithTags>>

    @Transaction
    @Query(
        """
        SELECT
            id,
            type,
            title,
            substr(body, 1, :previewCharacterLimit) AS body_preview,
            is_pinned,
            is_favorite,
            is_archived,
            created_at,
            updated_at,
            sync_status
        FROM vault_items
        WHERE deleted_at IS NULL AND is_archived = 1
        ORDER BY updated_at DESC, id ASC
        LIMIT :limit
        OFFSET :offset
        """,
    )
    fun observeArchivedSummaries(
        limit: Int,
        offset: Int,
        previewCharacterLimit: Int,
    ): Flow<List<VaultItemSummaryWithTags>>

    @Transaction
    @Query(
        """
        SELECT
            id,
            type,
            title,
            substr(body, 1, :previewCharacterLimit) AS body_preview,
            is_pinned,
            is_favorite,
            is_archived,
            created_at,
            updated_at,
            sync_status
        FROM vault_items
        WHERE deleted_at IS NOT NULL
        ORDER BY deleted_at DESC, id ASC
        LIMIT :limit
        OFFSET :offset
        """,
    )
    fun observeTrashSummaries(
        limit: Int,
        offset: Int,
        previewCharacterLimit: Int,
    ): Flow<List<VaultItemSummaryWithTags>>
}
