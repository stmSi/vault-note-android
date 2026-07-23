package com.vaultnote.core.repository

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.Clock
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.IdGenerator
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.AttachmentImportResult
import com.vaultnote.core.common.model.AttachmentDeleteResult
import com.vaultnote.core.common.model.AttachmentUploadStatus
import com.vaultnote.core.common.model.ItemSyncStatus
import com.vaultnote.core.common.model.OcrState
import com.vaultnote.core.common.model.OpenableAttachment
import com.vaultnote.core.common.model.SyncOperationState
import com.vaultnote.core.common.model.SyncOperationType
import com.vaultnote.core.common.model.VaultAttachment
import com.vaultnote.core.database.VaultDatabase
import com.vaultnote.core.database.entity.AttachmentEntity
import com.vaultnote.core.database.entity.AttachmentFileCleanupEntity
import com.vaultnote.core.database.entity.SearchDocumentEntity
import com.vaultnote.core.database.entity.SyncOperationEntity
import com.vaultnote.core.database.entity.TagEntity
import com.vaultnote.core.database.entity.VaultItemEntity
import com.vaultnote.core.encryption.CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION
import com.vaultnote.core.files.AttachmentCategory
import com.vaultnote.core.files.AttachmentFileManager
import com.vaultnote.core.files.AttachmentFilenamePolicy
import com.vaultnote.core.files.AttachmentFilenameSearch
import com.vaultnote.core.files.PreparedAttachment
import com.vaultnote.core.files.PlannedAttachmentPaths
import com.vaultnote.core.security.SecureAttachmentUriFactory
import com.vaultnote.core.sync.SyncScheduleResult
import com.vaultnote.core.sync.SyncScheduler
import java.util.concurrent.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Commits fully validated attachment files to Room and the durable sync queue.
 *
 * Stored paths are treated as untrusted database values and are resolved only by
 * [AttachmentFileManager]. When a metadata transaction does not commit, including cancellation,
 * the file is either removed after a Room reference check or remains durably journaled for retry.
 */
