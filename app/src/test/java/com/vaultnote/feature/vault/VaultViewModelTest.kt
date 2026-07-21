package com.vaultnote.feature.vault

import androidx.lifecycle.SavedStateHandle
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.ItemSyncStatus
import com.vaultnote.core.common.model.VaultItemSummary
import com.vaultnote.core.common.model.VaultItemType
import com.vaultnote.core.common.model.VaultItemColor
import com.vaultnote.core.common.model.VaultNote
import com.vaultnote.core.common.model.VaultTag
import com.vaultnote.core.repository.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelTest {
    @get:Rule
    val mainDispatcherRule = VaultMainDispatcherRule()

    @Test
    fun `section and bounded page survive ViewModel recreation`() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle(
                mapOf(
                    "vault_section" to VaultSection.TRASH.name,
                    "vault_page_index" to 2,
                ),
            )
            val repository = WindowedFakeRepository(itemCount = 250)
            val viewModel = VaultViewModel(repository, savedState)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect {}
            }
            runCurrent()

            val restored = viewModel.uiState.value as VaultUiState.Content
            assertEquals(VaultSection.TRASH, restored.section)
            assertEquals(2, restored.pageIndex)
            assertEquals(50, restored.items.size)

            viewModel.previousPage()
            runCurrent()
            assertEquals(1, (viewModel.uiState.value as VaultUiState.Content).pageIndex)
            assertEquals(1, savedState.get<Int>("vault_page_index"))
        }

    @Test
    fun `empty trailing page automatically returns to the previous page`() =
        runTest(mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle(
                mapOf(
                    "vault_section" to VaultSection.ACTIVE.name,
                    "vault_page_index" to 2,
                ),
            )
            val viewModel = VaultViewModel(
                repository = WindowedFakeRepository(itemCount = 150),
                savedStateHandle = savedState,
            )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect {}
            }

            advanceUntilIdle()

            val content = viewModel.uiState.value as VaultUiState.Content
            assertEquals(1, content.pageIndex)
            assertEquals(50, content.items.size)
            assertEquals(1, savedState.get<Int>("vault_page_index"))
        }

    @Test
    fun `swipe actions and undo call the matching soft state mutations`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = WindowedFakeRepository(itemCount = 1)
            val viewModel = VaultViewModel(repository, SavedStateHandle())
            val itemId = summary(0).id

            viewModel.handleSwipe(itemId, VaultItemChange.ARCHIVED)
            viewModel.handleSwipe(itemId, VaultItemChange.TRASHED)
            viewModel.handleSwipe(itemId, VaultItemChange.RESTORED_FROM_ARCHIVE)
            viewModel.handleSwipe(itemId, VaultItemChange.RESTORED_FROM_TRASH)
            advanceUntilIdle()

            assertEquals(listOf(itemId to true, itemId to false), repository.archiveChanges)
            assertEquals(listOf(itemId), repository.trashedItems)
            assertEquals(listOf(itemId), repository.restoredItems)

            viewModel.undo(itemId, VaultItemChange.ARCHIVED)
            viewModel.undo(itemId, VaultItemChange.TRASHED)
            advanceUntilIdle()

            assertEquals(itemId to false, repository.archiveChanges.last())
            assertEquals(itemId, repository.restoredItems.last())
        }

    private class WindowedFakeRepository(itemCount: Int) : VaultRepository {
        private val items = (0 until itemCount).map(::summary)
        val archiveChanges = mutableListOf<Pair<String, Boolean>>()
        val trashedItems = mutableListOf<String>()
        val restoredItems = mutableListOf<String>()

        override fun observeActiveItems(limit: Int, offset: Int): Flow<List<VaultItemSummary>> =
            window(limit, offset)

        override fun observeArchivedItems(limit: Int, offset: Int): Flow<List<VaultItemSummary>> =
            window(limit, offset)

        override fun observeTrashItems(limit: Int, offset: Int): Flow<List<VaultItemSummary>> =
            window(limit, offset)

        override fun observeNote(id: String): Flow<VaultNote?> = emptyFlow()

        override fun observeTags(): Flow<List<VaultTag>> = emptyFlow()

        override suspend fun createNote(title: String, body: String): RepositoryResult<String> =
            RepositoryResult.Success("new-note")

        override suspend fun createAttachmentContainer(
            title: String,
            type: VaultItemType,
        ): RepositoryResult<String> = RepositoryResult.Success("new-file")

        override suspend fun saveNote(
            id: String,
            title: String,
            body: String,
        ): RepositoryResult<Unit> = RepositoryResult.Success(Unit)

        override suspend fun saveNote(
            id: String,
            title: String,
            body: String,
            tagNames: Collection<String>,
        ): RepositoryResult<Unit> = RepositoryResult.Success(Unit)

        override suspend fun setPinned(id: String, isPinned: Boolean): RepositoryResult<Unit> =
            RepositoryResult.Success(Unit)

        override suspend fun setFavorite(id: String, isFavorite: Boolean): RepositoryResult<Unit> =
            RepositoryResult.Success(Unit)

        override suspend fun setColor(id: String, color: VaultItemColor): RepositoryResult<Unit> =
            RepositoryResult.Success(Unit)

        override suspend fun setArchived(id: String, isArchived: Boolean): RepositoryResult<Unit> =
            RepositoryResult.Success(Unit).also { archiveChanges += id to isArchived }

        override suspend fun moveToTrash(id: String): RepositoryResult<Unit> =
            RepositoryResult.Success(Unit).also { trashedItems += id }

        override suspend fun restore(id: String): RepositoryResult<Unit> =
            RepositoryResult.Success(Unit).also { restoredItems += id }

        override suspend fun setTags(
            id: String,
            tagNames: Collection<String>,
        ): RepositoryResult<Unit> = RepositoryResult.Success(Unit)

        private fun window(limit: Int, offset: Int): Flow<List<VaultItemSummary>> =
            flowOf(items.drop(offset).take(limit))
    }

    private companion object {
        fun summary(index: Int): VaultItemSummary = VaultItemSummary(
            id = "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
            type = VaultItemType.NOTE,
            color = VaultItemColor.DEFAULT,
            title = "Note $index",
            bodyPreview = "",
            isPinned = false,
            isFavorite = false,
            isArchived = false,
            createdAtEpochMillis = index.toLong(),
            updatedAtEpochMillis = index.toLong(),
            syncStatus = ItemSyncStatus.PENDING,
            conflictOriginId = null,
            tags = emptyList(),
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VaultMainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
