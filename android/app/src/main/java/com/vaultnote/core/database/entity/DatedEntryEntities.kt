package com.vaultnote.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.vaultnote.core.common.model.DatedEntryType
import com.vaultnote.core.common.model.RecurrenceUnit

@Entity(
    tableName = "dated_entries",
    foreignKeys = [
        ForeignKey(
            entity = VaultItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["item_id"], name = "index_dated_entries_item_id"),
        Index(
            value = ["completed_at", "occurrence_at", "id"],
            name = "index_dated_entries_agenda",
        ),
    ],
)
data class DatedEntryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "item_id")
    val itemId: String,
    @ColumnInfo(name = "entry_type")
    val type: DatedEntryType,
    @ColumnInfo(name = "label")
    val label: String,
    @ColumnInfo(name = "occurrence_at")
    val occurrenceAt: Long,
    @ColumnInfo(name = "is_all_day")
    val isAllDay: Boolean,
    @ColumnInfo(name = "time_zone_id")
    val timeZoneId: String,
    @ColumnInfo(name = "recurrence_unit")
    val recurrenceUnit: RecurrenceUnit?,
    @ColumnInfo(name = "recurrence_interval")
    val recurrenceInterval: Int?,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "dated_entry_alerts",
    foreignKeys = [
        ForeignKey(
            entity = DatedEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entry_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["entry_id"], name = "index_dated_entry_alerts_entry_id"),
        Index(value = ["snoozed_until"], name = "index_dated_entry_alerts_snoozed_until"),
    ],
)
data class DatedEntryAlertEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "entry_id")
    val entryId: String,
    @ColumnInfo(name = "lead_time_minutes")
    val leadTimeMinutes: Long,
    @ColumnInfo(name = "snoozed_until")
    val snoozedUntil: Long?,
    @ColumnInfo(name = "last_delivered_occurrence")
    val lastDeliveredOccurrence: Long?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
