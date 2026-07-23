package com.vaultnote.core.common.model

/**
 * Versioned note body used by the Android block editor. [VaultNote.body] remains the
 * derived, searchable plain-text representation.
 */
data class NoteBodyDocument(
    val version: Int = CURRENT_VERSION,
    val blocks: List<NoteBlock>,
) {
    companion object {
        const val CURRENT_VERSION: Int = 1
    }
}

enum class NoteBlockType {
    PARAGRAPH,
    CHECKLIST_ITEM,
}

data class NoteBlock(
    val id: String,
    val type: NoteBlockType,
    val text: String,
    val isChecked: Boolean = false,
)

enum class DatedEntryType {
    REMINDER,
    DEADLINE,
    IMPORTANT_DATE,
    RENEWAL,
}

enum class RecurrenceUnit {
    DAY,
    WEEK,
    MONTH,
    YEAR,
}

data class RecurrenceRule(
    val interval: Int,
    val unit: RecurrenceUnit,
)

data class DatedEntryAlert(
    val id: String,
    val leadTimeMinutes: Long,
    val snoozedUntilEpochMillis: Long? = null,
    val lastDeliveredOccurrenceEpochMillis: Long? = null,
)

data class DatedEntry(
    val id: String,
    val itemId: String,
    val type: DatedEntryType,
    val label: String,
    val occurrenceAtEpochMillis: Long,
    val isAllDay: Boolean,
    val timeZoneId: String,
    val recurrence: RecurrenceRule?,
    val completedAtEpochMillis: Long?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val alerts: List<DatedEntryAlert>,
)

data class AgendaEntry(
    val entry: DatedEntry,
    val noteTitle: String,
    val noteColor: VaultItemColor,
    val isArchived: Boolean,
)

data class DatedEntryDraft(
    val id: String? = null,
    val type: DatedEntryType,
    val label: String,
    val occurrenceAtEpochMillis: Long,
    val isAllDay: Boolean,
    val timeZoneId: String,
    val recurrence: RecurrenceRule? = null,
    val alertLeadTimesMinutes: List<Long> = listOf(0L),
)
