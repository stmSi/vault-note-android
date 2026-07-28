package com.vaultnote.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vaultnote.core.sync.SyncOverview
import com.vaultnote.core.sync.SyncRepository
import com.vaultnote.core.sync.SyncScheduleResult
import com.vaultnote.core.sync.SyncScheduler
import com.vaultnote.core.sync.lan.LanDiscoveryResult
import com.vaultnote.core.sync.lan.LanRelayCandidate
import com.vaultnote.core.sync.lan.LanSyncConnectionRepository
import com.vaultnote.core.sync.lan.RelayConnectionState
import com.vaultnote.core.sync.lan.RelayPairingInput
import com.vaultnote.core.sync.lan.RelayPairingResult
import java.util.concurrent.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal sealed interface SyncStatusState {
    data object Loading : SyncStatusState
    data class Content(
        val overview: SyncOverview,
        val connection: RelayConnectionState,
        val isConnectionActionRunning: Boolean,
    ) : SyncStatusState
    data object Error : SyncStatusState
}

internal sealed interface SyncStatusEvent {
    data object Scheduled : SyncStatusEvent
    data object ScheduleFailed : SyncStatusEvent
    data object ConnectionRequired : SyncStatusEvent
    data class RelayDiscovered(val relay: LanRelayCandidate) : SyncStatusEvent
    data class PairingFinished(val result: RelayPairingResult) : SyncStatusEvent
    data object DiscoveryNotFound : SyncStatusEvent
    data object DiscoveryPermissionDenied : SyncStatusEvent
    data object DiscoveryFailed : SyncStatusEvent
    data class DisconnectFinished(val succeeded: Boolean) : SyncStatusEvent
}

internal class SyncStatusViewModel(
    repository: SyncRepository,
    private val scheduler: SyncScheduler,
    private val connectionRepository: LanSyncConnectionRepository,
) : ViewModel() {
    private val mutableEvents = Channel<SyncStatusEvent>(Channel.BUFFERED)
    private val connectionActionRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
    val events: Flow<SyncStatusEvent> = mutableEvents.receiveAsFlow()
    val state = combine(
        repository.observeOverview(),
        connectionRepository.state,
        connectionActionRunning,
    ) { overview, connection, busy ->
        SyncStatusState.Content(overview, connection, busy)
    }
        .map<SyncStatusState.Content, SyncStatusState> { it }
        .catch { failure ->
            if (failure is CancellationException) throw failure
            emit(SyncStatusState.Error)
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            SyncStatusState.Loading,
        )

    init {
        viewModelScope.launch { connectionRepository.refresh() }
    }

    fun syncNow() {
        if (connectionRepository.state.value !is RelayConnectionState.Configured) {
            viewModelScope.launch { mutableEvents.send(SyncStatusEvent.ConnectionRequired) }
            return
        }
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

    fun discoverRelay() {
        if (connectionActionRunning.value) return
        viewModelScope.launch {
            connectionActionRunning.value = true
            try {
                when (val result = connectionRepository.discover()) {
                    is LanDiscoveryResult.Found ->
                        mutableEvents.send(SyncStatusEvent.RelayDiscovered(result.relay))
                    LanDiscoveryResult.NotFound ->
                        mutableEvents.send(SyncStatusEvent.DiscoveryNotFound)
                    LanDiscoveryResult.PermissionDenied ->
                        mutableEvents.send(SyncStatusEvent.DiscoveryPermissionDenied)
                    LanDiscoveryResult.Unavailable ->
                        mutableEvents.send(SyncStatusEvent.DiscoveryFailed)
                }
            } finally {
                connectionActionRunning.value = false
            }
        }
    }

    fun pair(
        hostAddress: String,
        port: String,
        vaultId: String,
        certificateSha256: String,
        authenticationToken: String,
        syncPassword: CharArray,
    ) {
        if (connectionActionRunning.value) {
            syncPassword.fill('\u0000')
            return
        }
        val parsedPort = port.toIntOrNull()
        if (parsedPort == null) {
            syncPassword.fill('\u0000')
            viewModelScope.launch {
                mutableEvents.send(
                    SyncStatusEvent.PairingFinished(RelayPairingResult.InvalidConfiguration),
                )
            }
            return
        }
        viewModelScope.launch {
            connectionActionRunning.value = true
            try {
                val result = connectionRepository.pair(
                    RelayPairingInput(
                        hostAddress = hostAddress,
                        port = parsedPort,
                        vaultId = vaultId.takeIf(String::isNotBlank),
                        certificateSha256 = certificateSha256,
                        authenticationToken = authenticationToken,
                        syncPassword = syncPassword,
                    ),
                )
                mutableEvents.send(SyncStatusEvent.PairingFinished(result))
            } finally {
                syncPassword.fill('\u0000')
                connectionActionRunning.value = false
            }
        }
    }

    fun disconnect() {
        if (connectionActionRunning.value) return
        viewModelScope.launch {
            connectionActionRunning.value = true
            try {
                mutableEvents.send(
                    SyncStatusEvent.DisconnectFinished(connectionRepository.disconnect()),
                )
            } finally {
                connectionActionRunning.value = false
            }
        }
    }

    class Factory(
        private val repository: SyncRepository,
        private val scheduler: SyncScheduler,
        private val connectionRepository: LanSyncConnectionRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SyncStatusViewModel::class.java))
            return SyncStatusViewModel(repository, scheduler, connectionRepository) as T
        }
    }
}
