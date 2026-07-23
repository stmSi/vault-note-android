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
import com.vaultnote.core.database.model.VaultItemSortRow
import com.vaultnote.core.common.model.VaultItemType
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultItemDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: VaultItemEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(item: VaultItemEntity): Int

    @Query("SELECT * FROM vault_items WHERE id = :itemId LIMIT 1")
    suspend fun getById(itemId: String): VaultItemEntity?

    @Query("DELETE FROM vault_items WHERE id = :itemId")
    suspend fun deleteById(itemId: String): Int

    @Query(
        """
        SELECT * FROM vault_items
        WHERE id = :originId OR conflict_origin_id = :originId
        ORDER BY conflict_origin_id IS NOT NULL ASC, created_at ASC, id ASC
        """,
    )
    suspend fun getConflictGroup(originId: String): List<VaultItemEntity>

    @Query("UPDATE vault_items SET ocr_text = :ocrText WHERE id = :itemId")
    suspend fun updateOcrText(itemId: String, ocrText: String): Int

    @Query(
        """
        SELECT MIN(sort_position) FROM vault_items
        WHERE deleted_at IS NULL AND is_archived = 0
          AND type = :type AND is_pinned = :isPinned
        """,
    )
    suspend fun minimumActiveSortPosition(type: VaultItemType, isPinned: Boolean): Long?

    @Query(
        """
        SELECT MAX(sort_position) FROM vault_items
        WHERE deleted_at IS NULL AND is_archived = 0 AND type = 'NOTE'
          AND is_pinned = :isPinned AND id != :excludedItemId
          AND sort_position < :position
        """,
    )
    suspend fun previousActiveNoteSortPosition(
        isPinned: Boolean,
        position: Long,
        excludedItemId: String,
    ): Long?

    @Query(
        """
        SELECT MIN(sort_position) FROM vault_items
        WHERE deleted_at IS NULL AND is_archived = 0 AND type = 'NOTE'
          AND is_pinned = :isPinned AND id != :excludedItemId
          AND sort_position > :position
        """,
    )
    suspend fun nextActiveNoteSortPosition(
        isPinned: Boolean,
        position: Long,
        excludedItemId: String,
    ): Long?

    @Query(
        """
        SELECT id, sort_position, local_revision
        FROM vault_items
        WHERE deleted_at IS NULL AND is_archived = 0 AND type = :type
          AND is_pinned = :isPinned
        ORDER BY sort_position ASC, updated_at DESC, id ASC
        """,
    )
    suspend fun getActiveSortRows(
        type: VaultItemType,
        isPinned: Boolean,
    ): List<VaultItemSortRow>

    @Query(
        """
        UPDATE vault_items
        SET sort_position = :sortPosition,
            local_revision = :localRevision,
            sync_status = 'PENDING'
        WHERE id = :itemId
        """,
    )
    suspend fun updateSortPosition(
        itemId: String,
        sortPosition: Long,
        localRevision: Long,
    ): Int

    @Transaction
    @Query("SELECT * FROM vault_items WHERE id = :itemId LIMIT 1")
    fun observeItemWithTags(itemId: String): Flow<VaultItemWithTags?>

    @Transaction
    @Query(
        """
        SELECT
            id,
            type,
            color,
            title,
            substr(body, 1, :previewCharacterLimit) AS body_preview,
            is_pinned,
            is_favorite,
            is_archived,
            sort_position,
            created_at,
            updated_at,
            sync_status,
            conflict_origin_id
        FROM vault_items
        WHERE deleted_at IS NULL AND is_archived = 0 AND type = 'NOTE'
        ORDER BY is_pinned DESC, sort_position ASC, updated_at DESC, id ASC
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
            color,
            title,
            substr(body, 1, :previewCharacterLimit) AS body_preview,
            is_pinned,
            is_favorite,
            is_archived,
            sort_position,
            created_at,
            updated_at,
            sync_status,
            conflict_origin_id
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
            color,
            title,
            substr(body, 1, :previewCharacterLimit) AS body_preview,
            is_pinned,
            is_favorite,
            is_archived,
            sort_position,
            created_at,
            updated_at,
            sync_status,
            conflict_origin_id
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

    @Transaction
    @Query(
        """
        SELECT
            id,
            type,
            color,
            title,
            substr(body, 1, :previewCharacterLimit) AS body_preview,
            is_pinned,
            is_favorite,
            is_archived,
            sort_position,
            created_at,
            updated_at,
            sync_status,
            conflict_origin_id
        FROM vault_items
        WHERE sync_status = 'CONFLICT'
        ORDER BY updated_at DESC, id ASC
        LIMIT :limit
        """,
    )
    fun observeConflictSummaries(
        limit: Int,
        previewCharacterLimit: Int,
    ): Flow<List<VaultItemSummaryWithTags>>
}
