package com.vaultnote.feature.viewer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.OpenableAttachment
import com.vaultnote.core.common.model.OcrState
import com.vaultnote.core.ocr.OcrRepository
import com.vaultnote.core.repository.AttachmentRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal enum class ViewerFailureReason {
    NOT_FOUND,
    CORRUPTED,
    PERMISSION_DENIED,
    LOCAL_DATABASE,
    UNKNOWN,
}

internal sealed interface AttachmentViewerState {
    data object Loading : AttachmentViewerState
    data class Content(
        val attachment: OpenableAttachment,
        val isDeleting: Boolean = false,
        val isRetryingOcr: Boolean = false,
        val isSaving: Boolean = false,
    ) : AttachmentViewerState
    data class Error(
        val reason: ViewerFailureReason,
        val retryable: Boolean,
    ) : AttachmentViewerState
}

internal sealed interface AttachmentViewerEvent {
    data class ShowError(val reason: ViewerFailureReason) : AttachmentViewerEvent
    data class DeleteComplete(
        val warnings: Set<AttachmentDeleteWarningReason>,
    ) : AttachmentViewerEvent
    data object SaveComplete : AttachmentViewerEvent
    data object SaveFailed : AttachmentViewerEvent
}

internal data class AttachmentSavePickerRequest(
    val displayName: String,
    val mimeType: String,
)

internal enum class AttachmentDeleteWarningReason {
    SYNC_DELAYED,
    FILE_CLEANUP_PENDING,
}

