package com.vaultnote.feature.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vaultnote.core.backup.BackupRepository
import com.vaultnote.core.backup.BackupSummary
import com.vaultnote.core.backup.PreparedBackupRestore
import com.vaultnote.core.backup.RestoreSummary
import com.vaultnote.core.common.RepositoryResult
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal data class BackupRestoreState(
    val hasSource: Boolean = false,
    val isValidating: Boolean = false,
    val isRestoring: Boolean = false,
    val confirmation: RestoreConfirmation? = null,
)

internal data class RestoreConfirmation(
    val summary: BackupSummary,
    val copiedItemCount: Long,
)

internal sealed interface BackupRestoreEvent {
    data class RestoreComplete(val summary: RestoreSummary, val syncDelayed: Boolean) :
        BackupRestoreEvent
    data class ShowError(val reason: BackupUiError) : BackupRestoreEvent
}

internal class BackupRestoreViewModel(
    private val repository: BackupRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BackupRestoreState())
    private val mutableEvents = Channel<BackupRestoreEvent>(Channel.BUFFERED)
    private var source: Uri? = null
    private var prepared: PreparedBackupRestore? = null
    private var operation: Job? = null

    val state: StateFlow<BackupRestoreState> = mutableState.asStateFlow()
    val events: Flow<BackupRestoreEvent> = mutableEvents.receiveAsFlow()

    fun selectSource(uri: Uri?) {
        if (operation?.isActive == true || prepared != null) return
        source = uri
        mutableState.value = BackupRestoreState(hasSource = uri != null)
    }

    fun validate(password: CharArray) {
        val selected = source
        if (selected == null || operation?.isActive == true || prepared != null) {
            password.fill('\u0000')
            return
        }
        mutableState.value = BackupRestoreState(hasSource = true, isValidating = true)
        operation = viewModelScope.launch {
            try {
                when (val result = repository.prepareRestore(selected, password)) {
                    is RepositoryResult.Success -> {
                        prepared = result.value
                        mutableState.value = BackupRestoreState(
                            hasSource = true,
                            confirmation = RestoreConfirmation(
                                summary = result.value.backupSummary,
                                copiedItemCount = result.value.copiedItemCount,
                            ),
                        )
                    }
                    is RepositoryResult.Failure -> {
                        mutableState.value = BackupRestoreState(hasSource = true)
                        mutableEvents.send(
                            BackupRestoreEvent.ShowError(result.error.toBackupUiError()),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    fun confirmRestore() {
        val restore = prepared ?: return
        prepared = null
        mutableState.value = BackupRestoreState(hasSource = true, isRestoring = true)
        operation = viewModelScope.launch {
            when (val result = repository.commitRestore(restore)) {
                is RepositoryResult.Success -> {
                    source = null
                    mutableState.value = BackupRestoreState()
                    mutableEvents.send(
                        BackupRestoreEvent.RestoreComplete(
                            result.value,
                            syncDelayed = result.warning != null,
                        ),
                    )
                }
                is RepositoryResult.Failure -> {
                    mutableState.value = BackupRestoreState(hasSource = true)
                    mutableEvents.send(
                        BackupRestoreEvent.ShowError(result.error.toBackupUiError()),
                    )
                }
            }
        }
    }

    fun cancelPreparedRestore() {
        prepared?.let(repository::cancelRestore)
        prepared = null
        mutableState.value = BackupRestoreState(hasSource = source != null)
    }

    override fun onCleared() {
        prepared?.let(repository::cancelRestore)
        prepared = null
        super.onCleared()
    }

    class Factory(private val repository: BackupRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BackupRestoreViewModel::class.java))
            return BackupRestoreViewModel(repository) as T
        }
    }
}
