package com.vaultnote.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "tags",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["name"], name = "index_tags_name"),
        Index(
            value = ["normalized_name"],
            name = "index_tags_normalized_name_unique",
            unique = true,
        ),
    ],
)
data class TagEntity(
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "normalized_name")
    val normalizedName: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "item_tag_cross_refs",
    primaryKeys = ["item_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = VaultItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tag_id"], name = "index_item_tag_cross_refs_tag_id")],
)
data class ItemTagCrossRef(
    @ColumnInfo(name = "item_id")
    val itemId: String,
    @ColumnInfo(name = "tag_id")
    val tagId: String,
)
