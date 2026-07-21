package com.vaultnote.feature.importing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.VaultConstraints
import com.vaultnote.core.files.AttachmentFileManager
import com.vaultnote.core.files.AttachmentPreview
import com.vaultnote.core.repository.AttachmentRepository
import com.vaultnote.core.repository.VaultRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class InspectedImportCandidate(
    val stableId: Long,
    val source: ImportSource,
    val preview: AttachmentPreview?,
    val rejection: ImportFailureReason?,
)

internal enum class ImportFailureReason {
    SELECTION_EXPIRED,
    CAMERA_CAPTURE_EXPIRED,
    PERMISSION_DENIED,
    FILE_TOO_LARGE,
    UNSUPPORTED_FILE,
    CORRUPTED_FILE,
    INSUFFICIENT_STORAGE,
    NOTE_UNAVAILABLE,
    LOCAL_DATABASE,
    UNKNOWN,
}

internal sealed interface ImportPreviewUiState {
    data object Loading : ImportPreviewUiState

    data class Ready(
        val sharedText: String?,
        val candidates: List<InspectedImportCandidate>,
    ) : ImportPreviewUiState

    data class Importing(
        val sharedText: String?,
        val candidates: List<InspectedImportCandidate>,
        val completedFiles: Int,
        val totalFiles: Int,
    ) : ImportPreviewUiState

    data class Error(
        val sharedText: String?,
        val candidates: List<InspectedImportCandidate>,
        val reason: ImportFailureReason,
        val retryable: Boolean,
    ) : ImportPreviewUiState
}

internal sealed interface ImportPreviewEvent {
    data object NavigateBack : ImportPreviewEvent
    data class ImportComplete(
        val itemId: String,
        val createdItem: Boolean,
        val warnings: Set<ImportWarningReason>,
    ) : ImportPreviewEvent
}

internal enum class ImportWarningReason {
    SYNC_DELAYED,
    PREVIEW_UNAVAILABLE,
    FILE_CLEANUP_PENDING,
    LOCAL_MAINTENANCE_PENDING,
}

