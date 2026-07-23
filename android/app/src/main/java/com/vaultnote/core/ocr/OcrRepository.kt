package com.vaultnote.core.ocr

import androidx.room.withTransaction
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.Clock
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.OcrState
import com.vaultnote.core.database.VaultDatabase
import com.vaultnote.core.database.entity.AttachmentEntity
import com.vaultnote.core.encryption.CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class OcrBatchResult(val processedCount: Int, val mayHaveMore: Boolean)

interface OcrRepository {
    suspend fun processPending(limit: Int): RepositoryResult<OcrBatchResult>
    suspend fun processAttachment(attachmentId: String): RepositoryResult<Boolean>
    suspend fun retry(attachmentId: String): RepositoryResult<Boolean>
    fun isRetryable(failureCode: String?): Boolean
}

/** Persists every OCR transition and updates Room FTS atomically with successful extraction. */
class RoomOcrRepository(
    private val database: VaultDatabase,
    private val plaintextStore: OcrPlaintextStore,
    private val processor: OcrProcessor,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock,
) : OcrRepository {
    private val attachments = database.attachmentDao()

    override suspend fun processPending(limit: Int): RepositoryResult<OcrBatchResult> =
        withContext(dispatchers.io) {
            if (limit !in 1..MAX_BATCH) {
                return@withContext RepositoryResult.Failure(
                    AppError.InvalidInput("ocr_limit", "out_of_range"),
                )
            }
            try {
                val candidates = attachments.getOcrCandidates(
                    staleBefore = clock.nowEpochMillis() - STALE_PROCESSING_MILLIS,
                    encryptionFormatVersion = CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION,
                    limit = limit,
                )
                var processed = 0
                for (candidate in candidates) {
                    currentCoroutineContext().ensureActive()
                    if (processCandidate(candidate)) processed += 1
                }
                RepositoryResult.Success(
                    OcrBatchResult(processed, mayHaveMore = candidates.size == limit),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                RepositoryResult.Failure(AppError.DatabaseFailure(OPERATION, failure))
            }
        }

    override suspend fun processAttachment(
        attachmentId: String,
    ): RepositoryResult<Boolean> = withContext(dispatchers.io) {
        if (attachmentId.isBlank()) {
            return@withContext RepositoryResult.Failure(AppError.InvalidInput("attachment_id", "required"))
        }
        try {
            val candidate = attachments.getById(attachmentId)
                ?: return@withContext RepositoryResult.Failure(AppError.ItemNotFound(attachmentId))
            RepositoryResult.Success(processCandidate(candidate))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            RepositoryResult.Failure(AppError.DatabaseFailure(OPERATION, failure))
        }
    }

    override suspend fun retry(attachmentId: String): RepositoryResult<Boolean> =
        withContext(dispatchers.io) {
            if (attachmentId.isBlank()) {
                return@withContext RepositoryResult.Failure(
                    AppError.InvalidInput("attachment_id", "required"),
                )
            }
            try {
                val attachment = attachments.getById(attachmentId)
                    ?: return@withContext RepositoryResult.Failure(AppError.ItemNotFound(attachmentId))
                if (attachment.ocrState != OcrState.FAILED || !isRetryable(attachment.ocrFailureCode)) {
                    return@withContext RepositoryResult.Success(false)
                }
                RepositoryResult.Success(attachments.retryOcr(attachmentId) == 1)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                RepositoryResult.Failure(AppError.DatabaseFailure(OPERATION, failure))
            }
        }

    override fun isRetryable(failureCode: String?): Boolean =
        failureCode?.let { code ->
            OcrFailureCode.entries.firstOrNull { it.name == code }?.retryable
        } == true

    private suspend fun processCandidate(candidate: AttachmentEntity): Boolean {
        val now = clock.nowEpochMillis()
        val staleBefore = now - STALE_PROCESSING_MILLIS
        if (candidate.encryptionFormatVersion != CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION) return false
        if (candidate.ocrState != OcrState.PENDING && !candidate.isStaleProcessing(staleBefore)) return false
        if (attachments.claimOcr(candidate.id, candidate.sha256Checksum, staleBefore, now) != 1) return false

        val prepared = plaintextStore.prepare(candidate.id, candidate.localEncryptedPath)
        val result = when (prepared) {
            is RepositoryResult.Failure -> OcrProcessResult.Failure(prepared.error.toOcrFailure())
            is RepositoryResult.Success -> prepared.value.use { lease ->
                try {
                    processor.recognize(
                        OcrInput(lease.file, candidate.mimeType, candidate.pdfPageCount),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    OcrProcessResult.Failure(OcrFailureCode.PROCESSING_FAILED)
                }
            }
        }
        val completedAt = clock.nowEpochMillis()
        when (result) {
            is OcrProcessResult.Failure -> attachments.failOcr(
                candidate.id,
                candidate.sha256Checksum,
                result.code.name,
                completedAt,
            )
            is OcrProcessResult.Success -> database.withTransaction {
                if (
                    attachments.completeOcr(
                        candidate.id,
                        candidate.sha256Checksum,
                        result.text,
                        completedAt,
                    ) == 1
                ) {
                    val aggregate = attachments.getSearchableOcrText(candidate.parentItemId).orEmpty()
                    database.vaultItemDao().updateOcrText(candidate.parentItemId, aggregate)
                    database.searchDao().updateOcrText(candidate.parentItemId, aggregate)
                }
            }
        }
        return true
    }

    private fun AttachmentEntity.isStaleProcessing(staleBefore: Long): Boolean =
        ocrState == OcrState.PROCESSING && (ocrUpdatedAt == null || ocrUpdatedAt <= staleBefore)

    private fun AppError.toOcrFailure(): OcrFailureCode = when (this) {
        is AppError.DecryptionFailure, AppError.CorruptedFile -> OcrFailureCode.CORRUPTED_FILE
        is AppError.InsufficientStorage -> OcrFailureCode.TEMPORARY_STORAGE
        AppError.UnsupportedFile -> OcrFailureCode.UNSUPPORTED_FORMAT
        else -> OcrFailureCode.PROCESSING_FAILED
    }

    private companion object {
        const val OPERATION = "process_ocr"
        const val MAX_BATCH = 8
        const val STALE_PROCESSING_MILLIS = 10L * 60L * 1_000L
    }
}
