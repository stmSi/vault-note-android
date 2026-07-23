package com.vaultnote.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vaultnote.core.search.SearchQueryCompilation
import com.vaultnote.core.search.SearchQueryCompiler
import com.vaultnote.core.search.SearchRepository
import com.vaultnote.core.search.VaultSearchResult
import java.util.concurrent.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

internal sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data object Empty : SearchUiState
    data class Content(val results: List<VaultSearchResult>) : SearchUiState
    data object QueryTooLong : SearchUiState
    data object Error : SearchUiState
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal class SearchViewModel(
    private val repository: SearchRepository,
) : ViewModel() {
    private data class Input(val text: String, val retryGeneration: Long)

    private val input = MutableStateFlow(Input("", 0L))
    val query: StateFlow<String> = input
        .map { value -> value.text }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val state: StateFlow<SearchUiState> = input
        .debounce(SEARCH_DEBOUNCE_MILLIS)
        .distinctUntilChanged()
        .flatMapLatest(::observeInput)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
            initialValue = SearchUiState.Idle,
        )

    fun setQuery(text: String) {
        if (input.value.text == text) return
        input.update { current -> current.copy(text = text) }
    }

    fun retry() {
        input.update { current -> current.copy(retryGeneration = current.retryGeneration + 1L) }
    }

    private fun observeInput(input: Input): Flow<SearchUiState> = when (
        val compiled = SearchQueryCompiler.compile(input.text)
    ) {
        SearchQueryCompilation.Empty -> flowOf(SearchUiState.Idle)
        SearchQueryCompilation.TooLong -> flowOf(SearchUiState.QueryTooLong)
        is SearchQueryCompilation.Valid -> repository.observe(compiled.query, SEARCH_RESULT_LIMIT)
            .map { results ->
                if (results.isEmpty()) SearchUiState.Empty else SearchUiState.Content(results)
            }
            .onStart { emit(SearchUiState.Loading) }
            .catch { failure ->
                if (failure is CancellationException) throw failure
                emit(SearchUiState.Error)
            }
    }

    class Factory(private val repository: SearchRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SearchViewModel::class.java))
            return SearchViewModel(repository) as T
        }
    }

    private companion object {
        // Short enough to feel like per-character typeahead while still coalescing fast IME input.
        const val SEARCH_DEBOUNCE_MILLIS = 120L
        const val SEARCH_RESULT_LIMIT = 100
    }
}
