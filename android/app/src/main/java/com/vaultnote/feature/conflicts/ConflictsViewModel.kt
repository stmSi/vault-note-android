package com.vaultnote.feature.conflicts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.VaultItemSummary
import com.vaultnote.core.sync.SyncRepository
import java.util.concurrent.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal sealed interface ConflictsState {
    data object Loading : ConflictsState
    data object Empty : ConflictsState
    data class Content(
        val items: List<VaultItemSummary>,
        val resolvingItemId: String?,
    ) : ConflictsState
    data object Error : ConflictsState
}

internal sealed interface ConflictsEvent {
    data object Resolved : ConflictsEvent
    data object ResolveFailed : ConflictsEvent
}

internal class ConflictsViewModel(
    private val repository: SyncRepository,
) : ViewModel() {
    private val mutableEvents = Channel<ConflictsEvent>(Channel.BUFFERED)
    private var resolvingItemId: String? = null
    val events: Flow<ConflictsEvent> = mutableEvents.receiveAsFlow()
    val state = repository.observeConflicts()
        .map<List<VaultItemSummary>, ConflictsState> { items ->
            if (items.isEmpty()) ConflictsState.Empty
            else ConflictsState.Content(items, resolvingItemId)
        }
        .catch { failure ->
            if (failure is CancellationException) throw failure
            emit(ConflictsState.Error)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ConflictsState.Loading)

    fun keepVersion(itemId: String) {
        if (resolvingItemId != null) return
        resolvingItemId = itemId
        viewModelScope.launch {
            when (repository.resolveConflict(itemId)) {
                is RepositoryResult.Success -> mutableEvents.send(ConflictsEvent.Resolved)
                is RepositoryResult.Failure -> mutableEvents.send(ConflictsEvent.ResolveFailed)
            }
            resolvingItemId = null
        }
    }

    class Factory(private val repository: SyncRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ConflictsViewModel::class.java))
            return ConflictsViewModel(repository) as T
        }
    }
}
