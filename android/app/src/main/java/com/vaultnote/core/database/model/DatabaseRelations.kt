package com.vaultnote.core.database.model

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import com.vaultnote.core.common.model.ItemSyncStatus
import com.vaultnote.core.common.model.VaultItemType
import com.vaultnote.core.common.model.VaultItemColor
import com.vaultnote.core.database.entity.ItemTagCrossRef
import com.vaultnote.core.database.entity.TagEntity
import com.vaultnote.core.database.entity.VaultItemEntity
import com.vaultnote.core.database.entity.DatedEntryAlertEntity
import com.vaultnote.core.database.entity.DatedEntryEntity

data class DatedEntryWithAlerts(
    @Embedded
    val entry: DatedEntryEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "entry_id",
    )
    val alerts: List<DatedEntryAlertEntity>,
)

data class VaultItemWithTags(
    @Embedded
    val item: VaultItemEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = ItemTagCrossRef::class,
            parentColumn = "item_id",
            entityColumn = "tag_id",
        ),
    )
    val tags: List<TagEntity>,
    @Relation(
        entity = DatedEntryEntity::class,
        parentColumn = "id",
        entityColumn = "item_id",
    )
    val datedEntries: List<DatedEntryWithAlerts>,
)

/** A deliberately narrow row used by the initial vault list. */
data class VaultItemSummaryRow(
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "type")
    val type: VaultItemType,
    @ColumnInfo(name = "color")
    val color: VaultItemColor,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "body_preview")
    val bodyPreview: String,
    @ColumnInfo(name = "is_pinned")
    val isPinned: Boolean,
    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean,
    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean,
    @ColumnInfo(name = "sort_position")
    val sortPosition: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "sync_status")
    val syncStatus: ItemSyncStatus,
    @ColumnInfo(name = "conflict_origin_id")
    val conflictOriginId: String?,
    @ColumnInfo(name = "next_dated_entry_at")
    val nextDatedEntryAt: Long?,
    @ColumnInfo(name = "has_overdue_entry")
    val hasOverdueEntry: Boolean,
)

data class VaultItemSummaryWithTags(
    @Embedded
    val item: VaultItemSummaryRow,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = ItemTagCrossRef::class,
            parentColumn = "item_id",
            entityColumn = "tag_id",
        ),
    )
    val tags: List<TagEntity>,
)

/** Minimal projection used only when a crowded manual-order range must be rebalanced. */
data class VaultItemSortRow(
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "sort_position")
    val sortPosition: Long,
    @ColumnInfo(name = "local_revision")
    val localRevision: Long,
)

/** Bounded FTS projection; no attachment bytes or complete note bodies are loaded. */
data class SearchResultRow(
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "color")
    val color: VaultItemColor,
    @ColumnInfo(name = "type")
    val type: VaultItemType,
    @ColumnInfo(name = "primary_attachment_id")
    val primaryAttachmentId: String?,
    @ColumnInfo(name = "highlighted_title")
    val highlightedTitle: String,
    @ColumnInfo(name = "highlighted_snippet")
    val highlightedSnippet: String,
    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

data class AgendaEntryRow(
    @Embedded
    val entry: DatedEntryEntity,
    @ColumnInfo(name = "note_title")
    val noteTitle: String,
    @ColumnInfo(name = "note_color")
    val noteColor: VaultItemColor,
    @ColumnInfo(name = "note_is_archived")
    val noteIsArchived: Boolean,
    @Relation(
        parentColumn = "id",
        entityColumn = "entry_id",
    )
    val alerts: List<DatedEntryAlertEntity>,
)
