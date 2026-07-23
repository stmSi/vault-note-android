package com.vaultnote.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.security.LockPolicy
import com.vaultnote.core.security.LockPolicyRepository
import com.vaultnote.core.security.VaultLockManager
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal sealed interface SecuritySettingsState {
    data object Loading : SecuritySettingsState
    data class Content(val policy: LockPolicy, val isSaving: Boolean) : SecuritySettingsState
    data object Error : SecuritySettingsState
}

internal sealed interface SecuritySettingsEvent {
    data object RequestAuthentication : SecuritySettingsEvent
    data object ShowSaveError : SecuritySettingsEvent
}

internal class SecuritySettingsViewModel(
    private val repository: LockPolicyRepository,
    private val lockManager: VaultLockManager,
) : ViewModel() {
    private val mutableState = MutableStateFlow<SecuritySettingsState>(SecuritySettingsState.Loading)
    private val mutableEvents = Channel<SecuritySettingsEvent>(Channel.BUFFERED)
    private var saveJob: Job? = null
    private var observeJob: Job? = null

    val state: StateFlow<SecuritySettingsState> = mutableState.asStateFlow()
    val events: Flow<SecuritySettingsEvent> = mutableEvents.receiveAsFlow()

    init {
        observePolicy()
    }

    fun retry() {
        if (observeJob?.isActive == true) return
        mutableState.value = SecuritySettingsState.Loading
        observePolicy()
    }

    private fun observePolicy() {
        observeJob = viewModelScope.launch {
            repository.observe().collect { result ->
                when (result) {
                    is RepositoryResult.Success -> {
                        val saving = (mutableState.value as? SecuritySettingsState.Content)?.isSaving
                            ?: false
                        mutableState.value = SecuritySettingsState.Content(result.value, saving)
                    }
                    is RepositoryResult.Failure -> mutableState.value = SecuritySettingsState.Error
                }
            }
        }
    }

    fun requestLockEnabled(enabled: Boolean) {
        val current = contentOrNull() ?: return
        if (current.isSaving || current.policy.isLockEnabled == enabled) return
        if (enabled) {
            viewModelScope.launch { mutableEvents.send(SecuritySettingsEvent.RequestAuthentication) }
        } else {
            save(current.policy.copy(isLockEnabled = false))
        }
    }

    fun confirmLockEnabled() {
        val current = contentOrNull() ?: return
        if (current.isSaving || current.policy.isLockEnabled) return
        lockManager.unlock()
        save(current.policy.copy(isLockEnabled = true))
    }

    fun setScreenshotBlocking(enabled: Boolean) {
        val current = contentOrNull() ?: return
        if (current.isSaving || current.policy.blockScreenshots == enabled) return
        save(current.policy.copy(blockScreenshots = enabled))
    }

    fun setBackgroundTimeout(timeoutMillis: Long) {
        val current = contentOrNull() ?: return
        if (
            current.isSaving ||
            timeoutMillis !in LockPolicy.SUPPORTED_TIMEOUTS ||
            current.policy.backgroundTimeoutMillis == timeoutMillis
        ) {
            return
        }
        save(current.policy.copy(backgroundTimeoutMillis = timeoutMillis))
    }

    private fun save(policy: LockPolicy) {
        if (saveJob?.isActive == true) return
        mutableState.value = SecuritySettingsState.Content(policy, isSaving = true)
        saveJob = viewModelScope.launch {
            try {
                when (repository.save(policy)) {
                    is RepositoryResult.Success -> Unit
                    is RepositoryResult.Failure -> {
                        mutableState.value = SecuritySettingsState.Content(
                            lockManager.state.value.policy,
                            isSaving = false,
                        )
                        mutableEvents.send(SecuritySettingsEvent.ShowSaveError)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    private fun contentOrNull(): SecuritySettingsState.Content? =
        mutableState.value as? SecuritySettingsState.Content

    class Factory(
        private val repository: LockPolicyRepository,
        private val lockManager: VaultLockManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SecuritySettingsViewModel::class.java))
            return SecuritySettingsViewModel(repository, lockManager) as T
        }
    }
}
