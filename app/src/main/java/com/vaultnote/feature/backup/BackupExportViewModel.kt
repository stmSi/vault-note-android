package com.vaultnote.feature.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vaultnote.core.backup.BackupRepository
import com.vaultnote.core.backup.PreparedBackupExport
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.security.VaultLockManager
import java.util.concurrent.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class BackupExportState(
    val isWaitingForDestination: Boolean = false,
    val isExporting: Boolean = false,
)

internal sealed interface BackupExportEvent {
    data object ChooseDestination : BackupExportEvent
    data object ExportComplete : BackupExportEvent
    data class ShowError(val reason: BackupUiError) : BackupExportEvent
}

internal enum class BackupUiError {
    PASSWORD_MISMATCH,
    PASSWORD_INVALID,
    WRONG_PASSWORD,
    INVALID_BACKUP,
    INSUFFICIENT_SPACE,
    PERMISSION_DENIED,
    LOCKED,
    UNKNOWN,
}

internal class BackupExportViewModel(
    private val repository: BackupRepository,
    private val lockManager: VaultLockManager,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BackupExportState())
    private val mutableEvents = Channel<BackupExportEvent>(Channel.BUFFERED)
    private var prepared: PreparedBackupExport? = null

    val state: StateFlow<BackupExportState> = mutableState.asStateFlow()
    val events: Flow<BackupExportEvent> = mutableEvents.receiveAsFlow()

    fun requestExport(password: CharArray, confirmation: CharArray) {
        if (mutableState.value.isExporting || mutableState.value.isWaitingForDestination) {
            password.fill('\u0000')
            confirmation.fill('\u0000')
            return
        }
        if (!password.contentEquals(confirmation)) {
            password.fill('\u0000')
            confirmation.fill('\u0000')
            viewModelScope.launch {
                mutableEvents.send(
                    BackupExportEvent.ShowError(BackupUiError.PASSWORD_MISMATCH),
                )
            }
            return
        }
        confirmation.fill('\u0000')
        when (val result = repository.prepareExport(password)) {
            is RepositoryResult.Success -> {
                prepared = result.value
                mutableState.value = BackupExportState(isWaitingForDestination = true)
                viewModelScope.launch { mutableEvents.send(BackupExportEvent.ChooseDestination) }
            }
            is RepositoryResult.Failure -> viewModelScope.launch {
                mutableEvents.send(BackupExportEvent.ShowError(result.error.toBackupUiError()))
            }
        }
    }

    fun completeDestination(destination: Uri?) {
        val export = prepared
        if (export == null) {
            if (destination != null) {
                viewModelScope.launch {
                    repository.discardDestination(destination)
                    mutableEvents.send(BackupExportEvent.ShowError(BackupUiError.UNKNOWN))
                }
            }
            return
        }
        prepared = null
        if (destination == null) {
            repository.cancelExport(export)
            mutableState.value = BackupExportState()
            return
        }
        mutableState.value = BackupExportState(isExporting = true)
        viewModelScope.launch {
            var handedToRepository = false
            try {
                lockManager.state.first { it.isPolicyLoaded && !it.isLocked }
                handedToRepository = true
                when (val result = repository.export(export, destination)) {
                    is RepositoryResult.Success -> {
                        mutableState.value = BackupExportState()
                        mutableEvents.send(BackupExportEvent.ExportComplete)
                    }
                    is RepositoryResult.Failure -> {
                        mutableState.value = BackupExportState()
                        mutableEvents.send(
                            BackupExportEvent.ShowError(result.error.toBackupUiError()),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                if (!handedToRepository) {
                    repository.cancelExport(export)
                    withContext(NonCancellable) {
                        repository.discardDestination(destination)
                    }
                }
                throw cancelled
            }
        }
    }

    override fun onCleared() {
        prepared?.let(repository::cancelExport)
        prepared = null
        super.onCleared()
    }

    class Factory(
        private val repository: BackupRepository,
        private val lockManager: VaultLockManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BackupExportViewModel::class.java))
            return BackupExportViewModel(repository, lockManager) as T
        }
    }
}

internal fun AppError.toBackupUiError(): BackupUiError = when (this) {
    AppError.AuthenticationExpired -> BackupUiError.LOCKED
    AppError.PermissionDenied -> BackupUiError.PERMISSION_DENIED
    is AppError.InsufficientStorage,
    is AppError.FileTooLarge,
    -> BackupUiError.INSUFFICIENT_SPACE
    is AppError.InvalidInput -> BackupUiError.PASSWORD_INVALID
    is AppError.BackupValidationFailure -> when (reason) {
        AppError.BackupValidationReason.WRONG_KEY -> BackupUiError.WRONG_PASSWORD
        else -> BackupUiError.INVALID_BACKUP
    }
    else -> BackupUiError.UNKNOWN
}
