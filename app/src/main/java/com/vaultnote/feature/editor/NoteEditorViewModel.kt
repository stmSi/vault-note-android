package com.vaultnote.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.VaultConstraints
import com.vaultnote.core.common.isRetryableLocalDatabaseFailure
import com.vaultnote.core.common.model.VaultNote
import com.vaultnote.core.repository.VaultRepository
import java.util.concurrent.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal data class EditorDraft(
    val title: String,
    val body: String,
    val tagsText: String,
    val isPinned: Boolean,
    val isFavorite: Boolean,
    val isArchived: Boolean,
)

internal enum class EditorSaveStatus {
    DIRTY,
    SAVING,
    SAVED,
    FAILED,
}

internal sealed interface EditorUiState {
    data object Loading : EditorUiState
    data class Content(
        val draft: EditorDraft,
        val editVersion: Long,
        val saveStatus: EditorSaveStatus,
        val saveRetryable: Boolean = false,
        val isClosing: Boolean = false,
        val isMetadataSaving: Boolean = false,
    ) : EditorUiState

    data class Error(
        val noteMissing: Boolean,
        val retryable: Boolean,
    ) : EditorUiState
}

internal sealed interface EditorEvent {
    data object NavigateBack : EditorEvent
    data class ShowError(val error: AppError) : EditorEvent
}

