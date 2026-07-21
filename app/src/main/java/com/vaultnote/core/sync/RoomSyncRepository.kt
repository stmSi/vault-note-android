package com.vaultnote.core.sync

import androidx.room.withTransaction
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.Clock
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.IdGenerator
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.AttachmentUploadStatus
import com.vaultnote.core.common.model.ItemSyncStatus
import com.vaultnote.core.common.model.SyncOperationState
import com.vaultnote.core.common.model.SyncOperationType
import com.vaultnote.core.common.model.VaultItemSummary
import com.vaultnote.core.database.VaultDatabase
import com.vaultnote.core.database.entity.ItemTagCrossRef
import com.vaultnote.core.database.entity.SearchDocumentEntity
import com.vaultnote.core.database.entity.SyncOperationEntity
import com.vaultnote.core.database.entity.SyncStateEntity
import com.vaultnote.core.database.entity.TagEntity
import com.vaultnote.core.database.entity.VaultItemEntity
import com.vaultnote.core.database.model.VaultItemSummaryWithTags
import com.vaultnote.core.files.AttachmentFileManager
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomSyncRepository(
    private val database: VaultDatabase,
    private val syncApi: SyncApi,
    private val authProvider: AuthProvider,
    private val remoteFileStore: RemoteFileStore,
    private val fileManager: AttachmentFileManager,
    private val syncScheduler: SyncScheduler,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val backoffPolicy: ExponentialBackoffPolicy = ExponentialBackoffPolicy(),
) : SyncRepository {
    private val itemDao = database.vaultItemDao()
    private val attachmentDao = database.attachmentDao()
    private val tagDao = database.tagDao()
    private val operationDao = database.syncOperationDao()
    private val stateDao = database.syncStateDao()
    private val searchDao = database.searchDao()

    override fun observeOverview(): Flow<SyncOverview> = combine(
        operationDao.observeQueueStatus(),
        stateDao.observe(DEFAULT_SYNC_SCOPE),
        itemDao.observeConflictSummaries(MAX_CONFLICTS, BODY_PREVIEW_CHARACTER_LIMIT),
    ) { queue, state, conflicts ->
        SyncOverview(
            pendingCount = queue.pendingCount,
            runningCount = queue.runningCount,
            retryCount = queue.retryCount,
            failedCount = queue.failedCount,
            conflictCount = conflicts.size,
            lastAttemptAtEpochMillis = state?.lastAttemptAt,
            lastSuccessAtEpochMillis = state?.lastSuccessAt,
        )
    }.flowOn(dispatchers.io)

    override fun observeConflicts(limit: Int): Flow<List<VaultItemSummary>> =
        itemDao.observeConflictSummaries(
            limit.coerceIn(1, MAX_CONFLICTS),
            BODY_PREVIEW_CHARACTER_LIMIT,
        ).map { rows -> rows.map { row -> row.toDomain() } }
            .flowOn(dispatchers.io)

    override suspend fun synchronize(maxOperations: Int): SyncRunResult = withContext(dispatchers.io) {
        val boundedMaximum = maxOperations.coerceIn(1, MAX_OPERATIONS_PER_RUN)
        if (authProvider.authenticationState() != AuthenticationState.AUTHENTICATED) {
            recordAttempt(success = false)
            return@withContext SyncRunResult.AuthenticationRequired
        }
        recordAttempt(success = false)
        var processed = 0
        while (processed < boundedMaximum) {
            val claimed = claimNextOperation() ?: break
            when (process(claimed)) {
                ProcessOutcome.Continue -> processed += 1
                ProcessOutcome.Retry -> {
                    return@withContext SyncRunResult.RetryRequired(processed + 1)
                }
                ProcessOutcome.AuthenticationRequired -> {
                    return@withContext SyncRunResult.AuthenticationRequired
                }
            }
        }

        if (processed == boundedMaximum) {
            return@withContext SyncRunResult.RetryRequired(processed)
        }

        when (pullRemoteChanges()) {
            PullOutcome.Complete -> Unit
            PullOutcome.Retry -> return@withContext SyncRunResult.RetryRequired(processed)
            PullOutcome.AuthenticationRequired -> return@withContext SyncRunResult.AuthenticationRequired
        }
        recordAttempt(success = true)
        SyncRunResult.Completed(processed)
    }

    override suspend fun resolveConflict(
        selectedItemId: String,
    ): RepositoryResult<Unit> = withContext(dispatchers.io) {
        if (selectedItemId.isBlank()) {
            return@withContext RepositoryResult.Failure(
                AppError.InvalidInput("item_id", "required"),
            )
        }
        try {
            database.withTransaction {
                val selected = itemDao.getById(selectedItemId)
                    ?: return@withTransaction RepositoryResult.Failure(
                        AppError.ItemNotFound(selectedItemId),
                    )
                if (selected.syncStatus != ItemSyncStatus.CONFLICT) {
                    return@withTransaction RepositoryResult.Failure(
                        AppError.InvalidItemState(selectedItemId, "not_a_conflict"),
                    )
                }
                val originId = selected.conflictOriginId ?: selected.id
                val origin = itemDao.getById(originId)
                    ?: return@withTransaction RepositoryResult.Failure(AppError.ItemNotFound(originId))
                val selectedTags = tagDao.getTagsForItem(selected.id).map(TagEntity::name)
                val now = clock.nowEpochMillis()
                val resolved = origin.copy(
                    type = selected.type,
                    title = selected.title,
                    body = selected.body,
                    ocrText = selected.ocrText,
                    color = selected.color,
                    isPinned = selected.isPinned,
                    isFavorite = selected.isFavorite,
                    isArchived = selected.isArchived,
                    updatedAt = maxOf(now, origin.updatedAt),
                    localRevision = origin.localRevision.checkedIncrement(),
                    remoteRevision = selected.remoteRevision ?: origin.remoteRevision,
                    serverVersionToken = selected.serverVersionToken ?: origin.serverVersionToken,
                    syncStatus = ItemSyncStatus.PENDING,
                    deletedAt = null,
                    conflictOriginId = null,
                )
                if (itemDao.update(resolved) != 1) error("Conflict origin update failed")
                replaceTags(originId, selectedTags, now)
                updateSearchDocument(resolved)
                itemDao.getConflictGroup(originId)
                    .asSequence()
                    .filter { it.id != originId }
                    .forEach { copy ->
                        searchDao.deleteDocumentForItem(copy.id)
                        itemDao.deleteById(copy.id)
                    }
                operationDao.deleteItemMetadataOperations(originId)
                enqueueItemOperation(resolved, now)
                RepositoryResult.Success(Unit)
            }.also { result ->
                if (result is RepositoryResult.Success) syncScheduler.requestSync()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            RepositoryResult.Failure(AppError.DatabaseFailure("resolve_sync_conflict", failure))
        }
    }

    private suspend fun claimNextOperation(): ClaimedOperation? = database.withTransaction {
        val now = clock.nowEpochMillis()
        operationDao.recoverExpiredLeases(now)
        val operation = operationDao.getReadyOperations(
            states = READY_STATES,
            now = now,
            limit = 1,
        ).firstOrNull() ?: return@withTransaction null
        val leaseToken = idGenerator.newId()
        val attempt = operation.attemptCount + 1
        if (operationDao.updateAttemptState(
                operationId = operation.operationId,
                state = SyncOperationState.RUNNING,
                attemptCount = attempt,
                nextAttemptAt = now,
                leaseToken = leaseToken,
                leaseExpiresAt = now + LEASE_DURATION_MILLIS,
                updatedAt = now,
                lastErrorCode = null,
            ) != 1
        ) {
            return@withTransaction null
        }
        val item = operation.itemId?.let { itemDao.getById(it) }
        if (item != null && item.syncStatus != ItemSyncStatus.CONFLICT) {
            itemDao.update(item.copy(syncStatus = ItemSyncStatus.SYNCING))
        }
        val attachment = operation.attachmentId?.let { attachmentDao.getById(it) }
        if (attachment != null && operation.operationType == SyncOperationType.UPLOAD_ATTACHMENT) {
            attachmentDao.updateRemoteState(
                attachment.id,
                AttachmentUploadStatus.UPLOADING,
                attachment.remotePath,
            )
        }
        ClaimedOperation(
            operation = operation.copy(
                state = SyncOperationState.RUNNING,
                attemptCount = attempt,
                leaseToken = leaseToken,
                leaseExpiresAt = now + LEASE_DURATION_MILLIS,
            ),
            item = item,
            attachment = attachment,
            attachments = item?.let { attachmentDao.getForItem(it.id) }.orEmpty(),
            tags = item?.let { tagDao.getTagsForItem(it.id) }.orEmpty(),
        )
    }

    private suspend fun process(claimed: ClaimedOperation): ProcessOutcome = when (
        claimed.operation.operationType
    ) {
        SyncOperationType.UPLOAD_ATTACHMENT -> uploadAttachment(claimed)
        SyncOperationType.DELETE_ATTACHMENT -> deleteAttachment(claimed)
        SyncOperationType.UPSERT_ITEM -> upsertItem(claimed)
        SyncOperationType.DELETE_ITEM -> deleteItem(claimed)
    }

    private suspend fun uploadAttachment(claimed: ClaimedOperation): ProcessOutcome {
        val attachment = claimed.attachment ?: return completeStale(claimed)
        val file = when (val resolved = fileManager.resolveAttachmentPath(attachment.localEncryptedPath)) {
            is RepositoryResult.Success -> resolved.value
            is RepositoryResult.Failure -> return fail(
                claimed,
                RemoteErrorCode.CORRUPTED_UPLOAD,
            )
        }
        return when (
            val result = remoteFileStore.uploadEncrypted(
                operationId = claimed.operation.operationId,
                attachmentId = attachment.id,
                plaintextSha256 = attachment.sha256Checksum,
                source = file,
            )
        ) {
            is RemoteFileResult.Uploaded -> {
                if (!remoteFileStore.verifyUpload(result.remotePath, attachment.sha256Checksum)) {
                    fail(claimed, RemoteErrorCode.CORRUPTED_UPLOAD)
                } else {
                    database.withTransaction {
                        attachmentDao.updateRemoteState(
                            attachment.id,
                            AttachmentUploadStatus.UPLOADED,
                            result.remotePath,
                        )
                        completeOperation(claimed, serverResult = null)
                    }
                    ProcessOutcome.Continue
                }
            }
            is RemoteFileResult.Failure -> fail(claimed, result.code)
            RemoteFileResult.Deleted -> fail(claimed, RemoteErrorCode.INVALID_REQUEST)
        }
    }

    private suspend fun deleteAttachment(claimed: ClaimedOperation): ProcessOutcome {
        val attachmentId = claimed.operation.attachmentId ?: return completeStale(claimed)
        return when (
            val result = remoteFileStore.delete(claimed.operation.operationId, attachmentId)
        ) {
            RemoteFileResult.Deleted,
            is RemoteFileResult.Uploaded,
            -> {
                database.withTransaction { completeOperation(claimed, serverResult = null) }
                ProcessOutcome.Continue
            }
            is RemoteFileResult.Failure -> fail(claimed, result.code)
        }
    }

    private suspend fun upsertItem(claimed: ClaimedOperation): ProcessOutcome {
        val item = claimed.item ?: return completeStale(claimed)
        val attachmentReferences = claimed.attachments.map { attachment ->
            val remotePath = attachment.remotePath
                ?: return fail(claimed, RemoteErrorCode.SERVER_UNAVAILABLE)
            RemoteAttachmentReference(
                id = attachment.id,
                remotePath = remotePath,
                mimeType = attachment.mimeType,
                fileSizeBytes = attachment.fileSize,
                plaintextSha256 = attachment.sha256Checksum,
                encryptionFormatVersion = attachment.encryptionFormatVersion,
            )
        }
        val metadata = RemoteItemMetadata(
            id = item.id,
            type = item.type,
            title = item.title,
            body = item.body,
            ocrText = item.ocrText,
            color = item.color,
            isPinned = item.isPinned,
            isFavorite = item.isFavorite,
            isArchived = item.isArchived,
            createdAtEpochMillis = item.createdAt,
            updatedAtEpochMillis = item.updatedAt,
            clientRevision = claimed.operation.targetRevision,
            tags = claimed.tags.map(TagEntity::name),
            attachments = attachmentReferences,
        )
        return handleMutationResult(
            claimed,
            syncApi.upsertItem(
                operationId = claimed.operation.operationId,
                item = metadata,
                expectedVersionToken = item.serverVersionToken,
            ),
        )
    }

    private suspend fun deleteItem(claimed: ClaimedOperation): ProcessOutcome {
        val item = claimed.item ?: return completeStale(claimed)
        return handleMutationResult(
            claimed,
            syncApi.deleteItem(
                operationId = claimed.operation.operationId,
                itemId = item.id,
                expectedVersionToken = item.serverVersionToken,
            ),
        )
    }

    private suspend fun handleMutationResult(
        claimed: ClaimedOperation,
        result: RemoteMutationResult,
    ): ProcessOutcome = when (result) {
        is RemoteMutationResult.Applied -> {
            database.withTransaction { completeOperation(claimed, result) }
            ProcessOutcome.Continue
        }
        is RemoteMutationResult.Conflict -> {
            preserveConflict(claimed, result.remote)
            ProcessOutcome.Continue
        }
        is RemoteMutationResult.Failure -> fail(claimed, result.code)
    }

    private suspend fun completeStale(claimed: ClaimedOperation): ProcessOutcome {
        database.withTransaction { operationDao.deleteById(claimed.operation.operationId) }
        return ProcessOutcome.Continue
    }

    private suspend fun completeOperation(
        claimed: ClaimedOperation,
        serverResult: RemoteMutationResult.Applied?,
    ) {
        operationDao.deleteById(claimed.operation.operationId)
        val itemId = claimed.operation.itemId ?: return
        val current = itemDao.getById(itemId) ?: return
        var updated = current
        if (
            serverResult != null &&
            current.serverVersionToken == claimed.item?.serverVersionToken
        ) {
            updated = updated.copy(
                remoteRevision = serverResult.serverRevision,
                lastSyncedRevision = claimed.operation.targetRevision,
                serverVersionToken = serverResult.versionToken,
            )
        }
        val outstanding = operationDao.countOutstandingForItem(itemId)
        val status = when {
            updated.syncStatus == ItemSyncStatus.CONFLICT -> ItemSyncStatus.CONFLICT
            outstanding > 0L || updated.localRevision > claimed.operation.targetRevision ->
                ItemSyncStatus.PENDING
            else -> ItemSyncStatus.SYNCED
        }
        if (updated != current || current.syncStatus != status) {
            itemDao.update(updated.copy(syncStatus = status))
        }
    }

    private suspend fun fail(
        claimed: ClaimedOperation,
        code: RemoteErrorCode,
    ): ProcessOutcome {
        if (code == RemoteErrorCode.AUTHENTICATION_EXPIRED) {
            markFailure(claimed, code, RetryDecision.Permanent)
            return ProcessOutcome.AuthenticationRequired
        }
        val decision = backoffPolicy.decision(claimed.operation.attemptCount - 1, code)
        markFailure(claimed, code, decision)
        return if (decision is RetryDecision.RetryAfter) {
            ProcessOutcome.Retry
        } else {
            ProcessOutcome.Continue
        }
    }

    private suspend fun markFailure(
        claimed: ClaimedOperation,
        code: RemoteErrorCode,
        decision: RetryDecision,
    ) = database.withTransaction {
        val currentOperation = operationDao.getById(claimed.operation.operationId)
            ?: return@withTransaction
        val now = clock.nowEpochMillis()
        val retryAt = when (decision) {
            is RetryDecision.RetryAfter -> now + decision.delayMillis
            RetryDecision.Permanent -> now
        }
        val state = if (decision is RetryDecision.RetryAfter) {
            SyncOperationState.RETRY_WAIT
        } else {
            SyncOperationState.FAILED_PERMANENT
        }
        operationDao.updateAttemptState(
            operationId = currentOperation.operationId,
            state = state,
            attemptCount = claimed.operation.attemptCount,
            nextAttemptAt = retryAt,
            leaseToken = null,
            leaseExpiresAt = null,
            updatedAt = now,
            lastErrorCode = code.name.lowercase(Locale.ROOT),
        )
        claimed.operation.attachmentId?.let { attachmentId ->
            val attachment = attachmentDao.getById(attachmentId)
            if (attachment != null) {
                attachmentDao.updateRemoteState(
                    attachmentId,
                    if (decision is RetryDecision.RetryAfter) {
                        AttachmentUploadStatus.FAILED_RETRYABLE
                    } else {
                        AttachmentUploadStatus.FAILED_PERMANENT
                    },
                    attachment.remotePath,
                )
            }
        }
        claimed.operation.itemId?.let { itemId ->
            val item = itemDao.getById(itemId)
            if (item != null && item.syncStatus != ItemSyncStatus.CONFLICT) {
                itemDao.update(
                    item.copy(
                        syncStatus = if (decision is RetryDecision.RetryAfter) {
                            ItemSyncStatus.PENDING
                        } else {
                            ItemSyncStatus.FAILED
                        },
                    ),
                )
            }
        }
    }

    private suspend fun preserveConflict(
        claimed: ClaimedOperation,
        remote: RemoteItemVersion?,
    ) = database.withTransaction {
        val itemId = claimed.operation.itemId ?: return@withTransaction
        val local = itemDao.getById(itemId) ?: return@withTransaction
        val localConflict = local.copy(
            remoteRevision = remote?.serverRevision ?: local.remoteRevision,
            serverVersionToken = remote?.versionToken ?: local.serverVersionToken,
            syncStatus = ItemSyncStatus.CONFLICT,
        )
        itemDao.update(localConflict)
        operationDao.deleteItemMetadataOperations(itemId)
        if (remote != null) insertRemoteConflictCopy(itemId, remote)
    }

    private suspend fun insertRemoteConflictCopy(
        originId: String,
        remote: RemoteItemVersion,
    ) {
        val existing = itemDao.getConflictGroup(originId)
            .any { it.conflictOriginId == originId && it.serverVersionToken == remote.versionToken }
        if (existing) return
        val metadata = remote.metadata
        val now = clock.nowEpochMillis()
        val copy = VaultItemEntity(
            id = idGenerator.newId(),
            type = metadata.type,
            color = metadata.color,
            title = metadata.title,
            body = metadata.body,
            ocrText = metadata.ocrText,
            isPinned = metadata.isPinned,
            isFavorite = metadata.isFavorite,
            isArchived = metadata.isArchived,
            createdAt = now,
            updatedAt = maxOf(now, metadata.updatedAtEpochMillis),
            localRevision = metadata.clientRevision.coerceAtLeast(1L),
            remoteRevision = remote.serverRevision,
            lastSyncedRevision = metadata.clientRevision,
            serverVersionToken = remote.versionToken,
            syncStatus = ItemSyncStatus.CONFLICT,
            deletedAt = null,
            conflictOriginId = originId,
        )
        itemDao.insert(copy)
        replaceTags(copy.id, metadata.tags, now)
        updateSearchDocument(copy)
    }

    private suspend fun pullRemoteChanges(): PullOutcome {
        var state = stateDao.get(DEFAULT_SYNC_SCOPE) ?: emptySyncState()
        repeat(MAX_PULL_PAGES_PER_RUN) {
            when (val result = syncApi.pullChanges(state.incrementalCursor, PULL_PAGE_SIZE)) {
                is RemotePullResult.Failure -> return when {
                    result.code == RemoteErrorCode.AUTHENTICATION_EXPIRED ->
                        PullOutcome.AuthenticationRequired
                    result.code.retryable -> PullOutcome.Retry
                    else -> PullOutcome.Complete
                }
                is RemotePullResult.Success -> {
                    database.withTransaction {
                        result.page.changes.forEach { change -> applyRemoteChange(change) }
                        state = state.copy(
                            incrementalCursor = result.page.nextCursor,
                            serverRevision = result.page.changes.maxOfOrNull {
                                change -> change.serverRevision
                            } ?: state.serverRevision,
                        )
                        stateDao.upsert(state)
                    }
                    if (!result.page.hasMore) return PullOutcome.Complete
                }
            }
        }
        return PullOutcome.Retry
    }

    private suspend fun applyRemoteChange(change: RemoteChange) {
        when (change) {
            is RemoteChange.Upsert -> applyRemoteUpsert(change.item)
            is RemoteChange.Delete -> applyRemoteDelete(change)
        }
    }

    private suspend fun applyRemoteUpsert(remote: RemoteItemVersion) {
        val metadata = remote.metadata
        val local = itemDao.getById(metadata.id)
        if (local == null) {
            val imported = VaultItemEntity(
                id = metadata.id,
                type = metadata.type,
                color = metadata.color,
                title = metadata.title,
                body = metadata.body,
                ocrText = metadata.ocrText,
                isPinned = metadata.isPinned,
                isFavorite = metadata.isFavorite,
                isArchived = metadata.isArchived,
                createdAt = metadata.createdAtEpochMillis,
                updatedAt = metadata.updatedAtEpochMillis,
                localRevision = metadata.clientRevision.coerceAtLeast(1L),
                remoteRevision = remote.serverRevision,
                lastSyncedRevision = metadata.clientRevision.coerceAtLeast(1L),
                serverVersionToken = remote.versionToken,
                syncStatus = ItemSyncStatus.SYNCED,
                deletedAt = null,
                conflictOriginId = null,
            )
            itemDao.insert(imported)
            replaceTags(imported.id, metadata.tags, clock.nowEpochMillis())
            updateSearchDocument(imported)
            return
        }
        if (local.serverVersionToken == remote.versionToken) return
        val hasLocalChanges = local.lastSyncedRevision == null ||
            local.localRevision != local.lastSyncedRevision
        if (hasLocalChanges) {
            val claimed = ClaimedOperation.synthetic(local)
            preserveConflict(claimed, remote)
            return
        }
        val updated = local.copy(
            type = metadata.type,
            color = metadata.color,
            title = metadata.title,
            body = metadata.body,
            ocrText = metadata.ocrText,
            isPinned = metadata.isPinned,
            isFavorite = metadata.isFavorite,
            isArchived = metadata.isArchived,
            updatedAt = metadata.updatedAtEpochMillis,
            remoteRevision = remote.serverRevision,
            lastSyncedRevision = local.localRevision,
            serverVersionToken = remote.versionToken,
            syncStatus = ItemSyncStatus.SYNCED,
            deletedAt = null,
        )
        itemDao.update(updated)
        replaceTags(updated.id, metadata.tags, clock.nowEpochMillis())
        updateSearchDocument(updated)
    }

    private suspend fun applyRemoteDelete(change: RemoteChange.Delete) {
        val local = itemDao.getById(change.itemId) ?: return
        if (local.serverVersionToken == change.versionToken && local.deletedAt != null) return
        val hasLocalChanges = local.lastSyncedRevision == null ||
            local.localRevision != local.lastSyncedRevision
        if (hasLocalChanges) {
            itemDao.update(
                local.copy(
                    remoteRevision = change.serverRevision,
                    serverVersionToken = change.versionToken,
                    syncStatus = ItemSyncStatus.CONFLICT,
                ),
            )
            operationDao.deleteItemMetadataOperations(local.id)
        } else {
            itemDao.update(
                local.copy(
                    remoteRevision = change.serverRevision,
                    serverVersionToken = change.versionToken,
                    syncStatus = ItemSyncStatus.SYNCED,
                    deletedAt = clock.nowEpochMillis(),
                ),
            )
        }
    }

    private suspend fun replaceTags(itemId: String, names: List<String>, now: Long) {
        val normalized = names.asSequence()
            .map { Normalizer.normalize(it, Normalizer.Form.NFKC).trim() }
            .filter { it.isNotEmpty() && it.none(Char::isISOControl) }
            .map { display -> display.takeCodePoints(MAX_TAG_CODE_POINTS) }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(MAX_TAGS)
            .toList()
        val normalizedNames = normalized.map { it.lowercase(Locale.ROOT) }
        val existing = if (normalizedNames.isEmpty()) {
            emptyMap()
        } else {
            tagDao.getByNormalizedNames(normalizedNames).associateBy(TagEntity::normalizedName)
        }
        val missing = normalized.filterNot { existing.containsKey(it.lowercase(Locale.ROOT)) }
            .map { display ->
                TagEntity(idGenerator.newId(), display, display.lowercase(Locale.ROOT), now)
            }
        if (missing.isNotEmpty()) tagDao.insertTags(missing)
        val resolved = if (normalizedNames.isEmpty()) {
            emptyList()
        } else {
            tagDao.getByNormalizedNames(normalizedNames)
        }
        tagDao.deleteCrossRefsForItem(itemId)
        if (resolved.isNotEmpty()) {
            tagDao.insertCrossRefs(resolved.map { ItemTagCrossRef(itemId, it.id) })
        }
        tagDao.deleteUnusedTags()
    }

    private suspend fun updateSearchDocument(item: VaultItemEntity) {
        val existing = searchDao.getDocumentForItem(item.id)
        val tags = tagDao.getTagsForItem(item.id).joinToString("\n", transform = TagEntity::name)
        val next = SearchDocumentEntity(
            rowId = existing?.rowId ?: 0L,
            itemId = item.id,
            title = item.title,
            body = item.body,
            tags = tags,
            attachmentFilenames = existing?.attachmentFilenames.orEmpty(),
            ocrText = item.ocrText,
        )
        if (existing == null) {
            searchDao.insertDocument(next)
        } else if (searchDao.updateDocument(next) != 1) {
            error("Search document update failed")
        }
    }

    private suspend fun enqueueItemOperation(item: VaultItemEntity, now: Long) {
        val dedupeKey = "item:${item.id}"
        val operationId = idGenerator.newId()
        if (operationDao.rotateAndRefresh(
                dedupeKey = dedupeKey,
                newOperationId = operationId,
                itemId = item.id,
                attachmentId = null,
                operationType = SyncOperationType.UPSERT_ITEM,
                targetRevision = item.localRevision,
                state = SyncOperationState.PENDING,
                now = now,
            ) == 0
        ) {
            operationDao.insert(
                SyncOperationEntity(
                    operationId,
                    dedupeKey,
                    item.id,
                    null,
                    SyncOperationType.UPSERT_ITEM,
                    item.localRevision,
                    SyncOperationState.PENDING,
                    0,
                    now,
                    null,
                    null,
                    now,
                    now,
                    null,
                ),
            )
        }
    }

    private suspend fun recordAttempt(success: Boolean) {
        val now = clock.nowEpochMillis()
        val current = stateDao.get(DEFAULT_SYNC_SCOPE) ?: emptySyncState()
        stateDao.upsert(
            current.copy(
                lastAttemptAt = now,
                lastSuccessAt = if (success) now else current.lastSuccessAt,
            ),
        )
    }

    private fun emptySyncState(): SyncStateEntity = SyncStateEntity(
        scope = DEFAULT_SYNC_SCOPE,
        incrementalCursor = null,
        lastSuccessAt = null,
        lastAttemptAt = null,
        serverRevision = null,
    )

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
        tags = tags.sortedBy(TagEntity::normalizedName).map { tag ->
            com.vaultnote.core.common.model.VaultTag(tag.id, tag.name)
        },
    )

    private fun String.takeCodePoints(maximum: Int): String =
        if (codePointCount(0, length) <= maximum) this
        else substring(0, offsetByCodePoints(0, maximum))

    private fun Long.checkedIncrement(): Long {
        if (this == Long.MAX_VALUE) error("Local revision exhausted")
        return this + 1L
    }

    private data class ClaimedOperation(
        val operation: SyncOperationEntity,
        val item: VaultItemEntity?,
        val attachment: com.vaultnote.core.database.entity.AttachmentEntity?,
        val attachments: List<com.vaultnote.core.database.entity.AttachmentEntity>,
        val tags: List<TagEntity>,
    ) {
        companion object {
            fun synthetic(item: VaultItemEntity): ClaimedOperation = ClaimedOperation(
                operation = SyncOperationEntity(
                    operationId = "remote:${item.id}",
                    dedupeKey = "remote:${item.id}",
                    itemId = item.id,
                    attachmentId = null,
                    operationType = SyncOperationType.UPSERT_ITEM,
                    targetRevision = item.localRevision,
                    state = SyncOperationState.RUNNING,
                    attemptCount = 1,
                    nextAttemptAt = 0L,
                    leaseToken = null,
                    leaseExpiresAt = null,
                    createdAt = 0L,
                    updatedAt = 0L,
                    lastErrorCode = null,
                ),
                item = item,
                attachment = null,
                attachments = emptyList(),
                tags = emptyList(),
            )
        }
    }

    private enum class ProcessOutcome { Continue, Retry, AuthenticationRequired }
    private enum class PullOutcome { Complete, Retry, AuthenticationRequired }

    private companion object {
        const val DEFAULT_SYNC_SCOPE = "default"
        const val BODY_PREVIEW_CHARACTER_LIMIT = 240
        const val MAX_CONFLICTS = 100
        const val MAX_OPERATIONS_PER_RUN = 100
        const val LEASE_DURATION_MILLIS = 10L * 60L * 1_000L
        const val PULL_PAGE_SIZE = 100
        const val MAX_PULL_PAGES_PER_RUN = 4
        const val MAX_TAGS = 64
        const val MAX_TAG_CODE_POINTS = 64
        val READY_STATES = listOf(SyncOperationState.PENDING, SyncOperationState.RETRY_WAIT)
    }
}
