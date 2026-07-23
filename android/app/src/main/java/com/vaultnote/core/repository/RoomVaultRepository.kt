package com.vaultnote.core.repository

import androidx.room.withTransaction
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.Clock
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.IdGenerator
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.VaultConstraints
import com.vaultnote.core.common.NoteBodyCodec
import com.vaultnote.core.common.model.AgendaEntry
import com.vaultnote.core.common.model.DatedEntry
import com.vaultnote.core.common.model.DatedEntryAlert
import com.vaultnote.core.common.model.DatedEntryDraft
import com.vaultnote.core.common.model.DatedEntryType
import com.vaultnote.core.common.model.NoteBodyDocument
import com.vaultnote.core.common.model.RecurrenceRule
import com.vaultnote.core.common.model.RecurrenceUnit
import com.vaultnote.core.common.model.ItemSyncStatus
import com.vaultnote.core.common.model.SyncOperationState
import com.vaultnote.core.common.model.SyncOperationType
import com.vaultnote.core.common.model.VaultItemSummary
import com.vaultnote.core.common.model.VaultItemColor
import com.vaultnote.core.common.model.VaultItemType
import com.vaultnote.core.common.model.VaultNote
import com.vaultnote.core.common.model.VaultTag
import com.vaultnote.core.database.VaultDatabase
import com.vaultnote.core.database.entity.ItemTagCrossRef
import com.vaultnote.core.database.entity.SearchDocumentEntity
import com.vaultnote.core.database.entity.SyncOperationEntity
import com.vaultnote.core.database.entity.TagEntity
import com.vaultnote.core.database.entity.VaultItemEntity
import com.vaultnote.core.database.entity.DatedEntryAlertEntity
import com.vaultnote.core.database.entity.DatedEntryEntity
import com.vaultnote.core.database.model.AgendaEntryRow
import com.vaultnote.core.database.model.DatedEntryWithAlerts
import com.vaultnote.core.database.model.VaultItemSummaryWithTags
import com.vaultnote.core.database.model.VaultItemWithTags
import com.vaultnote.core.sync.SyncScheduleResult
import com.vaultnote.core.sync.SyncScheduler
import com.vaultnote.core.reminder.NoOpReminderScheduler
import com.vaultnote.core.reminder.ReminderScheduler
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomVaultRepository(
    private val database: VaultDatabase,
    private val syncScheduler: SyncScheduler,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val reminderScheduler: ReminderScheduler = NoOpReminderScheduler,
) : VaultRepository {
    private val itemDao = database.vaultItemDao()
    private val tagDao = database.tagDao()
    private val syncOperationDao = database.syncOperationDao()
    private val searchDao = database.searchDao()
    private val datedEntryDao = database.datedEntryDao()

    override fun observeActiveItems(limit: Int, offset: Int): Flow<List<VaultItemSummary>> {
        val boundedLimit = boundedListLimit(limit)
        return itemDao.observeActiveSummaries(
            boundedLimit,
            offset.coerceAtLeast(0),
            BODY_PREVIEW_CHARACTER_LIMIT,
            clock.nowEpochMillis(),
        )
            .map { rows -> rows.map { row -> row.toDomain() } }
            .flowOn(dispatchers.io)
    }

    override fun observeArchivedItems(limit: Int, offset: Int): Flow<List<VaultItemSummary>> {
        val boundedLimit = boundedListLimit(limit)
        return itemDao.observeArchivedSummaries(
            boundedLimit,
            offset.coerceAtLeast(0),
            BODY_PREVIEW_CHARACTER_LIMIT,
            clock.nowEpochMillis(),
        )
            .map { rows -> rows.map { row -> row.toDomain() } }
            .flowOn(dispatchers.io)
    }

    override fun observeTrashItems(limit: Int, offset: Int): Flow<List<VaultItemSummary>> {
        val boundedLimit = boundedListLimit(limit)
        return itemDao.observeTrashSummaries(
            boundedLimit,
            offset.coerceAtLeast(0),
            BODY_PREVIEW_CHARACTER_LIMIT,
            clock.nowEpochMillis(),
        )
            .map { rows -> rows.map { row -> row.toDomain() } }
            .flowOn(dispatchers.io)
    }

    override fun observeNote(id: String): Flow<VaultNote?> {
        if (id.isBlank()) return flowOf(null)
        return itemDao.observeItemWithTags(id)
            .map { item -> item?.takeIf { it.item.type == VaultItemType.NOTE }?.toDomainNote() }
            .flowOn(dispatchers.io)
    }

    override fun observeTags(): Flow<List<VaultTag>> =
        tagDao.observeAllTags()
            .map { tags -> tags.map { tag -> tag.toDomain() } }
            .flowOn(dispatchers.io)

    override fun observeDatedEntries(itemId: String): Flow<List<DatedEntry>> {
        if (itemId.isBlank()) return flowOf(emptyList())
        return datedEntryDao.observeForItem(itemId)
            .map { rows -> rows.map { row -> row.toDomain() } }
            .flowOn(dispatchers.io)
    }

    override fun observeAgenda(includeCompleted: Boolean): Flow<List<AgendaEntry>> =
        datedEntryDao.observeAgenda(
            includeCompleted = includeCompleted,
            limit = MAX_AGENDA_ENTRIES,
        ).map { rows -> rows.map { row -> row.toDomain() } }
            .flowOn(dispatchers.io)

    override suspend fun createNote(title: String, body: String): RepositoryResult<String> =
        createItem(VaultItemType.NOTE, title, body)

    override suspend fun createAttachmentContainer(
        title: String,
        type: VaultItemType,
    ): RepositoryResult<String> {
        if (type != VaultItemType.DOCUMENT && type != VaultItemType.IMAGE) {
            return RepositoryResult.Failure(AppError.InvalidInput("type", "not_an_attachment"))
        }
        return createItem(type, title, body = "")
    }

    private suspend fun createItem(
        type: VaultItemType,
        title: String,
        body: String,
    ): RepositoryResult<String> {
        validateNoteContentOffMain(title, body)?.let { return RepositoryResult.Failure(it) }
        return runMutation(OPERATION_CREATE_NOTE) {
            val now = clock.nowEpochMillis()
            val sortPosition = nextTopSortPosition(type = type, isPinned = false)
            val item = VaultItemEntity(
                id = idGenerator.newId(),
                type = type,
                color = VaultItemColor.DEFAULT,
                title = title,
                body = body,
                ocrText = "",
                isPinned = false,
                isFavorite = false,
                isArchived = false,
                sortPosition = sortPosition,
                createdAt = now,
                updatedAt = now,
                localRevision = INITIAL_LOCAL_REVISION,
                remoteRevision = null,
                lastSyncedRevision = null,
                serverVersionToken = null,
                syncStatus = ItemSyncStatus.PENDING,
                deletedAt = null,
                conflictOriginId = null,
            )
            itemDao.insert(item)
            updateSearchDocument(item = item, tags = emptyList())
            enqueueItemOperation(item, SyncOperationType.UPSERT_ITEM, now)
            MutationOutcome(item.id, changed = true)
        }
    }

    override suspend fun saveNote(
        id: String,
        title: String,
        body: String,
    ): RepositoryResult<Unit> = saveNoteInternal(
        id = id,
        title = title,
        body = body,
        requestedTags = null,
    )

    override suspend fun saveNote(
        id: String,
        title: String,
        body: String,
        tagNames: Collection<String>,
    ): RepositoryResult<Unit> {
        val normalizedResult = normalizeTagsOffMain(tagNames)
        if (normalizedResult is RepositoryResult.Failure) return normalizedResult
        return saveNoteInternal(
            id = id,
            title = title,
            body = body,
            requestedTags = (normalizedResult as RepositoryResult.Success).value,
        )
    }

    override suspend fun saveStructuredNote(
        id: String,
        title: String,
        bodyDocument: NoteBodyDocument,
        tagNames: Collection<String>,
    ): RepositoryResult<Unit> {
        val encoded = try {
            NoteBodyCodec.encode(bodyDocument)
        } catch (_: IllegalArgumentException) {
            return RepositoryResult.Failure(
                AppError.InvalidInput("body", "invalid_structured_body"),
            )
        }
        val body = NoteBodyCodec.derivePlainText(bodyDocument)
        validateNoteContentOffMain(title, body)?.let { return RepositoryResult.Failure(it) }
        val normalizedResult = normalizeTagsOffMain(tagNames)
        if (normalizedResult is RepositoryResult.Failure) return normalizedResult
        val requestedTags = (normalizedResult as RepositoryResult.Success).value

        return runMutation(OPERATION_SAVE_NOTE) {
            val current = requireEditableItem(id)
            if (current.type != VaultItemType.NOTE) {
                abort(AppError.InvalidItemState(id, "not_a_note"))
            }
            val existingTags = tagDao.getTagsForItem(id)
            val tagsChanged =
                existingTags.map(TagEntity::normalizedName).sorted() !=
                    requestedTags.map(TagInput::normalizedName).sorted()
            val contentChanged =
                current.title != title ||
                    current.body != body ||
                    current.bodyDocumentJson != encoded
            if (!contentChanged && !tagsChanged) {
                return@runMutation MutationOutcome(Unit, changed = false)
            }
            val now = clock.nowEpochMillis()
            val updated = current.withLocalChange(now).copy(
                title = title,
                body = body,
                bodyDocumentJson = encoded,
            )
            val resultingTags = if (tagsChanged) {
                replaceTagRelations(id, requestedTags, now)
            } else {
                existingTags
            }
            updateItemExactlyOnce(updated)
            updateSearchDocument(updated, resultingTags)
            enqueueItemOperation(updated, SyncOperationType.UPSERT_ITEM, now)
            MutationOutcome(Unit, changed = true)
        }
    }

    override suspend fun saveDatedEntry(
        itemId: String,
        draft: DatedEntryDraft,
    ): RepositoryResult<String> {
        validateDatedEntryDraft(draft)?.let { return RepositoryResult.Failure(it) }
        draft.id?.let { reminderScheduler.cancelEntry(it) }
        val result = runMutation(OPERATION_SAVE_DATED_ENTRY) {
            val item = requireEditableItem(itemId)
            if (item.type != VaultItemType.NOTE) {
                abort(AppError.InvalidItemState(itemId, "not_a_note"))
            }
            val now = clock.nowEpochMillis()
            val existing = draft.id?.let { datedEntryDao.getEntry(it) }
            if (draft.id != null && (existing == null || existing.itemId != itemId)) {
                abort(AppError.ItemNotFound(draft.id))
            }
            val entryId = existing?.id ?: idGenerator.newId()
            val entry = DatedEntryEntity(
                id = entryId,
                itemId = itemId,
                type = draft.type,
                label = draft.label.trim(),
                occurrenceAt = draft.occurrenceAtEpochMillis,
                isAllDay = draft.isAllDay,
                timeZoneId = draft.timeZoneId,
                recurrenceUnit = draft.recurrence?.unit,
                recurrenceInterval = draft.recurrence?.interval,
                completedAt = null,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            if (existing == null) {
                datedEntryDao.insertEntry(entry)
            } else if (datedEntryDao.updateEntry(entry) != 1) {
                error("Dated entry update did not affect exactly one row")
            }
            datedEntryDao.deleteAlertsForEntry(entryId)
            val alerts = draft.alertLeadTimesMinutes.distinct().sorted().map { leadMinutes ->
                DatedEntryAlertEntity(
                    id = idGenerator.newId(),
                    entryId = entryId,
                    leadTimeMinutes = leadMinutes,
                    snoozedUntil = null,
                    lastDeliveredOccurrence = null,
                    createdAt = now,
                )
            }
            if (alerts.isNotEmpty()) datedEntryDao.insertAlerts(alerts)
            val updatedItem = item.withLocalChange(now)
            updateItemExactlyOnce(updatedItem)
            enqueueItemOperation(updatedItem, SyncOperationType.UPSERT_ITEM, now)
            MutationOutcome(entryId, changed = true)
        }
        if (result is RepositoryResult.Success) {
            reminderScheduler.reconcileEntry(result.value)
        } else {
            draft.id?.let { reminderScheduler.reconcileEntry(it) }
        }
        return result
    }

    override suspend fun deleteDatedEntry(entryId: String): RepositoryResult<Unit> {
        reminderScheduler.cancelEntry(entryId)
        val result = runMutation(OPERATION_DELETE_DATED_ENTRY) {
            val existing = datedEntryDao.getEntry(entryId)
                ?: abort(AppError.ItemNotFound(entryId))
            val item = requireEditableItem(existing.itemId)
            if (datedEntryDao.deleteEntry(entryId) != 1) {
                error("Dated entry delete did not affect exactly one row")
            }
            val now = clock.nowEpochMillis()
            val updatedItem = item.withLocalChange(now)
            updateItemExactlyOnce(updatedItem)
            enqueueItemOperation(updatedItem, SyncOperationType.UPSERT_ITEM, now)
            MutationOutcome(Unit, changed = true)
        }
        if (result is RepositoryResult.Failure) reminderScheduler.reconcileEntry(entryId)
        return result
    }

    override suspend fun completeDatedEntry(entryId: String): RepositoryResult<Unit> {
        val result = runMutation(OPERATION_COMPLETE_DATED_ENTRY) {
            val existing = datedEntryDao.getEntry(entryId)
                ?: abort(AppError.ItemNotFound(entryId))
            val item = requireEditableItem(existing.itemId)
            val now = clock.nowEpochMillis()
            val recurrence = existing.recurrence()
            val updatedEntry = when {
                recurrence != null -> existing.copy(
                    occurrenceAt = nextOccurrenceAfter(
                        existing.occurrenceAt,
                        existing.timeZoneId,
                        recurrence,
                        now,
                    ),
                    completedAt = null,
                    updatedAt = now,
                )
                existing.type == DatedEntryType.IMPORTANT_DATE ->
                    return@runMutation MutationOutcome(Unit, changed = false)
                existing.completedAt != null ->
                    return@runMutation MutationOutcome(Unit, changed = false)
                else -> existing.copy(completedAt = now, updatedAt = now)
            }
            if (datedEntryDao.updateEntry(updatedEntry) != 1) {
                error("Dated entry completion did not affect exactly one row")
            }
            datedEntryDao.getEntryWithAlerts(entryId)?.alerts?.forEach { alert ->
                datedEntryDao.updateAlert(
                    alert.copy(
                        snoozedUntil = null,
                        lastDeliveredOccurrence = null,
                    ),
                )
            }
            val updatedItem = item.withLocalChange(now)
            updateItemExactlyOnce(updatedItem)
            enqueueItemOperation(updatedItem, SyncOperationType.UPSERT_ITEM, now)
            MutationOutcome(Unit, changed = true)
        }
        if (result is RepositoryResult.Success) reminderScheduler.reconcileEntry(entryId)
        return result
    }

    override suspend fun snoozeDatedEntryAlert(
        alertId: String,
        untilEpochMillis: Long,
    ): RepositoryResult<Unit> {
        if (untilEpochMillis <= clock.nowEpochMillis()) {
            return RepositoryResult.Failure(AppError.InvalidInput("snooze", "must_be_future"))
        }
        val entryId = withContext(dispatchers.io) { datedEntryDao.getAlert(alertId)?.entryId }
            ?: return RepositoryResult.Failure(AppError.ItemNotFound(alertId))
        return try {
            withContext(dispatchers.io) {
                if (datedEntryDao.setSnoozedUntil(alertId, untilEpochMillis) != 1) {
                    return@withContext RepositoryResult.Failure(AppError.ItemNotFound(alertId))
                }
                reminderScheduler.reconcileEntry(entryId)
                RepositoryResult.Success(Unit)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            RepositoryResult.Failure(AppError.DatabaseFailure(OPERATION_SNOOZE_ALERT, failure))
        }
    }

    override suspend fun markDatedEntryAlertDelivered(
        alertId: String,
        occurrenceAtEpochMillis: Long,
    ): RepositoryResult<Unit> = try {
        withContext(dispatchers.io) {
            if (datedEntryDao.markDelivered(alertId, occurrenceAtEpochMillis) == 1) {
                RepositoryResult.Success(Unit)
            } else {
                RepositoryResult.Failure(AppError.ItemNotFound(alertId))
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        RepositoryResult.Failure(AppError.DatabaseFailure(OPERATION_DELIVER_ALERT, failure))
    }

    private suspend fun saveNoteInternal(
        id: String,
        title: String,
        body: String,
        requestedTags: List<TagInput>?,
    ): RepositoryResult<Unit> {
        validateNoteContentOffMain(title, body)?.let { return RepositoryResult.Failure(it) }
        return runMutation(OPERATION_SAVE_NOTE) {
        val current = requireEditableItem(id)
        if (current.type != VaultItemType.NOTE) {
            abort(AppError.InvalidItemState(id, "not_a_note"))
        }

        val contentChanged = current.title != title || current.body != body
        if (!contentChanged && requestedTags == null) {
            return@runMutation MutationOutcome(Unit, changed = false)
        }

        val existingTags = tagDao.getTagsForItem(id)
        val tagsChanged = requestedTags != null &&
            existingTags.map(TagEntity::normalizedName).sorted() !=
            requestedTags.map(TagInput::normalizedName).sorted()
        if (!contentChanged && !tagsChanged) {
            return@runMutation MutationOutcome(Unit, changed = false)
        }

        val now = clock.nowEpochMillis()
        val updated = current.withLocalChange(now).copy(title = title, body = body)
        val resultingTags = if (tagsChanged) {
            replaceTagRelations(id, requestedTags.orEmpty(), now)
        } else {
            existingTags
        }
        updateItemExactlyOnce(updated)
        updateSearchDocument(updated, resultingTags)
        enqueueItemOperation(updated, SyncOperationType.UPSERT_ITEM, now)
        MutationOutcome(Unit, changed = true)
        }
    }

    override suspend fun setPinned(id: String, isPinned: Boolean): RepositoryResult<Unit> =
        runMutation(OPERATION_SET_PINNED) {
            val current = requireEditableItem(id)
            if (current.isPinned == isPinned) {
                return@runMutation MutationOutcome(Unit, changed = false)
            }
            val now = clock.nowEpochMillis()
            val updated = current.withLocalChange(now).copy(
                isPinned = isPinned,
                sortPosition = nextTopSortPosition(current.type, isPinned),
            )
            updateItemExactlyOnce(updated)
            enqueueItemOperation(updated, SyncOperationType.UPSERT_ITEM, now)
            MutationOutcome(Unit, changed = true)
        }

    override suspend fun reorderActiveItem(
        id: String,
        previousItemId: String?,
        nextItemId: String?,
    ): RepositoryResult<Unit> = runMutation(OPERATION_REORDER_ITEM) {
        if (previousItemId == id || nextItemId == id || previousItemId == nextItemId) {
            abort(AppError.InvalidInput("item_order", "invalid_neighbors"))
        }
        val current = requireEditableItem(id)
        if (current.type != VaultItemType.NOTE || current.isArchived) {
            abort(AppError.InvalidItemState(id, "not_an_active_note"))
        }
        if (previousItemId == null && nextItemId == null) {
            return@runMutation MutationOutcome(Unit, changed = false)
        }

        var bounds = resolveSortBounds(current, previousItemId, nextItemId)
        var targetPosition = positionBetween(bounds)
        var didRebalance = false
        if (targetPosition == null) {
            rebalanceActiveGroup(current.type, current.isPinned, clock.nowEpochMillis())
            didRebalance = true
            val reloaded = requireEditableItem(id)
            bounds = resolveSortBounds(reloaded, previousItemId, nextItemId)
            targetPosition = positionBetween(bounds)
                ?: abort(AppError.InvalidItemState(id, "manual_order_exhausted"))
        }

        val latest = requireEditableItem(id)
        if (latest.sortPosition == targetPosition) {
            return@runMutation MutationOutcome(Unit, changed = didRebalance)
        }
        val now = clock.nowEpochMillis()
        val updated = latest.withLocalChange(now).copy(sortPosition = targetPosition)
        updateItemExactlyOnce(updated)
        enqueueItemOperation(updated, SyncOperationType.UPSERT_ITEM, now)
        MutationOutcome(Unit, changed = true)
    }

    override suspend fun setFavorite(id: String, isFavorite: Boolean): RepositoryResult<Unit> =
        updateBooleanProperty(
            id = id,
            operationName = OPERATION_SET_FAVORITE,
            isUnchanged = { it.isFavorite == isFavorite },
            transform = { item -> item.copy(isFavorite = isFavorite) },
        )

    override suspend fun setColor(id: String, color: VaultItemColor): RepositoryResult<Unit> =
        updateBooleanProperty(
            id = id,
            operationName = OPERATION_SET_COLOR,
            isUnchanged = { it.color == color },
            transform = { item -> item.copy(color = color) },
        )

    override suspend fun setArchived(id: String, isArchived: Boolean): RepositoryResult<Unit> =
        updateBooleanProperty(
            id = id,
            operationName = OPERATION_SET_ARCHIVED,
            isUnchanged = { it.isArchived == isArchived },
            transform = { item -> item.copy(isArchived = isArchived) },
        )

    override suspend fun moveToTrash(id: String): RepositoryResult<Unit> {
        val result = runMutation(OPERATION_MOVE_TO_TRASH) {
            val current = requireItem(id)
            if (current.deletedAt != null) {
                return@runMutation MutationOutcome(Unit, changed = false)
            }
            val now = clock.nowEpochMillis()
            val updated = current.withLocalChange(now).copy(deletedAt = now)
            updateItemExactlyOnce(updated)
            enqueueItemOperation(updated, SyncOperationType.DELETE_ITEM, now)
            MutationOutcome(Unit, changed = true)
        }
        if (result is RepositoryResult.Success) reminderScheduler.reconcileAll()
        return result
    }

    override suspend fun restore(id: String): RepositoryResult<Unit> {
        val result = runMutation(OPERATION_RESTORE) {
            val current = requireItem(id)
            if (current.deletedAt == null) {
                return@runMutation MutationOutcome(Unit, changed = false)
            }
            val now = clock.nowEpochMillis()
            val updated = current.withLocalChange(now).copy(deletedAt = null)
            updateItemExactlyOnce(updated)
            enqueueItemOperation(updated, SyncOperationType.UPSERT_ITEM, now)
            MutationOutcome(Unit, changed = true)
        }
        if (result is RepositoryResult.Success) reminderScheduler.reconcileAll()
        return result
    }

    override suspend fun setTags(
        id: String,
        tagNames: Collection<String>,
    ): RepositoryResult<Unit> {
        val normalizedResult = normalizeTagsOffMain(tagNames)
        if (normalizedResult is RepositoryResult.Failure) return normalizedResult
        val requestedTags = (normalizedResult as RepositoryResult.Success).value

        return runMutation(OPERATION_SET_TAGS) {
            val current = requireEditableItem(id)
            val existing = tagDao.getTagsForItem(id)
            val requestedNormalizedNames = requestedTags.map(TagInput::normalizedName)
            if (existing.map(TagEntity::normalizedName).sorted() == requestedNormalizedNames.sorted()) {
                return@runMutation MutationOutcome(Unit, changed = false)
            }

            val now = clock.nowEpochMillis()
            val resolved = replaceTagRelations(id, requestedTags, now)

            val updated = current.withLocalChange(now)
            updateItemExactlyOnce(updated)
            updateSearchDocument(updated, resolved)
            enqueueItemOperation(updated, SyncOperationType.UPSERT_ITEM, now)
            MutationOutcome(Unit, changed = true)
        }
    }

    private suspend fun updateBooleanProperty(
        id: String,
        operationName: String,
        isUnchanged: (VaultItemEntity) -> Boolean,
        transform: (VaultItemEntity) -> VaultItemEntity,
    ): RepositoryResult<Unit> = runMutation(operationName) {
        val current = requireEditableItem(id)
        if (isUnchanged(current)) {
            return@runMutation MutationOutcome(Unit, changed = false)
        }
        val now = clock.nowEpochMillis()
        val updated = transform(current.withLocalChange(now))
        updateItemExactlyOnce(updated)
        enqueueItemOperation(updated, SyncOperationType.UPSERT_ITEM, now)
        MutationOutcome(Unit, changed = true)
    }

    private suspend fun nextTopSortPosition(type: VaultItemType, isPinned: Boolean): Long {
        val minimum = itemDao.minimumActiveSortPosition(type, isPinned) ?: return 0L
        return if (minimum >= Long.MIN_VALUE + SORT_POSITION_GAP) {
            minimum - SORT_POSITION_GAP
        } else {
            rebalanceActiveGroup(type, isPinned, clock.nowEpochMillis())
            val rebalancedMinimum =
                itemDao.minimumActiveSortPosition(type, isPinned) ?: return 0L
            if (rebalancedMinimum < Long.MIN_VALUE + SORT_POSITION_GAP) {
                abort(AppError.InvalidItemState("item_order", "manual_order_exhausted"))
            }
            rebalancedMinimum - SORT_POSITION_GAP
        }
    }

    private suspend fun resolveSortBounds(
        current: VaultItemEntity,
        previousItemId: String?,
        nextItemId: String?,
    ): SortBounds {
        val previous = previousItemId?.let { requireSortNeighbor(it, current.isPinned) }
        val next = nextItemId?.let { requireSortNeighbor(it, current.isPinned) }
        val lower = previous?.sortPosition ?: next?.let { neighbor ->
            itemDao.previousActiveNoteSortPosition(
                isPinned = current.isPinned,
                position = neighbor.sortPosition,
                excludedItemId = current.id,
            )
        }
        val upper = next?.sortPosition ?: previous?.let { neighbor ->
            itemDao.nextActiveNoteSortPosition(
                isPinned = current.isPinned,
                position = neighbor.sortPosition,
                excludedItemId = current.id,
            )
        }
        return SortBounds(lower, upper)
    }

    private suspend fun requireSortNeighbor(
        id: String,
        isPinned: Boolean,
    ): VaultItemEntity {
        val neighbor = requireItem(id)
        if (
            neighbor.type != VaultItemType.NOTE ||
            neighbor.deletedAt != null ||
            neighbor.isArchived ||
            neighbor.isPinned != isPinned
        ) {
            abort(AppError.InvalidInput("item_order", "invalid_neighbor"))
        }
        return neighbor
    }

    private fun positionBetween(bounds: SortBounds): Long? {
        val lower = bounds.lower
        val upper = bounds.upper
        return when {
            lower == null && upper == null -> null
            lower == null ->
                if (upper!! >= Long.MIN_VALUE + SORT_POSITION_GAP) {
                    upper - SORT_POSITION_GAP
                } else {
                    null
                }
            upper == null ->
                if (lower <= Long.MAX_VALUE - SORT_POSITION_GAP) {
                    lower + SORT_POSITION_GAP
                } else {
                    null
                }
            lower >= upper -> null
            else -> signedMidpoint(lower, upper).takeUnless { midpoint ->
                midpoint == lower || midpoint == upper
            }
        }
    }

    private suspend fun rebalanceActiveGroup(
        type: VaultItemType,
        isPinned: Boolean,
        now: Long,
    ) {
        val rows = itemDao.getActiveSortRows(type, isPinned)
        if (rows.size.toLong() > MAX_REBALANCE_ITEM_COUNT) {
            abort(AppError.InvalidItemState("item_order", "manual_order_too_large"))
        }
        rows.forEachIndexed { index, row ->
            if (row.localRevision == Long.MAX_VALUE) {
                abort(AppError.InvalidItemState(row.id, "local_revision_exhausted"))
            }
            val position = index.toLong() * SORT_POSITION_GAP
            if (
                row.sortPosition != position &&
                itemDao.updateSortPosition(row.id, position, row.localRevision + 1L) != 1
            ) {
                throw IllegalStateException("A sort update did not affect exactly one row")
            }
            if (row.sortPosition != position) {
                enqueueItemOperation(
                    itemId = row.id,
                    targetRevision = row.localRevision + 1L,
                    operationType = SyncOperationType.UPSERT_ITEM,
                    now = now,
                )
            }
        }
    }

    private suspend fun replaceTagRelations(
        itemId: String,
        requestedTags: List<TagInput>,
        now: Long,
    ): List<TagEntity> {
        val requestedNormalizedNames = requestedTags.map(TagInput::normalizedName)
        val existingByName = if (requestedNormalizedNames.isEmpty()) {
            emptyMap()
        } else {
            tagDao.getByNormalizedNames(requestedNormalizedNames)
                .associateBy(TagEntity::normalizedName)
        }
        val missing = requestedTags
            .filterNot { existingByName.containsKey(it.normalizedName) }
            .map { input ->
                TagEntity(
                    id = idGenerator.newId(),
                    name = input.displayName,
                    normalizedName = input.normalizedName,
                    createdAt = now,
                )
            }
        if (missing.isNotEmpty()) tagDao.insertTags(missing)

        val resolved = if (requestedNormalizedNames.isEmpty()) {
            emptyList()
        } else {
            tagDao.getByNormalizedNames(requestedNormalizedNames)
        }
        if (resolved.size != requestedNormalizedNames.size) {
            throw IllegalStateException("Failed to resolve all normalized tags")
        }

        tagDao.deleteCrossRefsForItem(itemId)
        if (resolved.isNotEmpty()) {
            tagDao.insertCrossRefs(resolved.map { ItemTagCrossRef(itemId = itemId, tagId = it.id) })
        }
        tagDao.deleteUnusedTags()
        return resolved
    }

    private suspend fun requireItem(id: String): VaultItemEntity =
        itemDao.getById(id) ?: abort(AppError.ItemNotFound(id))

    private suspend fun requireEditableItem(id: String): VaultItemEntity {
        val item = requireItem(id)
        if (item.deletedAt != null) abort(AppError.InvalidItemState(id, "in_trash"))
        return item
    }

    private suspend fun updateItemExactlyOnce(item: VaultItemEntity) {
        if (itemDao.update(item) != 1) {
            throw IllegalStateException("A single item update did not affect exactly one row")
        }
    }

    private suspend fun updateSearchDocument(item: VaultItemEntity, tags: List<TagEntity>) {
        val current = searchDao.getDocumentForItem(item.id)
        val tagText = tags.sortedBy(TagEntity::normalizedName).joinToString(separator = "\n") { it.name }
        val next = if (current == null) {
            SearchDocumentEntity(
                itemId = item.id,
                title = item.title,
                body = item.body,
                tags = tagText,
                attachmentFilenames = "",
                ocrText = item.ocrText,
            )
        } else {
            current.copy(
                title = item.title,
                body = item.body,
                tags = tagText,
                ocrText = item.ocrText,
            )
        }

        if (current == null) {
            val insertedRowId = searchDao.insertDocument(next)
            if (insertedRowId == INSERT_IGNORED) {
                val concurrentlyInserted = searchDao.getDocumentForItem(item.id)
                    ?: throw IllegalStateException("Search document insert was ignored without a row")
                if (searchDao.updateDocument(next.copy(rowId = concurrentlyInserted.rowId)) != 1) {
                    throw IllegalStateException("Search document update failed")
                }
            }
        } else if (searchDao.updateDocument(next) != 1) {
            throw IllegalStateException("Search document update failed")
        }
    }

    private suspend fun enqueueItemOperation(
        item: VaultItemEntity,
        operationType: SyncOperationType,
        now: Long,
    ) = enqueueItemOperation(
        itemId = item.id,
        targetRevision = item.localRevision,
        operationType = operationType,
        now = now,
    )

    private suspend fun enqueueItemOperation(
        itemId: String,
        targetRevision: Long,
        operationType: SyncOperationType,
        now: Long,
    ) {
        val dedupeKey = "$ITEM_DEDUPE_PREFIX$itemId"
        val operationId = idGenerator.newId()
        val updated = syncOperationDao.rotateAndRefresh(
            dedupeKey = dedupeKey,
            newOperationId = operationId,
            itemId = itemId,
            attachmentId = null,
            operationType = operationType,
            targetRevision = targetRevision,
            state = SyncOperationState.PENDING,
            now = now,
        )
        if (updated == 0) {
            syncOperationDao.insert(
                SyncOperationEntity(
                    operationId = operationId,
                    dedupeKey = dedupeKey,
                    itemId = itemId,
                    attachmentId = null,
                    operationType = operationType,
                    targetRevision = targetRevision,
                    state = SyncOperationState.PENDING,
                    attemptCount = 0,
                    nextAttemptAt = now,
                    leaseToken = null,
                    leaseExpiresAt = null,
                    createdAt = now,
                    updatedAt = now,
                    lastErrorCode = null,
                ),
            )
        }
    }

    private suspend fun <T> runMutation(
        operationName: String,
        block: suspend () -> MutationOutcome<T>,
    ): RepositoryResult<T> = withContext(dispatchers.io) {
        val outcome = try {
            database.withTransaction { block() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (aborted: RepositoryAbort) {
            return@withContext RepositoryResult.Failure(aborted.error)
        } catch (failure: Exception) {
            return@withContext RepositoryResult.Failure(
                AppError.DatabaseFailure(operationName, failure),
            )
        }

        val warning = if (outcome.changed) requestSyncWarning() else null
        RepositoryResult.Success(outcome.value, warning)
    }

    private fun requestSyncWarning(): AppError? {
        val scheduleResult = try {
            syncScheduler.requestSync()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            return AppError.SyncSchedulingFailure(SYNC_SCHEDULER_UNAVAILABLE)
        }
        return when (scheduleResult) {
            SyncScheduleResult.Scheduled,
            SyncScheduleResult.Coalesced,
            -> null

            is SyncScheduleResult.Rejected ->
                AppError.SyncSchedulingFailure(scheduleResult.reason)
        }
    }

    private fun normalizeTags(tagNames: Collection<String>): RepositoryResult<List<TagInput>> {
        val deduplicated = linkedMapOf<String, TagInput>()
        for (rawName in tagNames) {
            val displayName = collapseWhitespace(
                Normalizer.normalize(rawName, Normalizer.Form.NFKC).trim(),
            )
            if (displayName.isEmpty()) continue
            if (displayName.codePointCount(0, displayName.length) > MAX_TAG_NAME_CHARACTERS) {
                return RepositoryResult.Failure(
                    AppError.InvalidInput(
                        "tags",
                        "Tag names may contain at most $MAX_TAG_NAME_CHARACTERS characters",
                    ),
                )
            }
            if (displayName.any(Char::isISOControl)) {
                return RepositoryResult.Failure(
                    AppError.InvalidInput("tags", "Tag names cannot contain control characters"),
                )
            }
            val normalizedName = displayName.lowercase(Locale.ROOT)
            deduplicated.putIfAbsent(normalizedName, TagInput(displayName, normalizedName))
            if (deduplicated.size > MAX_TAGS_PER_ITEM) {
                return RepositoryResult.Failure(
                    AppError.InvalidInput("tags", "At most $MAX_TAGS_PER_ITEM tags are allowed"),
                )
            }
        }
        return RepositoryResult.Success(deduplicated.values.toList())
    }

    private suspend fun normalizeTagsOffMain(
        tagNames: Collection<String>,
    ): RepositoryResult<List<TagInput>> = withContext(dispatchers.default) {
        if (tagNames.size > MAX_RAW_TAG_INPUT_COUNT) {
            return@withContext RepositoryResult.Failure(
                AppError.InvalidInput("tags", "Too many tag entries"),
            )
        }
        var aggregateUtf16Units = 0L
        for (rawName in tagNames) {
            aggregateUtf16Units += rawName.length.toLong()
            if (aggregateUtf16Units > MAX_RAW_TAG_INPUT_UTF16_UNITS) {
                return@withContext RepositoryResult.Failure(
                    AppError.InvalidInput("tags", "Tag input is too large"),
                )
            }
            val unicodeFailure = validateTextField(
                field = "tags",
                value = rawName,
                maximumCodePoints = MAX_RAW_TAG_NAME_CODE_POINTS,
            )
            if (unicodeFailure != null) {
                return@withContext RepositoryResult.Failure(unicodeFailure)
            }
        }
        normalizeTags(tagNames)
    }

    private suspend fun validateNoteContentOffMain(
        title: String,
        body: String,
    ): AppError.InvalidInput? = withContext(dispatchers.default) {
        validateTextField(
            field = "title",
            value = title,
            maximumCodePoints = VaultConstraints.MAX_NOTE_TITLE_CHARACTERS,
        ) ?: validateTextField(
            field = "body",
            value = body,
            maximumCodePoints = VaultConstraints.MAX_NOTE_BODY_CHARACTERS,
        )
    }

    private fun validateDatedEntryDraft(draft: DatedEntryDraft): AppError.InvalidInput? {
        if (draft.occurrenceAtEpochMillis < 0L) {
            return AppError.InvalidInput("date", "invalid_occurrence")
        }
        if (draft.label.codePointCount(0, draft.label.length) > MAX_DATED_ENTRY_LABEL_CHARACTERS) {
            return AppError.InvalidInput("date_label", "too_long")
        }
        if (draft.label.any(Char::isISOControl)) {
            return AppError.InvalidInput("date_label", "contains_control_character")
        }
        if (runCatching { ZoneId.of(draft.timeZoneId) }.isFailure) {
            return AppError.InvalidInput("time_zone", "invalid")
        }
        val recurrence = draft.recurrence
        if (recurrence != null && recurrence.interval !in 1..MAX_RECURRENCE_INTERVAL) {
            return AppError.InvalidInput("recurrence", "interval_out_of_range")
        }
        if (
            draft.alertLeadTimesMinutes.size > MAX_ALERTS_PER_ENTRY ||
            draft.alertLeadTimesMinutes.any { it !in 0L..MAX_ALERT_LEAD_MINUTES }
        ) {
            return AppError.InvalidInput("alerts", "invalid_lead_time")
        }
        return null
    }

    private fun nextOccurrenceAfter(
        occurrenceAt: Long,
        timeZoneId: String,
        recurrence: RecurrenceRule,
        afterEpochMillis: Long,
    ): Long {
        var candidate = ZonedDateTime.ofInstant(
            Instant.ofEpochMilli(occurrenceAt),
            ZoneId.of(timeZoneId),
        )
        repeat(MAX_RECURRENCE_ADVANCES) {
            candidate = when (recurrence.unit) {
                RecurrenceUnit.DAY -> candidate.plusDays(recurrence.interval.toLong())
                RecurrenceUnit.WEEK -> candidate.plusWeeks(recurrence.interval.toLong())
                RecurrenceUnit.MONTH -> candidate.plusMonths(recurrence.interval.toLong())
                RecurrenceUnit.YEAR -> candidate.plusYears(recurrence.interval.toLong())
            }
            val next = candidate.toInstant().toEpochMilli()
            if (next > afterEpochMillis) return next
        }
        abort(AppError.InvalidItemState("recurrence", "advance_limit_exceeded"))
    }

    private fun validateTextField(
        field: String,
        value: String,
        maximumCodePoints: Int,
    ): AppError.InvalidInput? {
        var index = 0
        var codePoints = 0
        while (index < value.length) {
            val character = value[index]
            when {
                Character.isHighSurrogate(character) -> {
                    if (
                        index + 1 >= value.length ||
                        !Character.isLowSurrogate(value[index + 1])
                    ) {
                        return AppError.InvalidInput(field, "Text contains invalid Unicode")
                    }
                    index += 2
                }

                Character.isLowSurrogate(character) ->
                    return AppError.InvalidInput(field, "Text contains invalid Unicode")

                else -> index += 1
            }
            codePoints += 1
            if (codePoints > maximumCodePoints) {
                return AppError.InvalidInput(
                    field,
                    "Text is longer than $maximumCodePoints characters",
                )
            }
        }
        return null
    }

    private fun collapseWhitespace(value: String): String = buildString(value.length) {
        var previousWasWhitespace = false
        value.forEach { character ->
            if (character.isWhitespace()) {
                if (!previousWasWhitespace) append(' ')
                previousWasWhitespace = true
            } else {
                append(character)
                previousWasWhitespace = false
            }
        }
    }

    private fun boundedListLimit(limit: Int): Int =
        limit.coerceIn(1, VaultRepository.MAX_OBSERVED_ITEM_LIMIT)

    private fun VaultItemEntity.withLocalChange(now: Long): VaultItemEntity {
        if (localRevision == Long.MAX_VALUE) {
            abort(AppError.InvalidItemState(id, "local_revision_exhausted"))
        }
        return copy(
            updatedAt = maxOf(updatedAt, now),
            localRevision = localRevision + 1L,
            syncStatus = ItemSyncStatus.PENDING,
        )
    }

    private fun VaultItemSummaryWithTags.toDomain(): VaultItemSummary = VaultItemSummary(
        id = item.id,
        type = item.type,
        color = item.color,
        title = item.title,
        bodyPreview = item.bodyPreview,
        isPinned = item.isPinned,
        isFavorite = item.isFavorite,
        isArchived = item.isArchived,
        createdAtEpochMillis = item.createdAt,
        updatedAtEpochMillis = item.updatedAt,
        syncStatus = item.syncStatus,
        conflictOriginId = item.conflictOriginId,
        tags = tags.sortedBy(TagEntity::normalizedName).map { tag -> tag.toDomain() },
        nextDatedEntryAtEpochMillis = item.nextDatedEntryAt,
        hasOverdueEntry = item.hasOverdueEntry,
    )

    private fun VaultItemWithTags.toDomainNote(): VaultNote = VaultNote(
        id = item.id,
        title = item.title,
        body = item.body,
        color = item.color,
        ocrText = item.ocrText,
        isPinned = item.isPinned,
        isFavorite = item.isFavorite,
        isArchived = item.isArchived,
        createdAtEpochMillis = item.createdAt,
        updatedAtEpochMillis = item.updatedAt,
        localRevision = item.localRevision,
        remoteRevision = item.remoteRevision,
        lastSyncedRevision = item.lastSyncedRevision,
        serverVersionToken = item.serverVersionToken,
        syncStatus = item.syncStatus,
        deletedAtEpochMillis = item.deletedAt,
        conflictOriginId = item.conflictOriginId,
        tags = tags.sortedBy(TagEntity::normalizedName).map { tag -> tag.toDomain() },
        bodyDocument = NoteBodyCodec.decodeOrNull(item.bodyDocumentJson),
        datedEntries = datedEntries
            .sortedWith(
                compareBy<DatedEntryWithAlerts> { it.entry.completedAt != null }
                    .thenBy { it.entry.occurrenceAt }
                    .thenBy { it.entry.id },
            )
            .map { row -> row.toDomain() },
    )

    private fun DatedEntryWithAlerts.toDomain(): DatedEntry = DatedEntry(
        id = entry.id,
        itemId = entry.itemId,
        type = entry.type,
        label = entry.label,
        occurrenceAtEpochMillis = entry.occurrenceAt,
        isAllDay = entry.isAllDay,
        timeZoneId = entry.timeZoneId,
        recurrence = entry.recurrence(),
        completedAtEpochMillis = entry.completedAt,
        createdAtEpochMillis = entry.createdAt,
        updatedAtEpochMillis = entry.updatedAt,
        alerts = alerts.sortedBy(DatedEntryAlertEntity::leadTimeMinutes).map { alert ->
            DatedEntryAlert(
                id = alert.id,
                leadTimeMinutes = alert.leadTimeMinutes,
                snoozedUntilEpochMillis = alert.snoozedUntil,
                lastDeliveredOccurrenceEpochMillis = alert.lastDeliveredOccurrence,
            )
        },
    )

    private fun AgendaEntryRow.toDomain(): AgendaEntry = AgendaEntry(
        entry = DatedEntryWithAlerts(entry, alerts).toDomain(),
        noteTitle = noteTitle,
        noteColor = noteColor,
        isArchived = noteIsArchived,
    )

    private fun DatedEntryEntity.recurrence(): RecurrenceRule? {
        val unit = recurrenceUnit ?: return null
        val interval = recurrenceInterval ?: return null
        return RecurrenceRule(interval = interval, unit = unit)
    }

    private fun TagEntity.toDomain(): VaultTag = VaultTag(id = id, name = name)

    private data class MutationOutcome<T>(val value: T, val changed: Boolean)

    private data class TagInput(val displayName: String, val normalizedName: String)

    private data class SortBounds(val lower: Long?, val upper: Long?)

    private class RepositoryAbort(val error: AppError) :
        RuntimeException(null, null, false, false)

    private fun abort(error: AppError): Nothing = throw RepositoryAbort(error)

    private companion object {
        const val INITIAL_LOCAL_REVISION: Long = 1L
        const val INSERT_IGNORED: Long = -1L
        const val BODY_PREVIEW_CHARACTER_LIMIT: Int = 240
        const val MAX_TAG_NAME_CHARACTERS: Int = 64
        const val MAX_TAGS_PER_ITEM: Int = 64
        const val MAX_RAW_TAG_NAME_CODE_POINTS: Int = 256
        const val MAX_RAW_TAG_INPUT_COUNT: Int = 4_225
        const val MAX_RAW_TAG_INPUT_UTF16_UNITS: Long = 16_896L
        const val ITEM_DEDUPE_PREFIX: String = "item:"
        const val SYNC_SCHEDULER_UNAVAILABLE: String = "sync_scheduler_unavailable"
        const val OPERATION_CREATE_NOTE: String = "create_note"
        const val OPERATION_SAVE_NOTE: String = "save_note"
        const val OPERATION_SET_PINNED: String = "set_pinned"
        const val OPERATION_REORDER_ITEM: String = "reorder_item"
        const val OPERATION_SET_FAVORITE: String = "set_favorite"
        const val OPERATION_SET_COLOR: String = "set_color"
        const val OPERATION_SET_ARCHIVED: String = "set_archived"
        const val OPERATION_MOVE_TO_TRASH: String = "move_to_trash"
        const val OPERATION_RESTORE: String = "restore"
        const val OPERATION_SET_TAGS: String = "set_tags"
        const val OPERATION_SAVE_DATED_ENTRY: String = "save_dated_entry"
        const val OPERATION_DELETE_DATED_ENTRY: String = "delete_dated_entry"
        const val OPERATION_COMPLETE_DATED_ENTRY: String = "complete_dated_entry"
        const val OPERATION_SNOOZE_ALERT: String = "snooze_dated_entry_alert"
        const val OPERATION_DELIVER_ALERT: String = "deliver_dated_entry_alert"
        const val MAX_AGENDA_ENTRIES: Int = 500
        const val MAX_DATED_ENTRY_LABEL_CHARACTERS: Int = 200
        const val MAX_ALERTS_PER_ENTRY: Int = 8
        const val MAX_ALERT_LEAD_MINUTES: Long = 10L * 365L * 24L * 60L
        const val MAX_RECURRENCE_INTERVAL: Int = 999
        const val MAX_RECURRENCE_ADVANCES: Int = 10_000
        const val SORT_POSITION_GAP: Long = 1_000_000_000_000L
        const val MAX_REBALANCE_ITEM_COUNT: Long = 100_000L

        fun signedMidpoint(lower: Long, upper: Long): Long =
            (lower and upper) + ((lower xor upper) shr 1)
    }
}
