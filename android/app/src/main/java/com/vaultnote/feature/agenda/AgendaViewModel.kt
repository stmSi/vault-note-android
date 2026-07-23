package com.vaultnote.feature.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vaultnote.core.common.model.AgendaEntry
import com.vaultnote.core.repository.VaultRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal sealed interface AgendaUiState {
    data object Loading : AgendaUiState
    data class Content(
        val entries: List<AgendaEntry>,
        val includeCompleted: Boolean,
        val selectedDate: LocalDate?,
    ) : AgendaUiState
    data object Error : AgendaUiState
}

internal class AgendaViewModel(
    private val repository: VaultRepository,
) : ViewModel() {
    private val includeCompleted = MutableStateFlow(false)
    private val selectedDate = MutableStateFlow<LocalDate?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<AgendaUiState> = includeCompleted
        .flatMapLatest { completed ->
            repository.observeAgenda(completed).map { rows ->
                AgendaSnapshot(rows, completed)
            }
        }
        .flatMapLatest { snapshot ->
            selectedDate.map<LocalDate?, AgendaUiState> { date ->
                val entries = if (date == null) {
                    snapshot.entries
                } else {
                    snapshot.entries.filter { row ->
                        Instant.ofEpochMilli(row.entry.occurrenceAtEpochMillis)
                            .atZone(ZoneId.of(row.entry.timeZoneId))
                            .toLocalDate() == date
                    }
                }
                AgendaUiState.Content(entries, snapshot.includeCompleted, date)
            }
        }
        .catch { emit(AgendaUiState.Error) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            AgendaUiState.Loading,
        )

    fun setIncludeCompleted(value: Boolean) {
        includeCompleted.value = value
    }

    fun selectDate(date: LocalDate?) {
        selectedDate.value = date
    }

    private data class AgendaSnapshot(
        val entries: List<AgendaEntry>,
        val includeCompleted: Boolean,
    )

    class Factory(
        private val repository: VaultRepository,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AgendaViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return AgendaViewModel(repository) as T
        }
    }
}