internal class AttachmentViewerViewModel(
    private val attachmentId: String,
    private val repository: AttachmentRepository,
    private val ocrRepository: OcrRepository,
    private val exporter: AttachmentExporter,
) : ViewModel() {
    private val mutableState = MutableStateFlow<AttachmentViewerState>(AttachmentViewerState.Loading)
    private val mutableEvents = Channel<AttachmentViewerEvent>(Channel.BUFFERED)
    private var attachmentObservation: Job? = null
    private var pendingExport: PreparedAttachmentExport? = null

    val state: StateFlow<AttachmentViewerState> = mutableState.asStateFlow()
    val events: Flow<AttachmentViewerEvent> = mutableEvents.receiveAsFlow()

    init {
        load()
    }

    fun retry() {
        val current = mutableState.value as? AttachmentViewerState.Error ?: return
        if (!current.retryable) return
        mutableState.value = AttachmentViewerState.Loading
        load()
    }

    fun delete() {
        val current = mutableState.value as? AttachmentViewerState.Content ?: return
        if (current.isDeleting || current.isSaving) return
        mutableState.value = current.copy(isDeleting = true)
        viewModelScope.launch {
            when (val result = repository.delete(attachmentId)) {
                is RepositoryResult.Success -> {
                    mutableEvents.send(
                        AttachmentViewerEvent.DeleteComplete(
                            warnings = buildSet {
                                if (result.value.syncDelayed) {
                                    add(AttachmentDeleteWarningReason.SYNC_DELAYED)
                                }
                                if (result.value.cleanupPending) {
                                    add(AttachmentDeleteWarningReason.FILE_CLEANUP_PENDING)
                                }
                            },
                        ),
                    )
                }
                is RepositoryResult.Failure -> {
                    mutableState.value = current
                    mutableEvents.send(
                        AttachmentViewerEvent.ShowError(result.error.toViewerFailureReason()),
                    )
                }
            }
        }
    }

    fun retryOcr() {
        val current = mutableState.value as? AttachmentViewerState.Content ?: return
        if (
            current.isRetryingOcr || current.isDeleting || current.isSaving ||
            !ocrRepository.isRetryable(current.attachment.attachment.ocrFailureCode)
        ) {
            return
        }
        mutableState.value = current.copy(isRetryingOcr = true)
        viewModelScope.launch {
            val reset = ocrRepository.retry(attachmentId)
            if (reset is RepositoryResult.Success && reset.value) {
                ocrRepository.processAttachment(attachmentId)
            } else {
                mutableState.value = current
                mutableEvents.send(AttachmentViewerEvent.ShowError(ViewerFailureReason.UNKNOWN))
            }
        }
    }

    fun prepareSave(): AttachmentSavePickerRequest? {
        val current = mutableState.value as? AttachmentViewerState.Content ?: return null
        if (current.isDeleting || current.isSaving || pendingExport != null) return null
        return when (val prepared = exporter.prepare(attachmentId)) {
            is RepositoryResult.Success -> {
                pendingExport = prepared.value
                AttachmentSavePickerRequest(
                    displayName = current.attachment.attachment.displayName,
                    mimeType = current.attachment.attachment.mimeType,
                )
            }
            is RepositoryResult.Failure -> {
                viewModelScope.launch { mutableEvents.send(AttachmentViewerEvent.SaveFailed) }
                null
            }
        }
    }

    fun completeSave(destination: Uri?) {
        val prepared = pendingExport ?: return
        pendingExport = null
        if (destination == null) {
            exporter.cancel(prepared)
            return
        }
        val current = mutableState.value as? AttachmentViewerState.Content
        if (current == null || current.isDeleting || current.isSaving) {
            exporter.cancel(prepared)
            return
        }
        mutableState.value = current.copy(isSaving = true)
        viewModelScope.launch {
            val result = exporter.save(prepared, destination)
            val latest = mutableState.value as? AttachmentViewerState.Content
            if (latest != null) mutableState.value = latest.copy(isSaving = false)
            mutableEvents.send(
                if (result is RepositoryResult.Success) {
                    AttachmentViewerEvent.SaveComplete
                } else {
                    AttachmentViewerEvent.SaveFailed
                },
            )
        }
    }

    private fun load() {
        viewModelScope.launch {
            when (val result = repository.getOpenableAttachment(attachmentId)) {
                is RepositoryResult.Success -> {
                    mutableState.value = AttachmentViewerState.Content(result.value)
                    observeAttachmentChanges(result.value)
                    if (result.value.attachment.ocrState == OcrState.PENDING) {
                        viewModelScope.launch { ocrRepository.processAttachment(attachmentId) }
                    }
                }
                is RepositoryResult.Failure -> {
                    mutableState.value = AttachmentViewerState.Error(
                        reason = result.error.toViewerFailureReason(),
                        retryable = result.error.isRetryable,
                    )
                }
            }
        }
    }

    private fun observeAttachmentChanges(openable: OpenableAttachment) {
        attachmentObservation?.cancel()
        attachmentObservation = viewModelScope.launch {
            repository.observeById(attachmentId).collect { latest ->
                latest ?: return@collect
                val current = mutableState.value as? AttachmentViewerState.Content ?: return@collect
                mutableState.value = current.copy(
                    attachment = openable.copy(attachment = latest),
                    isRetryingOcr = latest.ocrState == OcrState.PENDING ||
                        latest.ocrState == OcrState.PROCESSING,
                )
            }
        }
    }

    override fun onCleared() {
        pendingExport?.let(exporter::cancel)
        pendingExport = null
        super.onCleared()
    }

    class Factory(
        private val attachmentId: String,
        private val repository: AttachmentRepository,
        private val ocrRepository: OcrRepository,
        private val exporter: AttachmentExporter,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AttachmentViewerViewModel::class.java))
            return AttachmentViewerViewModel(
                attachmentId,
                repository,
                ocrRepository,
                exporter,
            ) as T
        }
    }
}

private fun AppError.toViewerFailureReason(): ViewerFailureReason = when (this) {
    is AppError.ItemNotFound,
    is AppError.InvalidInput,
    -> ViewerFailureReason.NOT_FOUND
    AppError.CorruptedFile,
    is AppError.DecryptionFailure,
    is AppError.EncryptionFailure,
    -> ViewerFailureReason.CORRUPTED
    AppError.PermissionDenied -> ViewerFailureReason.PERMISSION_DENIED
    is AppError.DatabaseFailure -> ViewerFailureReason.LOCAL_DATABASE
    AppError.NetworkUnavailable,
    AppError.AuthenticationExpired,
    is AppError.FileTooLarge,
    AppError.UnsupportedFile,
    is AppError.InsufficientStorage,
    is AppError.SynchronizationConflict,
    AppError.RemoteQuotaExceeded,
    is AppError.BackupValidationFailure,
    is AppError.InvalidItemState,
    is AppError.SyncSchedulingFailure,
    -> ViewerFailureReason.UNKNOWN
}
