package com.vaultnote.feature.vault

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.isRetryableLocalDatabaseFailure
import com.vaultnote.core.common.model.VaultItemSummary
import com.vaultnote.core.repository.VaultRepository
import java.util.concurrent.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

internal enum class VaultSection {
    ACTIVE,
    ARCHIVED,
    TRASH,
}

internal data class VaultListItem(
    val note: VaultItemSummary,
    val section: VaultSection,
)

internal sealed interface VaultUiState {
    val section: VaultSection
    val pageIndex: Int

    data class Loading(
        override val section: VaultSection,
        override val pageIndex: Int,
    ) : VaultUiState

    data class Empty(
        override val section: VaultSection,
        override val pageIndex: Int,
    ) : VaultUiState

    data class Content(
        override val section: VaultSection,
        override val pageIndex: Int,
        val items: List<VaultListItem>,
        val hasNextPage: Boolean,
    ) : VaultUiState

    data class Error(
        override val section: VaultSection,
        override val pageIndex: Int,
        val retryable: Boolean,
    ) : VaultUiState
}

internal sealed interface VaultEvent {
    data class OpenEditor(val itemId: String) : VaultEvent
    data class ShowError(val error: AppError) : VaultEvent
    data class ItemRestored(val source: VaultSection) : VaultEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class VaultViewModel(
    private val repository: VaultRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private data class QueryState(
        val section: VaultSection,
        val pageIndex: Int,
        val retryGeneration: Long,
    )

    private val queryState = MutableStateFlow(
        QueryState(
            section = VaultSection.ACTIVE,
            pageIndex = 0,
            retryGeneration = 0L,
        ).restoreFrom(savedStateHandle),
    )
    private val mutableEvents = Channel<VaultEvent>(capacity = Channel.BUFFERED)
    private var isCreatingNote = false

    val events: Flow<VaultEvent> = mutableEvents.receiveAsFlow()

    val uiState = queryState
        .flatMapLatest { query -> observePage(query.section, query.pageIndex) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
            initialValue = VaultUiState.Loading(
                section = queryState.value.section,
                pageIndex = queryState.value.pageIndex,
            ),
        )

    fun retry() {
        updateQuery(
            queryState.value.copy(
                retryGeneration = queryState.value.retryGeneration + 1L,
            ),
        )
    }

    fun selectSection(section: VaultSection) {
        val current = queryState.value
        if (current.section == section) return
        updateQuery(current.copy(section = section, pageIndex = 0))
    }

    fun nextPage() {
        val current = uiState.value as? VaultUiState.Content ?: return
        if (!current.hasNextPage) return
        val query = queryState.value
        updateQuery(query.copy(pageIndex = (query.pageIndex + 1).coerceAtMost(MAX_PAGE_INDEX)))
    }

    fun previousPage() {
        val query = queryState.value
        if (query.pageIndex == 0) return
        updateQuery(query.copy(pageIndex = query.pageIndex - 1))
    }

    fun createNote() {
        if (queryState.value.section != VaultSection.ACTIVE) return
        if (isCreatingNote) return
        isCreatingNote = true
        viewModelScope.launch {
            when (val result = repository.createNote()) {
                is RepositoryResult.Success -> {
                    result.warning?.let { mutableEvents.send(VaultEvent.ShowError(it)) }
                    mutableEvents.send(VaultEvent.OpenEditor(result.value))
                }

                is RepositoryResult.Failure -> {
                    mutableEvents.send(VaultEvent.ShowError(result.error))
                }
            }
            isCreatingNote = false
        }
    }

    fun setPinned(itemId: String, pinned: Boolean) {
        mutate(operation = { repository.setPinned(itemId, pinned) })
    }

    fun setFavorite(itemId: String, favorite: Boolean) {
        mutate(operation = { repository.setFavorite(itemId, favorite) })
    }

    fun restore(itemId: String, source: VaultSection) {
        val operation: suspend () -> RepositoryResult<Unit> = when (source) {
            VaultSection.ACTIVE -> return
            VaultSection.ARCHIVED -> ({ repository.setArchived(itemId, false) })
            VaultSection.TRASH -> ({ repository.restore(itemId) })
        }
        mutate(operation) { mutableEvents.send(VaultEvent.ItemRestored(source)) }
    }

    private fun mutate(
        operation: suspend () -> RepositoryResult<Unit>,
        onSuccess: suspend () -> Unit = {},
    ) {
        viewModelScope.launch {
            when (val result = operation()) {
                is RepositoryResult.Success -> {
                    result.warning?.let { mutableEvents.send(VaultEvent.ShowError(it)) }
                    onSuccess()
                }

                is RepositoryResult.Failure -> {
                    mutableEvents.send(VaultEvent.ShowError(result.error))
                }
            }
        }
    }

    private fun observePage(section: VaultSection, pageIndex: Int): Flow<VaultUiState> {
        val queryLimit = PAGE_SIZE + LOOKAHEAD_COUNT
        val offset = (pageIndex.toLong() * PAGE_SIZE)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val source = when (section) {
            VaultSection.ACTIVE -> repository.observeActiveItems(queryLimit, offset)
            VaultSection.ARCHIVED -> repository.observeArchivedItems(queryLimit, offset)
            VaultSection.TRASH -> repository.observeTrashItems(queryLimit, offset)
        }
        return source
            .transform { items ->
                if (items.isEmpty()) {
                    if (pageIndex > 0) {
                        val current = queryState.value
                        if (current.section == section && current.pageIndex == pageIndex) {
                            updateQuery(current.copy(pageIndex = pageIndex - 1))
                        }
                        return@transform
                    }
                    emit(VaultUiState.Empty(section, pageIndex))
                } else {
                    emit(
                        VaultUiState.Content(
                            section = section,
                            pageIndex = pageIndex,
                            items = items.take(PAGE_SIZE).map { item ->
                                VaultListItem(item, section)
                            },
                            hasNextPage = items.size > PAGE_SIZE,
                        ),
                    )
                }
            }
            .catch { failure ->
                if (failure is CancellationException) throw failure
                emit(
                    VaultUiState.Error(
                        section = section,
                        pageIndex = pageIndex,
                        retryable = failure.isRetryableLocalDatabaseFailure(),
                    ),
                )
            }
            .onStart { emit(VaultUiState.Loading(section, pageIndex)) }
    }

    private fun updateQuery(query: QueryState) {
        queryState.value = query
        savedStateHandle[KEY_SECTION] = query.section.name
        savedStateHandle[KEY_PAGE_INDEX] = query.pageIndex
    }

    private fun QueryState.restoreFrom(handle: SavedStateHandle): QueryState {
        val restoredSection = handle.get<String>(KEY_SECTION)
            ?.let { value -> runCatching { VaultSection.valueOf(value) }.getOrNull() }
            ?: section
        val restoredPage = handle.get<Int>(KEY_PAGE_INDEX)
            ?.coerceIn(0, MAX_PAGE_INDEX)
            ?: pageIndex
        return copy(section = restoredSection, pageIndex = restoredPage)
    }

    internal class Factory(
        private val repository: VaultRepository,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(VaultViewModel::class.java)) {
                "Unsupported ViewModel class: ${modelClass.name}"
            }
            @Suppress("UNCHECKED_CAST")
            return VaultViewModel(repository, extras.createSavedStateHandle()) as T
        }

    }

    private companion object {
        const val PAGE_SIZE = 100
        const val LOOKAHEAD_COUNT = 1
        const val MAX_PAGE_INDEX = Int.MAX_VALUE / PAGE_SIZE
        const val KEY_SECTION = "vault_section"
        const val KEY_PAGE_INDEX = "vault_page_index"
    }
}
