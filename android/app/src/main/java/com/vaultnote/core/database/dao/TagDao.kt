package com.vaultnote.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vaultnote.core.database.entity.ItemTagCrossRef
import com.vaultnote.core.database.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTags(tags: List<TagEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRefs(crossRefs: List<ItemTagCrossRef>): List<Long>

    @Query("SELECT * FROM tags WHERE normalized_name IN (:normalizedNames)")
    suspend fun getByNormalizedNames(normalizedNames: List<String>): List<TagEntity>

    @Query("SELECT * FROM tags WHERE id = :tagId LIMIT 1")
    suspend fun getById(tagId: String): TagEntity?

    @Query(
        """
        SELECT tags.* FROM tags
        INNER JOIN item_tag_cross_refs ON tags.id = item_tag_cross_refs.tag_id
        WHERE item_tag_cross_refs.item_id = :itemId
        ORDER BY tags.normalized_name ASC
        """,
    )
    suspend fun getTagsForItem(itemId: String): List<TagEntity>

    @Query(
        """
        SELECT tags.* FROM tags
        INNER JOIN item_tag_cross_refs ON tags.id = item_tag_cross_refs.tag_id
        WHERE item_tag_cross_refs.item_id = :itemId
        ORDER BY tags.normalized_name ASC
        """,
    )
    fun observeTagsForItem(itemId: String): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY normalized_name ASC")
    fun observeAllTags(): Flow<List<TagEntity>>

    @Query("DELETE FROM item_tag_cross_refs WHERE item_id = :itemId")
    suspend fun deleteCrossRefsForItem(itemId: String): Int

    @Query(
        """
        DELETE FROM tags
        WHERE NOT EXISTS (
            SELECT 1 FROM item_tag_cross_refs WHERE item_tag_cross_refs.tag_id = tags.id
        )
        """,
    )
    suspend fun deleteUnusedTags(): Int
}