internal class NoteEditorViewModel(
    private val itemId: String,
    private val repository: VaultRepository,
) : ViewModel() {
    private data class PersistedDraft(
        val title: String,
        val body: String,
        val tagNames: List<String>,
    )

    private val mutableUiState = MutableStateFlow<EditorUiState>(EditorUiState.Loading)
    private val mutableEvents = Channel<EditorEvent>(capacity = Channel.BUFFERED)
    private var hasLoadedInitialNote = false
    private var isLeaving = false
    private var metadataJob: Job? = null
    private var metadataOutcome: RepositoryResult<Unit>? = null
    private var lastSaveError: AppError? = null

    private val autosaver = DebouncedAutosaver(
        scope = viewModelScope,
        save = ::persistDraft,
        onSaveStarted = ::onSaveStarted,
        onSaveFinished = ::onSaveFinished,
    )

    val uiState: StateFlow<EditorUiState> = mutableUiState.asStateFlow()
    val events: Flow<EditorEvent> = mutableEvents.receiveAsFlow()

    init {
        require(itemId.isNotBlank()) { "A note ID is required" }
        observeNote()
    }

    fun onTitleChanged(title: String) {
        updateDraft {
            it.copy(title = title.takeCodePoints(VaultConstraints.MAX_NOTE_TITLE_CHARACTERS))
        }
    }

    fun onBodyChanged(body: String) {
        updateDraft {
            it.copy(body = body.takeCodePoints(VaultConstraints.MAX_NOTE_BODY_CHARACTERS))
        }
    }

    fun onTagsChanged(tagsText: String) {
        updateDraft {
            it.copy(
                tagsText = tagsText.takeCodePoints(VaultConstraints.MAX_NOTE_TAG_TEXT_CHARACTERS),
            )
        }
    }

    fun setPinned(pinned: Boolean) {
        updateMetadataOptimistically(
            transform = { it.copy(isPinned = pinned) },
            rollback = { latest, previous -> latest.copy(isPinned = previous.isPinned) },
            operation = { repository.setPinned(itemId, pinned) },
        )
    }

    fun setFavorite(favorite: Boolean) {
        updateMetadataOptimistically(
            transform = { it.copy(isFavorite = favorite) },
            rollback = { latest, previous -> latest.copy(isFavorite = previous.isFavorite) },
            operation = { repository.setFavorite(itemId, favorite) },
        )
    }

    fun retrySave() {
        val current = mutableUiState.value as? EditorUiState.Content ?: return
        if (current.saveStatus != EditorSaveStatus.FAILED || !current.saveRetryable) return
        viewModelScope.launch { autosaver.flush() }
    }

    fun retryLoad() {
        val current = mutableUiState.value as? EditorUiState.Error ?: return
        if (!current.retryable) return
        mutableUiState.value = EditorUiState.Loading
        observeNote()
    }

    fun flushInBackground() {
        viewModelScope.launch { autosaver.flush() }
    }

    fun requestClose() {
        leaveAfterLocalSave(operation = null)
    }

    fun archiveAndClose() {
        val content = mutableUiState.value as? EditorUiState.Content ?: return
        leaveAfterLocalSave { repository.setArchived(itemId, !content.draft.isArchived) }
    }

    fun moveToTrashAndClose() {
        leaveAfterLocalSave { repository.moveToTrash(itemId) }
    }

    override fun onCleared() {
        autosaver.cancelPendingDelay()
    }

    private fun observeNote() {
        viewModelScope.launch {
            repository.observeNote(itemId)
                .catch { failure ->
                    if (failure is CancellationException) throw failure
                    mutableUiState.value = EditorUiState.Error(
                        noteMissing = false,
                        retryable = failure.isRetryableLocalDatabaseFailure(),
                    )
                }
                .collect { note ->
                    if (note == null) {
                        mutableUiState.value = EditorUiState.Error(
                            noteMissing = true,
                            retryable = false,
                        )
                    } else {
                        applyDatabaseNote(note)
                    }
                }
        }
    }

    private fun applyDatabaseNote(note: VaultNote) {
        val current = mutableUiState.value
        if (!hasLoadedInitialNote || current !is EditorUiState.Content) {
            hasLoadedInitialNote = true
            mutableUiState.value = EditorUiState.Content(
                draft = note.toEditorDraft(),
                editVersion = 0L,
                saveStatus = EditorSaveStatus.SAVED,
            )
            return
        }

        val updatedDraft = if (current.saveStatus == EditorSaveStatus.SAVED) {
            note.toEditorDraft()
        } else {
            current.draft.copy(
                isPinned = note.isPinned,
                isFavorite = note.isFavorite,
                isArchived = note.isArchived,
            )
        }
        mutableUiState.value = current.copy(draft = updatedDraft)
    }

    private fun updateDraft(transform: (EditorDraft) -> EditorDraft) {
        val current = mutableUiState.value as? EditorUiState.Content ?: return
        if (current.isClosing) return
        val nextDraft = transform(current.draft)
        if (nextDraft == current.draft) return
        val version = autosaver.submit(nextDraft.toPersistedDraft())
        mutableUiState.value = current.copy(
            draft = nextDraft,
            editVersion = version,
            saveStatus = EditorSaveStatus.DIRTY,
        )
    }

    private fun updateMetadataOptimistically(
        transform: (EditorDraft) -> EditorDraft,
        rollback: (latest: EditorDraft, previous: EditorDraft) -> EditorDraft,
        operation: suspend () -> RepositoryResult<Unit>,
    ) {
        val current = mutableUiState.value as? EditorUiState.Content ?: return
        if (current.isClosing || current.isMetadataSaving) return
        val previousDraft = current.draft
        val nextDraft = transform(previousDraft)
        if (nextDraft == previousDraft) return
        mutableUiState.value = current.copy(
            draft = nextDraft,
            isMetadataSaving = true,
        )
        metadataOutcome = null

        metadataJob = viewModelScope.launch {
            val result = operation()
            metadataOutcome = result
            when (result) {
                is RepositoryResult.Success -> {
                    val latest = mutableUiState.value as? EditorUiState.Content
                    if (latest != null) {
                        mutableUiState.value = latest.copy(isMetadataSaving = false)
                    }
                    result.warning?.let { mutableEvents.send(EditorEvent.ShowError(it)) }
                }

                is RepositoryResult.Failure -> {
                    val latest = mutableUiState.value as? EditorUiState.Content
                    if (latest != null) {
                        mutableUiState.value = latest.copy(
                            draft = rollback(latest.draft, previousDraft),
                            isMetadataSaving = false,
                        )
                    }
                    mutableEvents.send(EditorEvent.ShowError(result.error))
                }
            }
        }
    }

    private fun leaveAfterLocalSave(
        operation: (suspend () -> RepositoryResult<Unit>)?,
    ) {
        if (isLeaving) return
        isLeaving = true
        val pendingMetadata = metadataJob?.takeIf { !it.isCompleted }
        val content = mutableUiState.value as? EditorUiState.Content
        if (content != null) {
            mutableUiState.value = content.copy(isClosing = true)
        }
        viewModelScope.launch {
            pendingMetadata?.join()
            if (pendingMetadata != null && metadataOutcome is RepositoryResult.Failure) {
                cancelLeaving()
                return@launch
            }
            if (!autosaver.flush()) {
                cancelLeaving()
                return@launch
            }

            val result = operation?.invoke()
            when (result) {
                null -> mutableEvents.send(EditorEvent.NavigateBack)
                is RepositoryResult.Success -> {
                    result.warning?.let { mutableEvents.send(EditorEvent.ShowError(it)) }
                    mutableEvents.send(EditorEvent.NavigateBack)
                }

                is RepositoryResult.Failure -> {
                    cancelLeaving()
                    mutableEvents.send(EditorEvent.ShowError(result.error))
                }
            }
        }
    }

    private suspend fun persistDraft(draft: PersistedDraft): Boolean =
        when (
            val result = repository.saveNote(
                id = itemId,
                title = draft.title,
                body = draft.body,
                tagNames = draft.tagNames,
            )
        ) {
            is RepositoryResult.Success -> {
                lastSaveError = null
                result.warning?.let { mutableEvents.send(EditorEvent.ShowError(it)) }
                true
            }

            is RepositoryResult.Failure -> {
                lastSaveError = result.error
                mutableEvents.send(EditorEvent.ShowError(result.error))
                false
            }
        }

    private fun onSaveStarted(version: Long) {
        val current = mutableUiState.value as? EditorUiState.Content ?: return
        mutableUiState.value = current.copy(
            saveStatus = if (current.editVersion == version) {
                EditorSaveStatus.SAVING
            } else {
                EditorSaveStatus.DIRTY
            },
            saveRetryable = false,
        )
    }

    private fun onSaveFinished(version: Long, succeeded: Boolean) {
        val current = mutableUiState.value as? EditorUiState.Content ?: return
        val status = when {
            !succeeded -> EditorSaveStatus.FAILED
            current.editVersion > version -> EditorSaveStatus.DIRTY
            else -> EditorSaveStatus.SAVED
        }
        mutableUiState.value = current.copy(
            saveStatus = status,
            saveRetryable = !succeeded && lastSaveError?.isRetryable == true,
        )
    }

    private fun cancelLeaving() {
        isLeaving = false
        val current = mutableUiState.value as? EditorUiState.Content ?: return
        mutableUiState.value = current.copy(isClosing = false)
    }

    private fun EditorDraft.toPersistedDraft(): PersistedDraft = PersistedDraft(
        title = title,
        body = body,
        tagNames = tagsText.split(',').map(String::trim),
    )

    private fun VaultNote.toEditorDraft(): EditorDraft = EditorDraft(
        title = title,
        body = body,
        tagsText = tags.joinToString(separator = ", ") { it.name },
        isPinned = isPinned,
        isFavorite = isFavorite,
        isArchived = isArchived,
    )

    private fun String.takeCodePoints(maximumCodePoints: Int): String {
        if (length <= maximumCodePoints) return this
        if (codePointCount(0, length) <= maximumCodePoints) return this
        return substring(0, offsetByCodePoints(0, maximumCodePoints))
    }

    internal class Factory(
        private val itemId: String,
        private val repository: VaultRepository,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NoteEditorViewModel::class.java)) {
                "Unsupported ViewModel class: ${modelClass.name}"
            }
            @Suppress("UNCHECKED_CAST")
            return NoteEditorViewModel(itemId, repository) as T
        }
    }
}
