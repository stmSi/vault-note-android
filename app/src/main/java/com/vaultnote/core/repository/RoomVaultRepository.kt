package com.vaultnote.core.repository

import androidx.room.withTransaction
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.Clock
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.IdGenerator
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.VaultConstraints
import com.vaultnote.core.common.model.ItemSyncStatus
import com.vaultnote.core.common.model.SyncOperationState
import com.vaultnote.core.common.model.SyncOperationType
import com.vaultnote.core.common.model.VaultItemSummary
import com.vaultnote.core.common.model.VaultItemType
import com.vaultnote.core.common.model.VaultNote
import com.vaultnote.core.common.model.VaultTag
import com.vaultnote.core.database.VaultDatabase
import com.vaultnote.core.database.entity.ItemTagCrossRef
import com.vaultnote.core.database.entity.SearchDocumentEntity
import com.vaultnote.core.database.entity.SyncOperationEntity
import com.vaultnote.core.database.entity.TagEntity
import com.vaultnote.core.database.entity.VaultItemEntity
import com.vaultnote.core.database.model.VaultItemSummaryWithTags
import com.vaultnote.core.database.model.VaultItemWithTags
import com.vaultnote.core.sync.SyncScheduleResult
import com.vaultnote.core.sync.SyncScheduler
import java.text.Normalizer
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
) : VaultRepository {
    private val itemDao = database.vaultItemDao()
    private val tagDao = database.tagDao()
    private val syncOperationDao = database.syncOperationDao()
    private val searchDao = database.searchDao()

    override fun observeActiveItems(limit: Int, offset: Int): Flow<List<VaultItemSummary>> {
        val boundedLimit = boundedListLimit(limit)
        return itemDao.observeActiveSummaries(
            boundedLimit,
            offset.coerceAtLeast(0),
            BODY_PREVIEW_CHARACTER_LIMIT,
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

    override suspend fun createNote(title: String, body: String): RepositoryResult<String> {
        validateNoteContentOffMain(title, body)?.let { return RepositoryResult.Failure(it) }
        return runMutation(OPERATION_CREATE_NOTE) {
            val now = clock.nowEpochMillis()
            val item = VaultItemEntity(
                id = idGenerator.newId(),
                type = VaultItemType.NOTE,
                title = title,
                body = body,
                ocrText = "",
                isPinned = false,
                isFavorite = false,
                isArchived = false,
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
        updateBooleanProperty(
            id = id,
            operationName = OPERATION_SET_PINNED,
            isUnchanged = { it.isPinned == isPinned },
            transform = { item -> item.copy(isPinned = isPinned) },
        )

    override suspend fun setFavorite(id: String, isFavorite: Boolean): RepositoryResult<Unit> =
        updateBooleanProperty(
            id = id,
            operationName = OPERATION_SET_FAVORITE,
            isUnchanged = { it.isFavorite == isFavorite },
            transform = { item -> item.copy(isFavorite = isFavorite) },
        )

    override suspend fun setArchived(id: String, isArchived: Boolean): RepositoryResult<Unit> =
        updateBooleanProperty(
            id = id,
            operationName = OPERATION_SET_ARCHIVED,
            isUnchanged = { it.isArchived == isArchived },
            transform = { item -> item.copy(isArchived = isArchived) },
        )

    override suspend fun moveToTrash(id: String): RepositoryResult<Unit> =
        runMutation(OPERATION_MOVE_TO_TRASH) {
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

    override suspend fun restore(id: String): RepositoryResult<Unit> =
        runMutation(OPERATION_RESTORE) {
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
    ) {
        val dedupeKey = "$ITEM_DEDUPE_PREFIX${item.id}"
        val operationId = idGenerator.newId()
        val updated = syncOperationDao.rotateAndRefresh(
            dedupeKey = dedupeKey,
            newOperationId = operationId,
            itemId = item.id,
            attachmentId = null,
            operationType = operationType,
            targetRevision = item.localRevision,
            state = SyncOperationState.PENDING,
            now = now,
        )
        if (updated == 0) {
            syncOperationDao.insert(
                SyncOperationEntity(
                    operationId = operationId,
                    dedupeKey = dedupeKey,
                    itemId = item.id,
                    attachmentId = null,
                    operationType = operationType,
                    targetRevision = item.localRevision,
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
        title = item.title,
        bodyPreview = item.bodyPreview,
        isPinned = item.isPinned,
        isFavorite = item.isFavorite,
        isArchived = item.isArchived,
        createdAtEpochMillis = item.createdAt,
        updatedAtEpochMillis = item.updatedAt,
        syncStatus = item.syncStatus,
        tags = tags.sortedBy(TagEntity::normalizedName).map { tag -> tag.toDomain() },
    )

    private fun VaultItemWithTags.toDomainNote(): VaultNote = VaultNote(
        id = item.id,
        title = item.title,
        body = item.body,
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
    )

    private fun TagEntity.toDomain(): VaultTag = VaultTag(id = id, name = name)

    private data class MutationOutcome<T>(val value: T, val changed: Boolean)

    private data class TagInput(val displayName: String, val normalizedName: String)

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
        const val OPERATION_SET_FAVORITE: String = "set_favorite"
        const val OPERATION_SET_ARCHIVED: String = "set_archived"
        const val OPERATION_MOVE_TO_TRASH: String = "move_to_trash"
        const val OPERATION_RESTORE: String = "restore"
        const val OPERATION_SET_TAGS: String = "set_tags"
    }
}
