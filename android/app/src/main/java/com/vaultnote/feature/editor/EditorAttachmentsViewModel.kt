package com.vaultnote.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.VaultAttachment
import com.vaultnote.core.repository.AttachmentRepository
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

internal sealed interface EditorAttachmentsState {
    data object Loading : EditorAttachmentsState
    data class Content(val attachments: List<VaultAttachment>) : EditorAttachmentsState
    data object Error : EditorAttachmentsState
}

internal class EditorAttachmentsViewModel(
    private val itemId: String,
    private val attachmentRepository: AttachmentRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow<EditorAttachmentsState>(EditorAttachmentsState.Loading)
    private var cleanupJob: Job? = null
    val state: StateFlow<EditorAttachmentsState> = mutableState.asStateFlow()

    init {
        reconcileFileCleanup()
        observeAttachments()
    }

    fun retry() {
        if (mutableState.value != EditorAttachmentsState.Error) return
        mutableState.value = EditorAttachmentsState.Loading
        reconcileFileCleanup()
        observeAttachments()
    }

    fun reconcileFileCleanup() {
        if (cleanupJob?.isActive == true) return
        cleanupJob = viewModelScope.launch {
            when (attachmentRepository.reconcileFileCleanup()) {
                is RepositoryResult.Success -> Unit
                is RepositoryResult.Failure -> Unit
            }
        }
    }

    private fun observeAttachments() {
        viewModelScope.launch {
            attachmentRepository.observeForItem(itemId)
                .catch { failure ->
                    if (failure is CancellationException) throw failure
                    mutableState.value = EditorAttachmentsState.Error
                }
                .collect { attachments ->
                    mutableState.value = EditorAttachmentsState.Content(attachments)
                }
        }
    }

    class Factory(
        private val itemId: String,
        private val attachmentRepository: AttachmentRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(EditorAttachmentsViewModel::class.java))
            return EditorAttachmentsViewModel(itemId, attachmentRepository) as T
        }
    }
}
