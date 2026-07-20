package com.vaultnote.feature.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.OpenableAttachment
import com.vaultnote.core.repository.AttachmentRepository
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
}

internal enum class AttachmentDeleteWarningReason {
    SYNC_DELAYED,
    FILE_CLEANUP_PENDING,
}

internal class AttachmentViewerViewModel(
    private val attachmentId: String,
    private val repository: AttachmentRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow<AttachmentViewerState>(AttachmentViewerState.Loading)
    private val mutableEvents = Channel<AttachmentViewerEvent>(Channel.BUFFERED)

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
        if (current.isDeleting) return
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

    private fun load() {
        viewModelScope.launch {
            when (val result = repository.getOpenableAttachment(attachmentId)) {
                is RepositoryResult.Success -> {
                    mutableState.value = AttachmentViewerState.Content(result.value)
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

    class Factory(
        private val attachmentId: String,
        private val repository: AttachmentRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AttachmentViewerViewModel::class.java))
            return AttachmentViewerViewModel(attachmentId, repository) as T
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
