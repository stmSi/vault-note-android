package com.vaultnote.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.vaultnote.core.database.entity.SearchDocumentEntity
import com.vaultnote.core.database.model.SearchResultRow
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDocument(document: SearchDocumentEntity): Long

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun updateDocument(document: SearchDocumentEntity): Int

    @Query("SELECT * FROM search_documents WHERE item_id = :itemId LIMIT 1")
    suspend fun getDocumentForItem(itemId: String): SearchDocumentEntity?

    @Query("DELETE FROM search_documents WHERE item_id = :itemId")
    suspend fun deleteDocumentForItem(itemId: String): Int

    @Query("UPDATE search_documents SET ocr_text = :ocrText WHERE item_id = :itemId")
    suspend fun updateOcrText(itemId: String, ocrText: String): Int

    @Query(
        """
        SELECT
            vault_items.id AS id,
            vault_items.title AS title,
            snippet(search_fts, :startMarker, :endMarker, :ellipsis, 0, :titleTokenLimit)
                AS highlighted_title,
            snippet(search_fts, :startMarker, :endMarker, :ellipsis, -1, :snippetTokenLimit)
                AS highlighted_snippet,
            vault_items.is_archived AS is_archived,
            vault_items.updated_at AS updated_at
        FROM search_fts
        INNER JOIN search_documents
            ON search_documents.rowid = search_fts.rowid
        INNER JOIN vault_items
            ON vault_items.id = search_documents.item_id
        WHERE search_fts MATCH :matchExpression
          AND vault_items.deleted_at IS NULL
        ORDER BY vault_items.is_pinned DESC, vault_items.updated_at DESC, vault_items.id ASC
        LIMIT :limit
        """,
    )
    fun observeMatches(
        matchExpression: String,
        startMarker: String,
        endMarker: String,
        ellipsis: String,
        titleTokenLimit: Int,
        snippetTokenLimit: Int,
        limit: Int,
    ): Flow<List<SearchResultRow>>
}