class RoomAttachmentRepository(
    private val database: VaultDatabase,
    private val fileManager: AttachmentFileManager,
    private val syncScheduler: SyncScheduler,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val secureUris: SecureAttachmentUriFactory,
) : AttachmentRepository {
    private val attachmentDao = database.attachmentDao()
    private val cleanupDao = database.attachmentFileCleanupDao()
    private val itemDao = database.vaultItemDao()
    private val searchDao = database.searchDao()
    private val tagDao = database.tagDao()
    private val syncOperationDao = database.syncOperationDao()
    private val cleanupMutex = Mutex()

    override fun observeActiveFiles(limit: Int, offset: Int): Flow<List<VaultAttachment>> =
        attachmentDao.observeActiveFiles(
            limit = limit.coerceIn(1, MAX_OBSERVED_FILE_LIMIT),
            offset = offset.coerceAtLeast(0),
        )
            .map { attachments -> attachments.map { attachment -> attachment.toDomain() } }
            .flowOn(dispatchers.io)

    override fun observeActiveFilesMatchingName(
        searchText: String,
        limit: Int,
        offset: Int,
    ): Flow<List<VaultAttachment>> {
        val patterns = AttachmentFilenameSearch.compile(searchText)
            ?: return observeActiveFiles(limit, offset)
        return attachmentDao.observeActiveFilesMatchingName(
            contiguousPattern = patterns.contiguous,
            subsequencePattern = patterns.subsequence,
            limit = limit.coerceIn(1, MAX_OBSERVED_FILE_LIMIT),
            offset = offset.coerceAtLeast(0),
        )
            .map { attachments -> attachments.map { attachment -> attachment.toDomain() } }
            .flowOn(dispatchers.io)
    }

    override fun observeForItem(itemId: String): Flow<List<VaultAttachment>> {
        if (itemId.isBlank()) return flowOf(emptyList())
        return attachmentDao.observeForItem(itemId)
            .map { attachments -> attachments.map { attachment -> attachment.toDomain() } }
            .flowOn(dispatchers.io)
    }

    override fun observeById(attachmentId: String): Flow<VaultAttachment?> {
        if (attachmentId.isBlank()) return flowOf(null)
        return attachmentDao.observeById(attachmentId)
            .map { attachment -> attachment?.toDomain() }
            .flowOn(dispatchers.io)
    }

    override suspend fun importFromUri(
        parentItemId: String,
        sourceUri: Uri,
        displayName: String?,
    ): RepositoryResult<AttachmentImportResult> = cleanupMutex.withLock {
        importFromUriInternal(parentItemId, sourceUri, displayName)
    }

    private suspend fun importFromUriInternal(
        parentItemId: String,
        sourceUri: Uri,
        displayName: String?,
    ): RepositoryResult<AttachmentImportResult> = withContext(dispatchers.io) {
        if (parentItemId.isBlank()) {
            return@withContext RepositoryResult.Failure(
                AppError.InvalidInput("parent_item_id", "required"),
            )
        }
        if (sourceUri.scheme != ContentResolver.SCHEME_CONTENT) {
            return@withContext RepositoryResult.Failure(AppError.UnsupportedFile)
        }

        val parentPreflight = try {
            itemDao.getById(parentItemId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            return@withContext databaseFailure(OPERATION_IMPORT_ATTACHMENT, failure)
        } ?: return@withContext RepositoryResult.Failure(AppError.ItemNotFound(parentItemId))
        if (parentPreflight.deletedAt != null) {
            return@withContext RepositoryResult.Failure(
                AppError.InvalidItemState(parentItemId, "in_trash"),
            )
        }

        val attachmentId = idGenerator.newId()
        val plannedPaths = when (val planned = fileManager.planAttachmentPaths(attachmentId)) {
            is RepositoryResult.Failure -> return@withContext planned
            is RepositoryResult.Success -> planned.value
        }
        val journalEntry = AttachmentFileCleanupEntity(
            cleanupId = "$IMPORT_CLEANUP_PREFIX$attachmentId",
            localRelativePath = plannedPaths.localRelativePath,
            thumbnailRelativePath = plannedPaths.thumbnailRelativePath,
            createdAt = clock.nowEpochMillis(),
            attemptCount = 0,
            lastAttemptAt = null,
        )
        try {
            cleanupDao.upsert(journalEntry)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            return@withContext databaseFailure(OPERATION_IMPORT_ATTACHMENT, failure)
        }

        val imported = try {
            fileManager.importAttachment(sourceUri, attachmentId)
        } catch (cancellation: CancellationException) {
            cleanupJournalAfterCancellation(journalEntry, cancellation)
        }
        val prepared = when (imported) {
            is RepositoryResult.Failure -> {
                cleanupJournalEntry(journalEntry)
                return@withContext imported
            }
            is RepositoryResult.Success -> imported.value
        }
        if (!prepared.matches(attachmentId, plannedPaths)) {
            val unexpectedEntry = prepared.toCleanupEntry(
                cleanupId = "$UNEXPECTED_CLEANUP_PREFIX$attachmentId",
                now = clock.nowEpochMillis(),
            )
            try {
                cleanupDao.upsert(unexpectedEntry)
            } catch (cancellation: CancellationException) {
                remediateUnexpectedJournalFailure(
                    unexpectedEntry = unexpectedEntry,
                    plannedEntry = journalEntry,
                    originalFailure = cancellation,
                )
                throw cancellation
            } catch (failure: Exception) {
                remediateUnexpectedJournalFailure(
                    unexpectedEntry = unexpectedEntry,
                    plannedEntry = journalEntry,
                    originalFailure = failure,
                )
                return@withContext databaseFailure(OPERATION_IMPORT_ATTACHMENT, failure)
            }
            val unexpectedCleanupWarning = cleanupJournalEntry(unexpectedEntry)
            val plannedCleanupWarning = cleanupJournalEntry(journalEntry)
            return@withContext RepositoryResult.Failure(
                unexpectedCleanupWarning
                    ?: plannedCleanupWarning
                    ?: AppError.InvalidInput("attachment_id", "mismatched_import"),
            )
        }

        val namedPrepared = when (
            val renamed = AttachmentFilenamePolicy.rename(
                requestedName = displayName ?: prepared.originalFilename,
                currentName = prepared.originalFilename,
                format = prepared.format,
            )
        ) {
            is RepositoryResult.Failure -> {
                cleanupJournalEntry(journalEntry)
                return@withContext renamed
            }
            is RepositoryResult.Success -> prepared.copy(originalFilename = renamed.value)
        }

        val mutation = try {
            database.withTransaction {
                val parent = requireItem(parentItemId)
                if (parent.deletedAt != null) {
                    abort(AppError.InvalidItemState(parentItemId, "in_trash"))
                }
                val duplicate = attachmentDao.findForItemByChecksum(
                    parentItemId,
                    namedPrepared.sha256Checksum,
                )
                if (duplicate != null) {
                    if (duplicate.originalFilename == namedPrepared.originalFilename) {
                        return@withTransaction ImportMutation(
                            duplicate,
                            wasDuplicate = true,
                            metadataChanged = false,
                        )
                    }
                    val renamedDuplicate = duplicate.copy(
                        originalFilename = namedPrepared.originalFilename,
                        uploadStatus = AttachmentUploadStatus.PENDING,
                    )
                    if (attachmentDao.update(renamedDuplicate) != 1) {
                        throw IllegalStateException("Attachment rename did not affect exactly one row")
                    }
                    val now = clock.nowEpochMillis()
                    val updatedParent = parent.withLocalChange(now)
                    updateItemExactlyOnce(updatedParent)
                    updateAttachmentSearchText(updatedParent)
                    enqueueAttachmentOperation(
                        attachmentId = duplicate.id,
                        itemId = parentItemId,
                        targetRevision = updatedParent.localRevision,
                        operationType = SyncOperationType.UPLOAD_ATTACHMENT,
                        now = now,
                    )
                    enqueueItemOperation(
                        item = updatedParent,
                        operationType = SyncOperationType.UPSERT_ITEM,
                        now = now,
                    )
                    return@withTransaction ImportMutation(
                        renamedDuplicate,
                        wasDuplicate = true,
                        metadataChanged = true,
                    )
                }

                val now = clock.nowEpochMillis()
                val attachment = namedPrepared.toEntity(parentItemId, now)
                attachmentDao.insert(attachment)
                if (cleanupDao.deleteByCleanupId(journalEntry.cleanupId) != 1) {
                    throw IllegalStateException("Committed attachment did not clear its cleanup journal")
                }

                val updatedParent = parent.withLocalChange(now).copy(
                    ocrText = attachmentDao.getSearchableOcrText(parent.id).orEmpty(),
                )
                updateItemExactlyOnce(updatedParent)
                updateAttachmentSearchText(updatedParent)
                enqueueAttachmentOperation(
                    attachmentId = attachment.id,
                    itemId = parentItemId,
                    targetRevision = updatedParent.localRevision,
                    operationType = SyncOperationType.UPLOAD_ATTACHMENT,
                    now = now,
                )
                enqueueItemOperation(
                    item = updatedParent,
                    operationType = SyncOperationType.UPSERT_ITEM,
                    now = now,
                )
                ImportMutation(attachment, wasDuplicate = false, metadataChanged = true)
            }
        } catch (cancellation: CancellationException) {
            cleanupJournalAfterCancellation(journalEntry, cancellation)
        } catch (aborted: RepositoryAbort) {
            cleanupJournalEntry(journalEntry)
            return@withContext RepositoryResult.Failure(aborted.error)
        } catch (failure: Exception) {
            cleanupJournalAfterUncertainFailure(journalEntry, failure)
            return@withContext databaseFailure(OPERATION_IMPORT_ATTACHMENT, failure)
        }

        if (mutation.wasDuplicate) {
            val cleanupWarning = cleanupJournalEntry(journalEntry)
            val syncWarning = if (mutation.metadataChanged) requestSyncWarning() else null
            return@withContext RepositoryResult.Success(
                AttachmentImportResult(
                    attachment = mutation.attachment.toDomain(),
                    wasDuplicate = true,
                ),
                warning = syncWarning ?: cleanupWarning,
            )
        }

        val syncWarning = requestSyncWarning()
        RepositoryResult.Success(
            AttachmentImportResult(
                attachment = mutation.attachment.toDomain(),
                wasDuplicate = false,
            ),
            // Missing thumbnails are visible in the domain model; the single warning slot is
            // reserved for a durable sync scheduling failure after the metadata commit.
            warning = syncWarning,
        )
    }

    override suspend fun getById(
        attachmentId: String,
    ): RepositoryResult<VaultAttachment> = withContext(dispatchers.io) {
        if (attachmentId.isBlank()) return@withContext attachmentNotFound()
        try {
            val attachment = attachmentDao.getById(attachmentId)
                ?: return@withContext attachmentNotFound()
            RepositoryResult.Success(attachment.toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            databaseFailure(OPERATION_GET_ATTACHMENT, failure)
        }
    }

    override suspend fun getOpenableAttachment(
        attachmentId: String,
    ): RepositoryResult<OpenableAttachment> = cleanupMutex.withLock {
        getOpenableAttachmentInternal(attachmentId)
    }

    private suspend fun getOpenableAttachmentInternal(
        attachmentId: String,
    ): RepositoryResult<OpenableAttachment> = withContext(dispatchers.io) {
        if (attachmentId.isBlank()) return@withContext attachmentNotFound()
        var attachment = try {
            attachmentDao.getById(attachmentId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            return@withContext databaseFailure(OPERATION_OPEN_ATTACHMENT, failure)
        } ?: return@withContext attachmentNotFound()

        when (val upgraded = ensureEncrypted(attachment)) {
            is RepositoryResult.Failure -> return@withContext upgraded
            is RepositoryResult.Success -> attachment = upgraded.value
        }
        RepositoryResult.Success(
            OpenableAttachment(
                attachment = attachment.toDomain(),
                contentUri = secureUris.attachment(attachment.id),
            ),
        )
    }

    override suspend fun delete(
        attachmentId: String,
    ): RepositoryResult<AttachmentDeleteResult> = cleanupMutex.withLock {
        deleteInternal(attachmentId)
    }

    override suspend fun rename(
        attachmentId: String,
        displayName: String,
    ): RepositoryResult<VaultAttachment> = withContext(dispatchers.io) {
        if (attachmentId.isBlank()) return@withContext attachmentNotFound()
        val renamed = try {
            database.withTransaction {
                val attachment = attachmentDao.getById(attachmentId)
                    ?: abort(AppError.InvalidInput("attachment_id", "not_found"))
                val parent = requireItem(attachment.parentItemId)
                if (parent.deletedAt != null) {
                    abort(AppError.InvalidItemState(parent.id, "in_trash"))
                }
                val validatedName = when (
                    val validation = AttachmentFilenamePolicy.renameForMimeType(
                        requestedName = displayName,
                        currentName = attachment.originalFilename,
                        mimeType = attachment.mimeType,
                    )
                ) {
                    is RepositoryResult.Failure -> abort(validation.error)
                    is RepositoryResult.Success -> validation.value
                }
                if (validatedName == attachment.originalFilename) {
                    return@withTransaction RenameMutation(attachment, changed = false)
                }

                val updatedAttachment = attachment.copy(
                    originalFilename = validatedName,
                    uploadStatus = AttachmentUploadStatus.PENDING,
                )
                if (attachmentDao.update(updatedAttachment) != 1) {
                    throw IllegalStateException("Attachment rename did not affect exactly one row")
                }
                val now = clock.nowEpochMillis()
                val updatedParent = parent.withLocalChange(now)
                updateItemExactlyOnce(updatedParent)
                updateAttachmentSearchText(updatedParent)
                enqueueAttachmentOperation(
                    attachmentId = attachment.id,
                    itemId = attachment.parentItemId,
                    targetRevision = updatedParent.localRevision,
                    operationType = SyncOperationType.UPLOAD_ATTACHMENT,
                    now = now,
                )
                enqueueItemOperation(
                    item = updatedParent,
                    operationType = SyncOperationType.UPSERT_ITEM,
                    now = now,
                )
                RenameMutation(updatedAttachment, changed = true)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (aborted: RepositoryAbort) {
            return@withContext RepositoryResult.Failure(aborted.error)
        } catch (failure: Exception) {
            return@withContext databaseFailure(OPERATION_RENAME_ATTACHMENT, failure)
        }

        val warning = if (renamed.changed) requestSyncWarning() else null
        RepositoryResult.Success(renamed.attachment.toDomain(), warning)
    }

    private suspend fun deleteInternal(
        attachmentId: String,
    ): RepositoryResult<AttachmentDeleteResult> = withContext(dispatchers.io) {
        if (attachmentId.isBlank()) return@withContext attachmentNotFound()

        val deleted = try {
            database.withTransaction {
                val attachment = attachmentDao.getById(attachmentId)
                    ?: return@withTransaction null
                val parent = requireItem(attachment.parentItemId)
                val now = clock.nowEpochMillis()
                val cleanupEntry = attachment.toCleanupEntry(now)
                cleanupDao.upsert(cleanupEntry)
                if (attachmentDao.deleteById(attachmentId) != 1) {
                    throw IllegalStateException("Attachment delete did not affect exactly one row")
                }

                val updatedParent = parent.withLocalChange(now).copy(
                    ocrText = attachmentDao.getSearchableOcrText(parent.id).orEmpty(),
                )
                updateItemExactlyOnce(updatedParent)
                updateAttachmentSearchText(updatedParent)
                enqueueAttachmentOperation(
                    attachmentId = attachment.id,
                    itemId = attachment.parentItemId,
                    targetRevision = updatedParent.localRevision,
                    operationType = SyncOperationType.DELETE_ATTACHMENT,
                    now = now,
                )
                enqueueItemOperation(
                    item = updatedParent,
                    operationType = if (updatedParent.deletedAt == null) {
                        SyncOperationType.UPSERT_ITEM
                    } else {
                        SyncOperationType.DELETE_ITEM
                    },
                    now = now,
                )
                DeletedAttachment(cleanupEntry)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (aborted: RepositoryAbort) {
            return@withContext RepositoryResult.Failure(aborted.error)
        } catch (failure: Exception) {
            return@withContext databaseFailure(OPERATION_DELETE_ATTACHMENT, failure)
        } ?: return@withContext RepositoryResult.Success(
            AttachmentDeleteResult(cleanupPending = false, syncDelayed = false),
        )

        val syncWarning = requestSyncWarning()
        val cleanupWarning = cleanupJournalEntry(deleted.cleanupEntry)
        RepositoryResult.Success(
            AttachmentDeleteResult(
                cleanupPending = cleanupWarning != null,
                syncDelayed = syncWarning != null,
            ),
        )
    }

    override suspend fun reconcileFileCleanup(): RepositoryResult<Unit> = cleanupMutex.withLock {
        withContext(dispatchers.io) {
            var firstError: AppError? = null
            val entries = try {
                cleanupDao.getOldest(CLEANUP_RECONCILE_LIMIT)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                firstError = AppError.DatabaseFailure(OPERATION_RECONCILE_FILES, failure)
                emptyList()
            }

            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                val mayRemove = try {
                    database.withTransaction {
                        if (attachmentDao.countPathReferences(
                                entry.localRelativePath,
                                entry.thumbnailRelativePath,
                            ) > 0
                        ) {
                            cleanupDao.deleteByCleanupId(entry.cleanupId)
                            false
                        } else {
                            cleanupDao.recordAttempt(
                                entry.cleanupId,
                                clock.nowEpochMillis(),
                            ) == 1
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    if (firstError == null) {
                        firstError = AppError.DatabaseFailure(OPERATION_RECONCILE_FILES, failure)
                    }
                    false
                }
                if (!mayRemove) continue

                val stillUnreferenced = try {
                    attachmentDao.countPathReferences(
                        entry.localRelativePath,
                        entry.thumbnailRelativePath,
                    ) == 0
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    if (firstError == null) {
                        firstError = AppError.DatabaseFailure(OPERATION_RECONCILE_FILES, failure)
                    }
                    false
                }
                if (!stillUnreferenced) {
                    tryDeleteCleanupEntry(entry.cleanupId)?.let { error ->
                        if (firstError == null) firstError = error
                    }
                    continue
                }

                when (
                    val cleanup = fileManager.removeStored(
                        entry.localRelativePath,
                        entry.thumbnailRelativePath,
                    )
                ) {
                    is RepositoryResult.Failure -> {
                        if (firstError == null) firstError = cleanup.error
                    }
                    is RepositoryResult.Success -> {
                        if (firstError == null && cleanup.warning != null) {
                            firstError = cleanup.warning
                        }
                        tryDeleteCleanupEntry(entry.cleanupId)?.let { error ->
                            if (firstError == null) firstError = error
                        }
                    }
                }
            }

            when (val pendingCleanup = fileManager.cleanupAbandonedFiles()) {
                is RepositoryResult.Failure -> {
                    if (firstError == null) firstError = pendingCleanup.error
                }
                is RepositoryResult.Success -> {
                    if (firstError == null && pendingCleanup.warning != null) {
                        firstError = pendingCleanup.warning
                    }
                    if (firstError == null && pendingCleanup.value.failedDeletions > 0) {
                        firstError = AppError.PermissionDenied
                    }
                }
            }

            val error = firstError
            if (error == null) {
                RepositoryResult.Success(Unit)
            } else {
                RepositoryResult.Failure(error)
            }
        }
    }

    override suspend fun migrateLegacyAttachments(limit: Int): RepositoryResult<Int> =
        cleanupMutex.withLock {
            withContext(dispatchers.io) {
                if (limit !in 1..MAX_SECURITY_MIGRATION_BATCH) {
                    return@withContext RepositoryResult.Failure(
                        AppError.InvalidInput("migration_limit", "out_of_range"),
                    )
                }
                val candidates = try {
                    attachmentDao.getLegacyEncryptionBatch(
                        CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION,
                        limit,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    return@withContext databaseFailure(OPERATION_MIGRATE_ENCRYPTION, failure)
                }
                var migrated = 0
                for (attachment in candidates) {
                    currentCoroutineContext().ensureActive()
                    when (val result = ensureEncrypted(attachment)) {
                        is RepositoryResult.Failure -> return@withContext result
                        is RepositoryResult.Success -> migrated += 1
                    }
                }
                RepositoryResult.Success(migrated)
            }
        }

    private suspend fun updateAttachmentSearchText(item: VaultItemEntity) {
        val filenames = attachmentDao.getSearchableFilenames(item.id).orEmpty()
        val current = searchDao.getDocumentForItem(item.id)
        val next = if (current == null) {
            val tags = tagDao.getTagsForItem(item.id)
                .sortedBy(TagEntity::normalizedName)
                .joinToString(separator = "\n", transform = TagEntity::name)
            SearchDocumentEntity(
                itemId = item.id,
                title = item.title,
                body = item.body,
                tags = tags,
                attachmentFilenames = filenames,
                ocrText = item.ocrText,
            )
        } else {
            current.copy(attachmentFilenames = filenames)
        }

        if (current == null) {
            val rowId = searchDao.insertDocument(next)
            if (rowId == INSERT_IGNORED) {
                val concurrent = searchDao.getDocumentForItem(item.id)
                    ?: throw IllegalStateException("Search document insert was ignored without a row")
                if (searchDao.updateDocument(next.copy(rowId = concurrent.rowId)) != 1) {
                    throw IllegalStateException("Search document update failed")
                }
            }
        } else if (searchDao.updateDocument(next) != 1) {
            throw IllegalStateException("Search document update failed")
        }
    }

    private suspend fun enqueueAttachmentOperation(
        attachmentId: String,
        itemId: String,
        targetRevision: Long,
        operationType: SyncOperationType,
        now: Long,
    ) {
        enqueueOperation(
            dedupeKey = "$ATTACHMENT_DEDUPE_PREFIX$attachmentId",
            itemId = itemId,
            attachmentId = attachmentId,
            operationType = operationType,
            targetRevision = targetRevision,
            now = now,
        )
    }

    private suspend fun enqueueItemOperation(
        item: VaultItemEntity,
        operationType: SyncOperationType,
        now: Long,
    ) {
        enqueueOperation(
            dedupeKey = "$ITEM_DEDUPE_PREFIX${item.id}",
            itemId = item.id,
            attachmentId = null,
            operationType = operationType,
            targetRevision = item.localRevision,
            now = now,
        )
    }

    private suspend fun enqueueOperation(
        dedupeKey: String,
        itemId: String,
        attachmentId: String?,
        operationType: SyncOperationType,
        targetRevision: Long,
        now: Long,
    ) {
        val operationId = idGenerator.newId()
        val updated = syncOperationDao.rotateAndRefresh(
            dedupeKey = dedupeKey,
            newOperationId = operationId,
            itemId = itemId,
            attachmentId = attachmentId,
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
                    attachmentId = attachmentId,
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

    private fun requestSyncWarning(): AppError? {
        val result = try {
            syncScheduler.requestSync()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            return AppError.SyncSchedulingFailure(SYNC_SCHEDULER_UNAVAILABLE)
        }
        return when (result) {
            SyncScheduleResult.Scheduled,
            SyncScheduleResult.Coalesced,
            -> null

            is SyncScheduleResult.Rejected -> AppError.SyncSchedulingFailure(result.reason)
        }
    }

    private suspend fun cleanupJournalEntry(
        entry: AttachmentFileCleanupEntity,
    ): AppError? = withContext(NonCancellable + dispatchers.io) {
        val isReferenced = try {
            attachmentDao.countPathReferences(
                entry.localRelativePath,
                entry.thumbnailRelativePath,
            ) > 0
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            return@withContext AppError.DatabaseFailure(OPERATION_RECONCILE_FILES, failure)
        }
        if (isReferenced) {
            return@withContext tryDeleteCleanupEntry(entry.cleanupId)
        }

        val cleanupWarning = when (
            val cleanup = fileManager.removeStored(
                entry.localRelativePath,
                entry.thumbnailRelativePath,
            )
        ) {
            is RepositoryResult.Failure -> return@withContext cleanup.error
            is RepositoryResult.Success -> cleanup.warning
        }
        val journalWarning = tryDeleteCleanupEntry(entry.cleanupId)
        cleanupWarning ?: journalWarning
    }

    private suspend fun tryDeleteCleanupEntry(cleanupId: String): AppError? = try {
        cleanupDao.deleteByCleanupId(cleanupId)
        null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        AppError.DatabaseFailure(OPERATION_RECONCILE_FILES, failure)
    }

    private suspend fun cleanupJournalAfterCancellation(
        entry: AttachmentFileCleanupEntity,
        cancellation: CancellationException,
    ): Nothing {
        try {
            cleanupJournalEntry(entry)
        } catch (cleanupFailure: Exception) {
            cancellation.addSuppressed(cleanupFailure)
        }
        throw cancellation
    }

    private suspend fun cleanupJournalAfterUncertainFailure(
        entry: AttachmentFileCleanupEntity,
        failure: Exception,
    ) {
        try {
            cleanupJournalEntry(entry)
        } catch (cleanupFailure: Exception) {
            failure.addSuppressed(cleanupFailure)
        }
    }

    private suspend fun remediateUnexpectedJournalFailure(
        unexpectedEntry: AttachmentFileCleanupEntity,
        plannedEntry: AttachmentFileCleanupEntity,
        originalFailure: Throwable,
    ) = withContext(NonCancellable + dispatchers.io) {
        val unexpectedCleanupError = try {
            cleanupJournalEntry(unexpectedEntry)
        } catch (cleanupFailure: Exception) {
            originalFailure.addSuppressed(cleanupFailure)
            AppError.DatabaseFailure(OPERATION_RECONCILE_FILES, cleanupFailure)
        }
        try {
            cleanupJournalEntry(plannedEntry)
        } catch (cleanupFailure: Exception) {
            originalFailure.addSuppressed(cleanupFailure)
        }

        if (unexpectedCleanupError != null) {
            try {
                cleanupDao.upsert(unexpectedEntry)
            } catch (fallbackFailure: Exception) {
                originalFailure.addSuppressed(fallbackFailure)
            }
        }
    }

    private suspend fun requireItem(itemId: String): VaultItemEntity =
        itemDao.getById(itemId) ?: abort(AppError.ItemNotFound(itemId))

    private suspend fun updateItemExactlyOnce(item: VaultItemEntity) {
        if (itemDao.update(item) != 1) {
            throw IllegalStateException("A single item update did not affect exactly one row")
        }
    }

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

    private fun PreparedAttachment.matches(
        expectedAttachmentId: String,
        plannedPaths: PlannedAttachmentPaths,
    ): Boolean =
        attachmentId == expectedAttachmentId &&
            localRelativePath == plannedPaths.localRelativePath &&
            (
                thumbnailRelativePath == null ||
                    thumbnailRelativePath == plannedPaths.thumbnailRelativePath
                )

    private fun AttachmentEntity.toCleanupEntry(now: Long): AttachmentFileCleanupEntity =
        AttachmentFileCleanupEntity(
            cleanupId = "$DELETE_CLEANUP_PREFIX$id",
            localRelativePath = localEncryptedPath,
            thumbnailRelativePath = thumbnailPath,
            createdAt = now,
            attemptCount = 0,
            lastAttemptAt = null,
        )

    private fun PreparedAttachment.toCleanupEntry(
        cleanupId: String,
        now: Long,
    ): AttachmentFileCleanupEntity = AttachmentFileCleanupEntity(
        cleanupId = cleanupId,
        localRelativePath = localRelativePath,
        thumbnailRelativePath = thumbnailRelativePath,
        createdAt = now,
        attemptCount = 0,
        lastAttemptAt = null,
    )

    private fun PreparedAttachment.toEntity(parentItemId: String, createdAt: Long): AttachmentEntity =
        AttachmentEntity(
            id = attachmentId,
            parentItemId = parentItemId,
            originalFilename = originalFilename,
            mimeType = mimeType,
            fileSize = fileSize,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            pdfPageCount = pdfPageCount,
            sha256Checksum = sha256Checksum,
            localEncryptedPath = localRelativePath,
            remotePath = null,
            thumbnailPath = thumbnailRelativePath,
            encryptionFormatVersion = encryptionFormatVersion,
            uploadStatus = AttachmentUploadStatus.PENDING,
            createdAt = createdAt,
            ocrState = if (
                format.category == AttachmentCategory.IMAGE ||
                format.category == AttachmentCategory.PDF
            ) {
                OcrState.PENDING
            } else {
                OcrState.NOT_APPLICABLE
            },
            extractedOcrText = "",
            ocrSourceChecksum = null,
            ocrFailureCode = null,
            ocrUpdatedAt = null,
        )

    private fun AttachmentEntity.toDomain(): VaultAttachment = VaultAttachment(
        id = id,
        parentItemId = parentItemId,
        displayName = originalFilename,
        mimeType = mimeType,
        fileSizeBytes = fileSize,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        pdfPageCount = pdfPageCount,
        sha256Checksum = sha256Checksum,
        remotePath = remotePath,
        thumbnailUri = thumbnailPath
            ?.takeIf { encryptionFormatVersion == CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION }
            ?.let { secureUris.thumbnail(id) },
        encryptionFormatVersion = encryptionFormatVersion,
        uploadStatus = uploadStatus,
        createdAtEpochMillis = createdAt,
        ocrState = ocrState,
        ocrFailureCode = ocrFailureCode,
    )

    private suspend fun ensureEncrypted(
        attachment: AttachmentEntity,
    ): RepositoryResult<AttachmentEntity> {
        if (attachment.encryptionFormatVersion == CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION) {
            return RepositoryResult.Success(attachment)
        }
        val newVersion = when (
            val upgraded = fileManager.upgradeStoredEncryption(
                attachmentId = attachment.id,
                localRelativePath = attachment.localEncryptedPath,
                thumbnailRelativePath = attachment.thumbnailPath,
                storedFormatVersion = attachment.encryptionFormatVersion,
                expectedPlaintextSha256 = attachment.sha256Checksum,
            )
        ) {
            is RepositoryResult.Success -> upgraded.value
            is RepositoryResult.Failure -> return upgraded
        }
        return try {
            val updated = attachmentDao.updateEncryptionFormat(
                attachmentId = attachment.id,
                expectedVersion = attachment.encryptionFormatVersion,
                newVersion = newVersion,
            )
            if (updated == 1) {
                RepositoryResult.Success(attachment.copy(encryptionFormatVersion = newVersion))
            } else {
                val concurrent = attachmentDao.getById(attachment.id)
                if (concurrent?.encryptionFormatVersion == newVersion) {
                    RepositoryResult.Success(concurrent)
                } else {
                    RepositoryResult.Failure(AppError.DatabaseFailure(OPERATION_MIGRATE_ENCRYPTION))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            databaseFailure(OPERATION_MIGRATE_ENCRYPTION, failure)
        }
    }

    private fun attachmentNotFound(): RepositoryResult.Failure = RepositoryResult.Failure(
        AppError.InvalidInput("attachment_id", "not_found"),
    )

    private fun <T> databaseFailure(
        operation: String,
        cause: Exception,
    ): RepositoryResult<T> = RepositoryResult.Failure(AppError.DatabaseFailure(operation, cause))

    private data class ImportMutation(
        val attachment: AttachmentEntity,
        val wasDuplicate: Boolean,
        val metadataChanged: Boolean,
    )

    private data class RenameMutation(
        val attachment: AttachmentEntity,
        val changed: Boolean,
    )

    private data class DeletedAttachment(
        val cleanupEntry: AttachmentFileCleanupEntity,
    )

    private class RepositoryAbort(val error: AppError) :
        RuntimeException(null, null, false, false)

    private fun abort(error: AppError): Nothing = throw RepositoryAbort(error)

    private companion object {
        const val MAX_OBSERVED_FILE_LIMIT = 101
        const val INSERT_IGNORED: Long = -1L
        const val ITEM_DEDUPE_PREFIX: String = "item:"
        const val ATTACHMENT_DEDUPE_PREFIX: String = "attachment:"
        const val IMPORT_CLEANUP_PREFIX: String = "import:"
        const val UNEXPECTED_CLEANUP_PREFIX: String = "unexpected-import:"
        const val DELETE_CLEANUP_PREFIX: String = "delete:"
        const val SYNC_SCHEDULER_UNAVAILABLE: String = "sync_scheduler_unavailable"
        const val OPERATION_IMPORT_ATTACHMENT: String = "import_attachment"
        const val OPERATION_GET_ATTACHMENT: String = "get_attachment"
        const val OPERATION_OPEN_ATTACHMENT: String = "open_attachment"
        const val OPERATION_DELETE_ATTACHMENT: String = "delete_attachment"
        const val OPERATION_RENAME_ATTACHMENT: String = "rename_attachment"
        const val OPERATION_RECONCILE_FILES: String = "reconcile_attachment_files"
        const val OPERATION_MIGRATE_ENCRYPTION: String = "migrate_attachment_encryption"
        const val CLEANUP_RECONCILE_LIMIT: Int = 64
        const val MAX_SECURITY_MIGRATION_BATCH: Int = 32
    }
}
