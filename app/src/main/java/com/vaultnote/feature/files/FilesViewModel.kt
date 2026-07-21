package com.vaultnote.feature.files

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.vaultnote.core.common.isRetryableLocalDatabaseFailure
import com.vaultnote.core.common.model.VaultAttachment
import com.vaultnote.core.files.AttachmentFilenameSearch
import com.vaultnote.core.repository.AttachmentRepository
import java.util.concurrent.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

internal sealed interface FilesUiState {
    val pageIndex: Int
    val searchText: String

    data class Loading(
        override val pageIndex: Int,
        override val searchText: String,
    ) : FilesUiState

    data class Empty(
        override val pageIndex: Int,
        override val searchText: String,
    ) : FilesUiState

    data class Content(
        override val pageIndex: Int,
        override val searchText: String,
        val files: List<VaultAttachment>,
        val hasNextPage: Boolean,
    ) : FilesUiState

    data class Error(
        override val pageIndex: Int,
        override val searchText: String,
        val retryable: Boolean,
    ) : FilesUiState
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal class FilesViewModel(
    private val repository: AttachmentRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private data class PageRequest(val pageIndex: Int, val retryGeneration: Long)
    private data class Query(
        val pageIndex: Int,
        val retryGeneration: Long,
        val searchText: String,
    )

    private val pageRequest = MutableStateFlow(
        PageRequest(
            pageIndex = savedStateHandle.get<Int>(KEY_PAGE_INDEX)?.coerceIn(0, MAX_PAGE_INDEX) ?: 0,
            retryGeneration = 0L,
        ),
    )
    private val mutableSearchText = MutableStateFlow(
        AttachmentFilenameSearch.boundInput(savedStateHandle[KEY_SEARCH_TEXT] ?: ""),
    )
    val searchText: StateFlow<String> = mutableSearchText.asStateFlow()

    private val query = combine(
        mutableSearchText.debounce(SEARCH_DEBOUNCE_MILLIS).distinctUntilChanged(),
        pageRequest,
    ) { searchText, page ->
        Query(
            pageIndex = page.pageIndex,
            retryGeneration = page.retryGeneration,
            searchText = searchText.trim(),
        )
    }

    val state = query
        .flatMapLatest(::observePage)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
            initialValue = FilesUiState.Loading(
                pageIndex = pageRequest.value.pageIndex,
                searchText = mutableSearchText.value.trim(),
            ),
        )

    fun retry() {
        pageRequest.value = pageRequest.value.copy(
            retryGeneration = pageRequest.value.retryGeneration + 1L,
        )
    }

    fun updateSearchText(value: String) {
        val bounded = AttachmentFilenameSearch.boundInput(value)
        if (bounded == mutableSearchText.value) return
        mutableSearchText.value = bounded
        savedStateHandle[KEY_SEARCH_TEXT] = bounded
        updatePage(0)
    }

    fun nextPage() {
        val current = state.value as? FilesUiState.Content ?: return
        if (!current.hasNextPage) return
        updatePage((current.pageIndex + 1).coerceAtMost(MAX_PAGE_INDEX))
    }

    fun previousPage() {
        val current = pageRequest.value.pageIndex
        if (current > 0) updatePage(current - 1)
    }

    private fun observePage(query: Query): Flow<FilesUiState> {
        val offset = (query.pageIndex.toLong() * PAGE_SIZE)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val source = if (query.searchText.isBlank()) {
            repository.observeActiveFiles(PAGE_SIZE + LOOKAHEAD_COUNT, offset)
        } else {
            repository.observeActiveFilesMatchingName(
                searchText = query.searchText,
                limit = PAGE_SIZE + LOOKAHEAD_COUNT,
                offset = offset,
            )
        }
        return source
            .map { files ->
                if (files.isEmpty()) {
                    if (query.pageIndex > 0) {
                        updatePage(query.pageIndex - 1)
                        FilesUiState.Loading(query.pageIndex - 1, query.searchText)
                    } else {
                        FilesUiState.Empty(query.pageIndex, query.searchText)
                    }
                } else {
                    FilesUiState.Content(
                        pageIndex = query.pageIndex,
                        searchText = query.searchText,
                        files = files.take(PAGE_SIZE),
                        hasNextPage = files.size > PAGE_SIZE,
                    )
                }
            }
            .catch { failure ->
                if (failure is CancellationException) throw failure
                emit(
                    FilesUiState.Error(
                        pageIndex = query.pageIndex,
                        searchText = query.searchText,
                        retryable = failure.isRetryableLocalDatabaseFailure(),
                    ),
                )
            }
            .onStart { emit(FilesUiState.Loading(query.pageIndex, query.searchText)) }
    }

    private fun updatePage(pageIndex: Int) {
        pageRequest.value = pageRequest.value.copy(pageIndex = pageIndex)
        savedStateHandle[KEY_PAGE_INDEX] = pageIndex
    }

    internal class Factory(
        private val repository: AttachmentRepository,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(FilesViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return FilesViewModel(repository, extras.createSavedStateHandle()) as T
        }
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val LOOKAHEAD_COUNT = 1
        const val MAX_PAGE_INDEX = Int.MAX_VALUE / PAGE_SIZE
        const val KEY_PAGE_INDEX = "files_page_index"
        const val KEY_SEARCH_TEXT = "files_search_text"
        const val SEARCH_DEBOUNCE_MILLIS = 150L
    }
}
