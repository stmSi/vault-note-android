package com.vaultnote.core.repository

import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.VaultItemSummary
import com.vaultnote.core.common.model.VaultItemColor
import com.vaultnote.core.common.model.VaultItemType
import com.vaultnote.core.common.model.VaultNote
import com.vaultnote.core.common.model.VaultTag
import com.vaultnote.core.common.model.AgendaEntry
import com.vaultnote.core.common.model.DatedEntry
import com.vaultnote.core.common.model.DatedEntryDraft
import com.vaultnote.core.common.model.NoteBodyDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import com.vaultnote.core.common.NoteBodyCodec
import com.vaultnote.core.common.AppError

interface VaultRepository {
    fun observeActiveItems(
        limit: Int = DEFAULT_ACTIVE_ITEM_LIMIT,
        offset: Int = 0,
    ): Flow<List<VaultItemSummary>>

    fun observeArchivedItems(
        limit: Int = DEFAULT_ACTIVE_ITEM_LIMIT,
        offset: Int = 0,
    ): Flow<List<VaultItemSummary>>

    fun observeTrashItems(
        limit: Int = DEFAULT_ACTIVE_ITEM_LIMIT,
        offset: Int = 0,
    ): Flow<List<VaultItemSummary>>

    fun observeNote(id: String): Flow<VaultNote?>

    fun observeTags(): Flow<List<VaultTag>>

    fun observeDatedEntries(itemId: String): Flow<List<DatedEntry>> = flowOf(emptyList())

    fun observeAgenda(includeCompleted: Boolean = false): Flow<List<AgendaEntry>> =
        flowOf(emptyList())

    suspend fun createNote(title: String = "", body: String = ""): RepositoryResult<String>

    suspend fun createAttachmentContainer(
        title: String,
        type: VaultItemType,
    ): RepositoryResult<String>

    suspend fun saveNote(id: String, title: String, body: String): RepositoryResult<Unit>

    suspend fun saveNote(
        id: String,
        title: String,
        body: String,
        tagNames: Collection<String>,
    ): RepositoryResult<Unit>

    suspend fun saveStructuredNote(
        id: String,
        title: String,
        bodyDocument: NoteBodyDocument,
        tagNames: Collection<String>,
    ): RepositoryResult<Unit> = saveNote(
        id = id,
        title = title,
        body = NoteBodyCodec.derivePlainText(bodyDocument),
        tagNames = tagNames,
    )

    suspend fun saveDatedEntry(
        itemId: String,
        draft: DatedEntryDraft,
    ): RepositoryResult<String> =
        RepositoryResult.Failure(AppError.InvalidItemState(itemId, "dated_entries_unsupported"))

    suspend fun deleteDatedEntry(entryId: String): RepositoryResult<Unit> =
        RepositoryResult.Failure(AppError.InvalidItemState(entryId, "dated_entries_unsupported"))

    suspend fun completeDatedEntry(entryId: String): RepositoryResult<Unit> =
        RepositoryResult.Failure(AppError.InvalidItemState(entryId, "dated_entries_unsupported"))

    suspend fun snoozeDatedEntryAlert(
        alertId: String,
        untilEpochMillis: Long,
    ): RepositoryResult<Unit> =
        RepositoryResult.Failure(AppError.InvalidItemState(alertId, "dated_entries_unsupported"))

    suspend fun markDatedEntryAlertDelivered(
        alertId: String,
        occurrenceAtEpochMillis: Long,
    ): RepositoryResult<Unit> =
        RepositoryResult.Failure(AppError.InvalidItemState(alertId, "dated_entries_unsupported"))

    suspend fun setPinned(id: String, isPinned: Boolean): RepositoryResult<Unit>

    suspend fun reorderActiveItem(
        id: String,
        previousItemId: String?,
        nextItemId: String?,
    ): RepositoryResult<Unit>

    suspend fun setFavorite(id: String, isFavorite: Boolean): RepositoryResult<Unit>

    suspend fun setColor(id: String, color: VaultItemColor): RepositoryResult<Unit>

    suspend fun setArchived(id: String, isArchived: Boolean): RepositoryResult<Unit>

    suspend fun moveToTrash(id: String): RepositoryResult<Unit>

    suspend fun restore(id: String): RepositoryResult<Unit>

    suspend fun setTags(id: String, tagNames: Collection<String>): RepositoryResult<Unit>

    companion object {
        const val DEFAULT_ACTIVE_ITEM_LIMIT: Int = 100
        const val MAX_OBSERVED_ITEM_LIMIT: Int = 101
    }
}
