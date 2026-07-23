package com.vaultnote.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Small external-content table for FTS. Large binaries remain in app-internal files; only searchable
 * text is copied here. The integer rowid is required by SQLite FTS external-content tables.
 */
@Entity(
    tableName = "search_documents",
    foreignKeys = [
        ForeignKey(
            entity = VaultItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["item_id"], name = "index_search_documents_item_id", unique = true),
    ],
)
data class SearchDocumentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "rowid")
    val rowId: Long = 0L,
    @ColumnInfo(name = "item_id")
    val itemId: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "body")
    val body: String,
    @ColumnInfo(name = "tags")
    val tags: String,
    @ColumnInfo(name = "attachment_filenames")
    val attachmentFilenames: String,
    @ColumnInfo(name = "ocr_text")
    val ocrText: String,
)

@Fts4(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    contentEntity = SearchDocumentEntity::class,
)
@Entity(tableName = "search_fts")
data class SearchFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "body")
    val body: String,
    @ColumnInfo(name = "tags")
    val tags: String,
    @ColumnInfo(name = "attachment_filenames")
    val attachmentFilenames: String,
    @ColumnInfo(name = "ocr_text")
    val ocrText: String,
)
