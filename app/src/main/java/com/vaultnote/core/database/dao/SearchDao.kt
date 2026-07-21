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
            vault_items.color AS color,
            vault_items.type AS type,
            (
                SELECT attachments.id FROM attachments
                WHERE attachments.parent_item_id = vault_items.id
                ORDER BY attachments.created_at ASC, attachments.id ASC
                LIMIT 1
            ) AS primary_attachment_id,
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

    @Query(
        """
        SELECT
            vault_items.id AS id,
            vault_items.title AS title,
            vault_items.color AS color,
            vault_items.type AS type,
            (
                SELECT attachments.id FROM attachments
                WHERE attachments.parent_item_id = vault_items.id
                ORDER BY attachments.created_at ASC, attachments.id ASC
                LIMIT 1
            ) AS primary_attachment_id,
            search_documents.title AS highlighted_title,
            CASE
                WHEN search_documents.title COLLATE NOCASE LIKE :subsequencePattern ESCAPE '\' THEN ''
                WHEN search_documents.attachment_filenames COLLATE NOCASE
                    LIKE :subsequencePattern ESCAPE '\'
                    THEN substr(
                        search_documents.attachment_filenames,
                        max(
                            1,
                            instr(lower(search_documents.attachment_filenames), lower(:anchor)) -
                                :snippetLeadingCharacters
                        ),
                        :snippetCharacterLimit
                    )
                WHEN search_documents.tags COLLATE NOCASE LIKE :subsequencePattern ESCAPE '\'
                    THEN substr(
                        search_documents.tags,
                        max(
                            1,
                            instr(lower(search_documents.tags), lower(:anchor)) -
                                :snippetLeadingCharacters
                        ),
                        :snippetCharacterLimit
                    )
                WHEN search_documents.body COLLATE NOCASE LIKE :subsequencePattern ESCAPE '\'
                    THEN substr(
                        search_documents.body,
                        max(
                            1,
                            instr(lower(search_documents.body), lower(:anchor)) -
                                :snippetLeadingCharacters
                        ),
                        :snippetCharacterLimit
                    )
                ELSE substr(
                    search_documents.ocr_text,
                    max(
                        1,
                        instr(lower(search_documents.ocr_text), lower(:anchor)) -
                            :snippetLeadingCharacters
                    ),
                    :snippetCharacterLimit
                )
            END AS highlighted_snippet,
            vault_items.is_archived AS is_archived,
            vault_items.updated_at AS updated_at
        FROM search_documents
        INNER JOIN vault_items ON vault_items.id = search_documents.item_id
        WHERE vault_items.deleted_at IS NULL
          AND (
            search_documents.title COLLATE NOCASE LIKE :subsequencePattern ESCAPE '\'
            OR search_documents.body COLLATE NOCASE LIKE :subsequencePattern ESCAPE '\'
            OR search_documents.tags COLLATE NOCASE LIKE :subsequencePattern ESCAPE '\'
            OR search_documents.attachment_filenames COLLATE NOCASE
                LIKE :subsequencePattern ESCAPE '\'
            OR search_documents.ocr_text COLLATE NOCASE LIKE :subsequencePattern ESCAPE '\'
          )
        ORDER BY vault_items.is_pinned DESC, vault_items.updated_at DESC, vault_items.id ASC
        LIMIT :limit
        """,
    )
    fun observeSubsequenceMatches(
        subsequencePattern: String,
        anchor: String,
        snippetLeadingCharacters: Int,
        snippetCharacterLimit: Int,
        limit: Int,
    ): Flow<List<SearchResultRow>>
}
