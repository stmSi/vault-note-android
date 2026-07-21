package com.vaultnote.feature.files

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.vaultnote.core.common.isRetryableLocalDatabaseFailure
import com.vaultnote.core.common.model.VaultAttachment
import com.vaultnote.core.repository.AttachmentRepository
import java.util.concurrent.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

internal sealed interface FilesUiState {
    val pageIndex: Int

    data class Loading(override val pageIndex: Int) : FilesUiState
    data class Empty(override val pageIndex: Int) : FilesUiState
    data class Content(
        override val pageIndex: Int,
        val files: List<VaultAttachment>,
        val hasNextPage: Boolean,
    ) : FilesUiState

    data class Error(
        override val pageIndex: Int,
        val retryable: Boolean,
    ) : FilesUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class FilesViewModel(
    private val repository: AttachmentRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private data class Query(val pageIndex: Int, val retryGeneration: Long)

    private val query = MutableStateFlow(
        Query(
            pageIndex = savedStateHandle.get<Int>(KEY_PAGE_INDEX)?.coerceIn(0, MAX_PAGE_INDEX) ?: 0,
            retryGeneration = 0L,
        ),
    )

    val state = query
        .flatMapLatest(::observePage)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
            initialValue = FilesUiState.Loading(query.value.pageIndex),
        )

    fun retry() {
        query.value = query.value.copy(retryGeneration = query.value.retryGeneration + 1L)
    }

    fun nextPage() {
        val current = state.value as? FilesUiState.Content ?: return
        if (!current.hasNextPage) return
        updatePage((current.pageIndex + 1).coerceAtMost(MAX_PAGE_INDEX))
    }

    fun previousPage() {
        val current = query.value.pageIndex
        if (current > 0) updatePage(current - 1)
    }

    private fun observePage(query: Query): Flow<FilesUiState> {
        val offset = (query.pageIndex.toLong() * PAGE_SIZE)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        return repository.observeActiveFiles(PAGE_SIZE + LOOKAHEAD_COUNT, offset)
            .map { files ->
                if (files.isEmpty()) {
                    if (query.pageIndex > 0) {
                        updatePage(query.pageIndex - 1)
                        FilesUiState.Loading(query.pageIndex - 1)
                    } else {
                        FilesUiState.Empty(query.pageIndex)
                    }
                } else {
                    FilesUiState.Content(
                        pageIndex = query.pageIndex,
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
                        retryable = failure.isRetryableLocalDatabaseFailure(),
                    ),
                )
            }
            .onStart { emit(FilesUiState.Loading(query.pageIndex)) }
    }

    private fun updatePage(pageIndex: Int) {
        query.value = query.value.copy(pageIndex = pageIndex)
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
    }
}
