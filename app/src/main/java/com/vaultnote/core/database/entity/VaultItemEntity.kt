package com.vaultnote.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.vaultnote.core.common.model.ItemSyncStatus
import com.vaultnote.core.common.model.VaultItemType

@Entity(
    tableName = "vault_items",
    indices = [
        Index(value = ["updated_at"], name = "index_vault_items_updated_at"),
        Index(value = ["created_at"], name = "index_vault_items_created_at"),
        Index(value = ["sync_status"], name = "index_vault_items_sync_status"),
        Index(value = ["deleted_at"], name = "index_vault_items_deleted_at"),
        Index(value = ["is_pinned"], name = "index_vault_items_is_pinned"),
        Index(value = ["is_favorite"], name = "index_vault_items_is_favorite"),
        Index(value = ["is_archived"], name = "index_vault_items_is_archived"),
        Index(
            value = ["deleted_at", "is_archived", "is_pinned", "updated_at", "id"],
            name = "index_vault_items_active_order",
        ),
    ],
)
data class VaultItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "type")
    val type: VaultItemType,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "body")
    val body: String,
    @ColumnInfo(name = "ocr_text")
    val ocrText: String,
    @ColumnInfo(name = "is_pinned")
    val isPinned: Boolean,
    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean,
    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "local_revision")
    val localRevision: Long,
    @ColumnInfo(name = "remote_revision")
    val remoteRevision: Long?,
    @ColumnInfo(name = "last_synced_revision")
    val lastSyncedRevision: Long?,
    @ColumnInfo(name = "server_version_token")
    val serverVersionToken: String?,
    @ColumnInfo(name = "sync_status")
    val syncStatus: ItemSyncStatus,
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long?,
    @ColumnInfo(name = "conflict_origin_id")
    val conflictOriginId: String?,
)
