package com.vaultnote.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.vaultnote.core.database.entity.AttachmentEntity
import com.vaultnote.core.database.entity.ItemTagCrossRef
import com.vaultnote.core.database.entity.TagEntity
import com.vaultnote.core.database.entity.VaultItemEntity

/** Keyset-paged reads used to create a bounded-memory, transactionally consistent backup. */
@Dao
interface BackupDao {
    @Query(
        """
        SELECT * FROM vault_items
        WHERE id > :afterId
        ORDER BY id ASC
        LIMIT :limit
        """,
    )
    suspend fun getItemsPage(afterId: String, limit: Int): List<VaultItemEntity>

    @Query(
        """
        SELECT * FROM tags
        WHERE id > :afterId
        ORDER BY id ASC
        LIMIT :limit
        """,
    )
    suspend fun getTagsPage(afterId: String, limit: Int): List<TagEntity>

    @Query(
        """
        SELECT * FROM item_tag_cross_refs
        WHERE item_id > :afterItemId
           OR (item_id = :afterItemId AND tag_id > :afterTagId)
        ORDER BY item_id ASC, tag_id ASC
        LIMIT :limit
        """,
    )
    suspend fun getItemTagsPage(
        afterItemId: String,
        afterTagId: String,
        limit: Int,
    ): List<ItemTagCrossRef>

    @Query(
        """
        SELECT * FROM attachments
        WHERE id > :afterId
        ORDER BY id ASC
        LIMIT :limit
        """,
    )
    suspend fun getAttachmentsPage(afterId: String, limit: Int): List<AttachmentEntity>

    @Query("SELECT COUNT(*) FROM vault_items")
    suspend fun countItems(): Long

    @Query("SELECT COUNT(*) FROM attachments")
    suspend fun countAttachments(): Long

    @Query("SELECT COALESCE(SUM(file_size), 0) FROM attachments")
    suspend fun totalAttachmentBytes(): Long
}