internal class ImportPreviewViewModel(
    private val parentItemId: String?,
    incomingImport: IncomingImport?,
    private val cameraCaptureId: String?,
    private val vaultRepository: VaultRepository,
    private val attachmentRepository: AttachmentRepository,
    private val attachmentFileManager: AttachmentFileManager,
    private val cameraCaptureManager: CameraCaptureManager,
    private val dispatchers: DispatcherProvider,
    private val importedFilesTitle: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow<ImportPreviewUiState>(ImportPreviewUiState.Loading)
    private val mutableEvents = Channel<ImportPreviewEvent>(Channel.BUFFERED)
    private var resolvedIncomingImport: IncomingImport? = incomingImport
    private var candidates: List<InspectedImportCandidate> = emptyList()
    private val completedCandidateIds = LinkedHashSet<Long>()
    private val warnings = LinkedHashSet<ImportWarningReason>()
    private var targetItemId: String? = parentItemId
    private var createdItem = false
    private var operation: Job? = null

    val state: StateFlow<ImportPreviewUiState> = mutableState.asStateFlow()
    val events: Flow<ImportPreviewEvent> = mutableEvents.receiveAsFlow()

    init {
        inspectSelection()
    }

    fun importSelection() {
        val current = mutableState.value
        if (current !is ImportPreviewUiState.Ready && current !is ImportPreviewUiState.Error) return
        if (operation?.isActive == true) return
        val payload = resolvedIncomingImport ?: return
        operation = viewModelScope.launch { performImport(payload) }
    }

    fun cancel() {
        val runningOperation = operation
        operation = viewModelScope.launch {
            runningOperation?.cancelAndJoin()
            cleanupTemporarySources()
            mutableEvents.send(ImportPreviewEvent.NavigateBack)
        }
    }

    private fun inspectSelection() {
        operation = viewModelScope.launch {
            val payload = resolveIncomingImport()
            if (payload == null || (payload.sharedText == null && payload.sources.isEmpty())) {
                mutableState.value = ImportPreviewUiState.Error(
                    sharedText = null,
                    candidates = emptyList(),
                    reason = if (cameraCaptureId == null) {
                        ImportFailureReason.SELECTION_EXPIRED
                    } else {
                        ImportFailureReason.CAMERA_CAPTURE_EXPIRED
                    },
                    retryable = false,
                )
                return@launch
            }
            if (parentItemId != null && payload.sharedText != null) {
                mutableState.value = ImportPreviewUiState.Error(
                    sharedText = null,
                    candidates = emptyList(),
                    reason = ImportFailureReason.UNKNOWN,
                    retryable = false,
                )
                return@launch
            }

            when (val reconciliation = attachmentRepository.reconcileFileCleanup()) {
                is RepositoryResult.Success -> Unit
                is RepositoryResult.Failure -> recordWarning(
                    reconciliation.error,
                    ImportWarningReason.FILE_CLEANUP_PENDING,
                )
            }

            candidates = withContext(dispatchers.io) {
                payload.sources.mapIndexed { index, source ->
                    when (val result = attachmentFileManager.inspect(source.uri)) {
                        is RepositoryResult.Success -> InspectedImportCandidate(
                            stableId = index.toLong() + 1L,
                            source = source,
                            preview = result.value,
                            rejection = null,
                        )

                        is RepositoryResult.Failure -> InspectedImportCandidate(
                            stableId = index.toLong() + 1L,
                            source = source,
                            preview = null,
                            rejection = result.error.toImportFailureReason(),
                        )
                    }
                }
            }
            if (payload.sharedText == null && candidates.none { it.preview != null }) {
                mutableState.value = ImportPreviewUiState.Error(
                    sharedText = null,
                    candidates = candidates,
                    reason = candidates.firstNotNullOfOrNull { it.rejection }
                        ?: ImportFailureReason.UNSUPPORTED_FILE,
                    retryable = false,
                )
            } else {
                mutableState.value = ImportPreviewUiState.Ready(payload.sharedText, candidates)
            }
        }
    }

    private suspend fun performImport(payload: IncomingImport) {
        val validCandidates = candidates.filter { it.preview != null }
        val itemId = targetItemId ?: when (
            val created = vaultRepository.createNote(
                title = suggestedTitle(payload.sharedText, validCandidates),
                body = payload.sharedText.orEmpty(),
            )
        ) {
            is RepositoryResult.Failure -> {
                showError(created.error, payload)
                return
            }

            is RepositoryResult.Success -> {
                created.warning?.let { warning ->
                    recordWarning(warning, ImportWarningReason.LOCAL_MAINTENANCE_PENDING)
                }
                createdItem = true
                targetItemId = created.value
                created.value
            }
        }

        for (candidate in validCandidates) {
            if (candidate.stableId in completedCandidateIds) continue
            mutableState.value = ImportPreviewUiState.Importing(
                sharedText = payload.sharedText,
                candidates = candidates,
                completedFiles = completedCandidateIds.size,
                totalFiles = validCandidates.size,
            )
            when (val result = attachmentRepository.importFromUri(itemId, candidate.source.uri)) {
                is RepositoryResult.Failure -> {
                    showError(result.error, payload)
                    return
                }

                is RepositoryResult.Success -> {
                    completedCandidateIds += candidate.stableId
                    result.warning?.let { warning ->
                        recordWarning(
                            warning,
                            if (result.value.wasDuplicate) {
                                ImportWarningReason.FILE_CLEANUP_PENDING
                            } else {
                                ImportWarningReason.PREVIEW_UNAVAILABLE
                            },
                        )
                    }
                    val attachment = result.value.attachment
                    if (
                        !result.value.wasDuplicate &&
                        attachment.thumbnailUri == null &&
                        (attachment.mimeType.startsWith("image/") || attachment.mimeType == PDF_MIME_TYPE)
                    ) {
                        warnings += ImportWarningReason.PREVIEW_UNAVAILABLE
                    }
                    deleteTemporarySource(candidate.source)
                }
            }
        }

        cleanupTemporarySources()
        mutableEvents.send(
            ImportPreviewEvent.ImportComplete(
                itemId = itemId,
                createdItem = createdItem,
                warnings = warnings.toSet(),
            ),
        )
    }

    private fun showError(error: AppError, payload: IncomingImport) {
        mutableState.value = ImportPreviewUiState.Error(
            sharedText = payload.sharedText,
            candidates = candidates,
            reason = error.toImportFailureReason(),
            retryable = error.isRetryable,
        )
    }

    private fun recordWarning(error: AppError, fallback: ImportWarningReason) {
        warnings += if (error is AppError.SyncSchedulingFailure) {
            ImportWarningReason.SYNC_DELAYED
        } else {
            fallback
        }
    }

    private suspend fun cleanupTemporarySources() {
        resolveIncomingImport()?.sources?.forEach { source -> deleteTemporarySource(source) }
    }

    private suspend fun resolveIncomingImport(): IncomingImport? {
        resolvedIncomingImport?.let { return it }
        val captureId = cameraCaptureId ?: return null
        val capture = cameraCaptureManager.restoreCapture(captureId) ?: return null
        return IncomingImport(
            sharedText = null,
            sources = listOf(capture.source),
        ).also { resolvedIncomingImport = it }
    }

    private suspend fun deleteTemporarySource(source: ImportSource) {
        if (source.temporaryFile == null) return
        if (source.kind != ImportSourceKind.CAMERA_CAPTURE) return
        val captureId = source.captureId ?: return
        cameraCaptureManager.deleteCapture(
            PendingCameraCapture(captureId = captureId, source = source),
        )
    }

    private fun suggestedTitle(
        sharedText: String?,
        validCandidates: List<InspectedImportCandidate>,
    ): String {
        val firstLine = sharedText
            ?.lineSequence()
            ?.map(String::trim)
            ?.firstOrNull(String::isNotEmpty)
        if (firstLine != null) return firstLine.takeCodePoints(SUGGESTED_TITLE_CODE_POINTS)
        return validCandidates.singleOrNull()?.preview?.originalFilename ?: importedFilesTitle
    }

    private fun String.takeCodePoints(maximumCodePoints: Int): String {
        if (codePointCount(0, length) <= maximumCodePoints) return this
        return substring(0, offsetByCodePoints(0, maximumCodePoints))
    }

    class Factory(
        private val parentItemId: String?,
        private val incomingImport: IncomingImport?,
        private val cameraCaptureId: String?,
        private val vaultRepository: VaultRepository,
        private val attachmentRepository: AttachmentRepository,
        private val attachmentFileManager: AttachmentFileManager,
        private val cameraCaptureManager: CameraCaptureManager,
        private val dispatchers: DispatcherProvider,
        private val importedFilesTitle: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ImportPreviewViewModel::class.java))
            return ImportPreviewViewModel(
                parentItemId,
                incomingImport,
                cameraCaptureId,
                vaultRepository,
                attachmentRepository,
                attachmentFileManager,
                cameraCaptureManager,
                dispatchers,
                importedFilesTitle,
            ) as T
        }
    }

    companion object {
        private const val PDF_MIME_TYPE = "application/pdf"
        private val SUGGESTED_TITLE_CODE_POINTS = minOf(
            80,
            VaultConstraints.MAX_NOTE_TITLE_CHARACTERS,
        )
    }
}

private fun AppError.toImportFailureReason(): ImportFailureReason = when (this) {
    AppError.PermissionDenied -> ImportFailureReason.PERMISSION_DENIED
    is AppError.FileTooLarge -> ImportFailureReason.FILE_TOO_LARGE
    AppError.UnsupportedFile -> ImportFailureReason.UNSUPPORTED_FILE
    AppError.CorruptedFile,
    is AppError.EncryptionFailure,
    is AppError.DecryptionFailure,
    -> ImportFailureReason.CORRUPTED_FILE
    is AppError.InsufficientStorage -> ImportFailureReason.INSUFFICIENT_STORAGE
    is AppError.ItemNotFound,
    is AppError.InvalidItemState,
    -> ImportFailureReason.NOTE_UNAVAILABLE
    is AppError.DatabaseFailure -> ImportFailureReason.LOCAL_DATABASE
    AppError.NetworkUnavailable,
    AppError.AuthenticationExpired,
    AppError.RemoteQuotaExceeded,
    is AppError.BackupValidationFailure,
    is AppError.SynchronizationConflict,
    is AppError.InvalidInput,
    is AppError.SyncSchedulingFailure,
    -> ImportFailureReason.UNKNOWN
}
