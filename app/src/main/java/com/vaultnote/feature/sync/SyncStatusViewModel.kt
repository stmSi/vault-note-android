package com.vaultnote.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vaultnote.core.sync.SyncOverview
import com.vaultnote.core.sync.SyncRepository
import com.vaultnote.core.sync.SyncScheduleResult
import com.vaultnote.core.sync.SyncScheduler
import java.util.concurrent.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal sealed interface SyncStatusState {
    data object Loading : SyncStatusState
    data class Content(val overview: SyncOverview) : SyncStatusState
    data object Error : SyncStatusState
}

internal sealed interface SyncStatusEvent {
    data object Scheduled : SyncStatusEvent
    data object ScheduleFailed : SyncStatusEvent
}

internal class SyncStatusViewModel(
    repository: SyncRepository,
    private val scheduler: SyncScheduler,
) : ViewModel() {
    private val mutableEvents = Channel<SyncStatusEvent>(Channel.BUFFERED)
    val events: Flow<SyncStatusEvent> = mutableEvents.receiveAsFlow()
    val state = repository.observeOverview()
        .map<SyncOverview, SyncStatusState>(SyncStatusState::Content)
        .catch { failure ->
            if (failure is CancellationException) throw failure
            emit(SyncStatusState.Error)
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            SyncStatusState.Loading,
        )

    fun syncNow() {
        val result = scheduler.requestSync()
        viewModelScope.launch {
            mutableEvents.send(
                if (result is SyncScheduleResult.Rejected) {
                    SyncStatusEvent.ScheduleFailed
                } else {
                    SyncStatusEvent.Scheduled
                },
            )
        }
    }

    class Factory(
        private val repository: SyncRepository,
        private val scheduler: SyncScheduler,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SyncStatusViewModel::class.java))
            return SyncStatusViewModel(repository, scheduler) as T
        }
    }
}
