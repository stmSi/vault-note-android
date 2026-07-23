package com.vaultnote.feature.viewer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.OpenableAttachment
import com.vaultnote.core.common.model.OcrState
import com.vaultnote.core.common.model.VaultAttachment
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
        val attachments: List<VaultAttachment>,
        val selectedAttachmentId: String,
        val openableAttachment: OpenableAttachment?,
        val isLoadingSelection: Boolean = false,
        val isDeleting: Boolean = false,
        val isRetryingOcr: Boolean = false,
        val isSaving: Boolean = false,
        val isRenaming: Boolean = false,
    ) : AttachmentViewerState {
        val selectedAttachment: VaultAttachment?
            get() = attachments.firstOrNull { it.id == selectedAttachmentId }

        val isBusy: Boolean
            get() = isDeleting || isSaving || isRenaming
    }

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
    data class RenameComplete(val syncDelayed: Boolean) : AttachmentViewerEvent
    data object RenameFailed : AttachmentViewerEvent
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
    private val initialAttachmentId: String,
    private val repository: AttachmentRepository,
    private val ocrRepository: OcrRepository,
    private val exporter: AttachmentExporter,
) : ViewModel() {
    private val mutableState = MutableStateFlow<AttachmentViewerState>(AttachmentViewerState.Loading)
    private val mutableEvents = Channel<AttachmentViewerEvent>(Channel.BUFFERED)
    private var siblingsObservation: Job? = null
    private var selectionLoad: Job? = null
    private var pendingExport: PreparedAttachmentExport? = null

    val state: StateFlow<AttachmentViewerState> = mutableState.asStateFlow()
    val events: Flow<AttachmentViewerEvent> = mutableEvents.receiveAsFlow()

    init {
        loadInitialAttachment()
    }

    fun retry() {
        val current = mutableState.value as? AttachmentViewerState.Error ?: return
        if (!current.retryable) return
        mutableState.value = AttachmentViewerState.Loading
        loadInitialAttachment()
    }

    fun selectAttachment(attachmentId: String) {
        val current = mutableState.value as? AttachmentViewerState.Content ?: return
        if (current.isBusy || current.attachments.none { it.id == attachmentId }) return
        if (
            current.selectedAttachmentId == attachmentId &&
            (current.openableAttachment != null || current.isLoadingSelection)
        ) {
            return
        }
        selectionLoad?.cancel()
        mutableState.value = current.copy(
            selectedAttachmentId = attachmentId,
            openableAttachment = null,
            isLoadingSelection = true,
            isRetryingOcr = false,
        )
        selectionLoad = viewModelScope.launch {
            when (val result = repository.getOpenableAttachment(attachmentId)) {
                is RepositoryResult.Success -> {
                    val latest = mutableState.value as? AttachmentViewerState.Content
                        ?: return@launch
                    if (latest.selectedAttachmentId != attachmentId) return@launch
                    mutableState.value = latest.copy(
                        openableAttachment = result.value,
                        isLoadingSelection = false,
                    )
                    processPendingOcr(result.value.attachment)
                }
                is RepositoryResult.Failure -> {
                    val latest = mutableState.value as? AttachmentViewerState.Content
                        ?: return@launch
                    if (latest.selectedAttachmentId != attachmentId) return@launch
                    mutableState.value = latest.copy(isLoadingSelection = false)
                    mutableEvents.send(
                        AttachmentViewerEvent.ShowError(result.error.toViewerFailureReason()),
                    )
                }
            }
        }
    }

    fun delete() {
        val current = mutableState.value as? AttachmentViewerState.Content ?: return
        if (current.isBusy || current.isLoadingSelection) return
        val attachmentId = current.selectedAttachment?.id ?: return
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
                    restoreOperationFlag { copy(isDeleting = false) }
                    mutableEvents.send(
                        AttachmentViewerEvent.ShowError(result.error.toViewerFailureReason()),
                    )
                }
            }
        }
    }

    fun rename(displayName: String) {
        val current = mutableState.value as? AttachmentViewerState.Content ?: return
        if (current.isBusy || current.isLoadingSelection) return
        val attachmentId = current.selectedAttachment?.id ?: return
        mutableState.value = current.copy(isRenaming = true)
        viewModelScope.launch {
            when (val result = repository.rename(attachmentId, displayName)) {
                is RepositoryResult.Success -> {
                    val latest = mutableState.value as? AttachmentViewerState.Content
                    if (latest != null && latest.selectedAttachmentId == attachmentId) {
                        mutableState.value = latest.copy(
                            attachments = latest.attachments.map { attachment ->
                                if (attachment.id == attachmentId) result.value else attachment
                            },
                            openableAttachment = latest.openableAttachment?.copy(
                                attachment = result.value,
                            ),
                            isRenaming = false,
                        )
                    }
                    mutableEvents.send(
                        AttachmentViewerEvent.RenameComplete(syncDelayed = result.warning != null),
                    )
                }
                is RepositoryResult.Failure -> {
                    restoreOperationFlag { copy(isRenaming = false) }
                    mutableEvents.send(AttachmentViewerEvent.RenameFailed)
                }
            }
        }
    }

    fun retryOcr() {
        val current = mutableState.value as? AttachmentViewerState.Content ?: return
        val attachment = current.selectedAttachment ?: return
        if (
            current.isRetryingOcr || current.isBusy || current.isLoadingSelection ||
            !ocrRepository.isRetryable(attachment.ocrFailureCode)
        ) {
            return
        }
        mutableState.value = current.copy(isRetryingOcr = true)
        viewModelScope.launch {
            val reset = ocrRepository.retry(attachment.id)
            if (reset is RepositoryResult.Success && reset.value) {
                ocrRepository.processAttachment(attachment.id)
            } else {
                restoreOperationFlag { copy(isRetryingOcr = false) }
                mutableEvents.send(AttachmentViewerEvent.ShowError(ViewerFailureReason.UNKNOWN))
            }
        }
    }

    fun prepareSave(): AttachmentSavePickerRequest? {
        val current = mutableState.value as? AttachmentViewerState.Content ?: return null
        val attachment = current.selectedAttachment ?: return null
        if (current.isBusy || current.isLoadingSelection || pendingExport != null) return null
        return when (val prepared = exporter.prepare(attachment.id)) {
            is RepositoryResult.Success -> {
                pendingExport = prepared.value
                mutableState.value = current.copy(isSaving = true)
                AttachmentSavePickerRequest(
                    displayName = attachment.displayName,
                    mimeType = attachment.mimeType,
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
            restoreOperationFlag { copy(isSaving = false) }
            return
        }
        val current = mutableState.value as? AttachmentViewerState.Content
        if (current == null || current.isDeleting || current.isRenaming) {
            exporter.cancel(prepared)
            return
        }
        viewModelScope.launch {
            val result = exporter.save(prepared, destination)
            restoreOperationFlag { copy(isSaving = false) }
            mutableEvents.send(
                if (result is RepositoryResult.Success) {
                    AttachmentViewerEvent.SaveComplete
                } else {
                    AttachmentViewerEvent.SaveFailed
                },
            )
        }
    }

    private fun loadInitialAttachment() {
        selectionLoad?.cancel()
        siblingsObservation?.cancel()
        selectionLoad = viewModelScope.launch {
            when (val result = repository.getOpenableAttachment(initialAttachmentId)) {
                is RepositoryResult.Success -> {
                    val attachment = result.value.attachment
                    mutableState.value = AttachmentViewerState.Content(
                        attachments = listOf(attachment),
                        selectedAttachmentId = attachment.id,
                        openableAttachment = result.value,
                    )
                    observeSiblings(attachment.parentItemId)
                    processPendingOcr(attachment)
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

    private fun observeSiblings(parentItemId: String) {
        siblingsObservation?.cancel()
        siblingsObservation = viewModelScope.launch {
            repository.observeForItem(parentItemId).collect { attachments ->
                val current = mutableState.value as? AttachmentViewerState.Content
                    ?: return@collect
                val selected = attachments.firstOrNull { it.id == current.selectedAttachmentId }
                if (selected == null) {
                    if (current.isDeleting) return@collect
                    mutableState.value = AttachmentViewerState.Error(
                        reason = ViewerFailureReason.NOT_FOUND,
                        retryable = false,
                    )
                    return@collect
                }
                val latestOpenable = current.openableAttachment?.takeIf {
                    it.attachment.id == selected.id
                }?.copy(attachment = selected)
                mutableState.value = current.copy(
                    attachments = attachments,
                    openableAttachment = latestOpenable,
                    isRetryingOcr = selected.ocrState == OcrState.PENDING ||
                        selected.ocrState == OcrState.PROCESSING,
                )
            }
        }
    }

    private fun processPendingOcr(attachment: VaultAttachment) {
        if (attachment.ocrState != OcrState.PENDING) return
        viewModelScope.launch { ocrRepository.processAttachment(attachment.id) }
    }

    private inline fun restoreOperationFlag(
        update: AttachmentViewerState.Content.() -> AttachmentViewerState.Content,
    ) {
        val latest = mutableState.value as? AttachmentViewerState.Content ?: return
        mutableState.value = latest.update()
    }

    override fun onCleared() {
        pendingExport?.let(exporter::cancel)
        pendingExport = null
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
