package com.vaultnote.feature.editor

import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.ItemSyncStatus
import com.vaultnote.core.common.model.VaultItemSummary
import com.vaultnote.core.common.model.VaultNote
import com.vaultnote.core.common.model.VaultItemColor
import com.vaultnote.core.common.model.VaultItemType
import com.vaultnote.core.common.model.VaultTag
import com.vaultnote.core.repository.VaultRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditorViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `closing disables draft mutation and flushes the accepted snapshot`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = EditorFakeRepository()
            val saveResult = CompletableDeferred<RepositoryResult<Unit>>()
            repository.nextSaveResult = saveResult
            val viewModel = NoteEditorViewModel(ITEM_ID, repository)
            runCurrent()

            viewModel.onBodyChanged("accepted")
            val event = async { viewModel.events.first() }
            viewModel.requestClose()
            repository.saveStarted.await()

            val closing = viewModel.uiState.value as EditorUiState.Content
            assertTrue(closing.isClosing)
            viewModel.onBodyChanged("must not be accepted")
            assertEquals("accepted", (viewModel.uiState.value as EditorUiState.Content).draft.body)

            saveResult.complete(RepositoryResult.Success(Unit))
            runCurrent()

            assertEquals("accepted", repository.savedBodies.single())
            assertEquals(EditorEvent.NavigateBack, event.await())
        }

    @Test
    fun `metadata failure rolls back only its field while retaining typed content`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = EditorFakeRepository()
            val pinResult = CompletableDeferred<RepositoryResult<Unit>>()
            repository.nextPinResult = pinResult
            val viewModel = NoteEditorViewModel(ITEM_ID, repository)
            runCurrent()

            viewModel.setPinned(true)
            viewModel.onBodyChanged("typing continues")
            pinResult.complete(
                RepositoryResult.Failure(AppError.DatabaseFailure("set_pinned")),
            )
            runCurrent()

            val content = viewModel.uiState.value as EditorUiState.Content
            assertFalse(content.draft.isPinned)
            assertEquals("typing continues", content.draft.body)
            assertFalse(content.isMetadataSaving)
        }

    @Test
    fun `immediate back waits for an in-flight metadata write`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = EditorFakeRepository()
            val pinResult = CompletableDeferred<RepositoryResult<Unit>>()
            repository.nextPinResult = pinResult
            val viewModel = NoteEditorViewModel(ITEM_ID, repository)
            runCurrent()
            val event = async { viewModel.events.first() }

            viewModel.setPinned(true)
            runCurrent()
            viewModel.requestClose()
            runCurrent()

            assertFalse(event.isCompleted)
            assertTrue((viewModel.uiState.value as EditorUiState.Content).isClosing)

            pinResult.complete(RepositoryResult.Success(Unit))
            runCurrent()

            assertEquals(EditorEvent.NavigateBack, event.await())
            assertTrue(repository.currentNote().isPinned)
        }

    @Test
    fun `permanent save failure does not offer an ineffective retry`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = EditorFakeRepository()
            repository.nextSaveResult = CompletableDeferred<RepositoryResult<Unit>>().apply {
                complete(
                    RepositoryResult.Failure(
                        AppError.InvalidInput("tags", "Tag is too long"),
                    ),
                )
            }
            val viewModel = NoteEditorViewModel(ITEM_ID, repository)
            runCurrent()

            viewModel.onTagsChanged("x".repeat(65))
            viewModel.requestClose()
            runCurrent()

            val content = viewModel.uiState.value as EditorUiState.Content
            assertEquals(EditorSaveStatus.FAILED, content.saveStatus)
            assertFalse(content.saveRetryable)
            assertFalse(content.isClosing)
        }

    private class EditorFakeRepository : VaultRepository {
        private val note = MutableStateFlow(baseNote())
        val saveStarted = CompletableDeferred<Unit>()
        val savedBodies = mutableListOf<String>()
        var nextSaveResult: CompletableDeferred<RepositoryResult<Unit>>? = null
        var nextPinResult: CompletableDeferred<RepositoryResult<Unit>>? = null

        fun currentNote(): VaultNote = note.value

        override fun observeActiveItems(limit: Int, offset: Int): Flow<List<VaultItemSummary>> =
            emptyFlow()

        override fun observeArchivedItems(limit: Int, offset: Int): Flow<List<VaultItemSummary>> =
            emptyFlow()

        override fun observeTrashItems(limit: Int, offset: Int): Flow<List<VaultItemSummary>> =
            emptyFlow()

        override fun observeNote(id: String): Flow<VaultNote?> = note

        override fun observeTags(): Flow<List<VaultTag>> = emptyFlow()

        override suspend fun createNote(title: String, body: String): RepositoryResult<String> =
            RepositoryResult.Success(ITEM_ID)

        override suspend fun createAttachmentContainer(
            title: String,
            type: VaultItemType,
        ): RepositoryResult<String> = RepositoryResult.Success(ITEM_ID)

        override suspend fun saveNote(
            id: String,
            title: String,
            body: String,
        ): RepositoryResult<Unit> = saveNote(id, title, body, emptyList())

        override suspend fun saveNote(
            id: String,
            title: String,
            body: String,
            tagNames: Collection<String>,
        ): RepositoryResult<Unit> {
            savedBodies += body
            saveStarted.complete(Unit)
            val result = nextSaveResult?.await() ?: RepositoryResult.Success(Unit)
            if (result is RepositoryResult.Success) {
                note.value = note.value.copy(
                    title = title,
                    body = body,
                    tags = tagNames.mapIndexed { index, name -> VaultTag("tag-$index", name) },
                )
            }
            return result
        }

        override suspend fun setPinned(id: String, isPinned: Boolean): RepositoryResult<Unit> {
            val result = nextPinResult?.await() ?: RepositoryResult.Success(Unit)
            if (result is RepositoryResult.Success) {
                note.value = note.value.copy(isPinned = isPinned)
            }
            return result
        }

        override suspend fun setFavorite(id: String, isFavorite: Boolean): RepositoryResult<Unit> =
            RepositoryResult.Success(Unit)

        override suspend fun setColor(id: String, color: VaultItemColor): RepositoryResult<Unit> =
            RepositoryResult.Success(Unit)

        override suspend fun setArchived(id: String, isArchived: Boolean): RepositoryResult<Unit> =
            RepositoryResult.Success(Unit)

        override suspend fun moveToTrash(id: String): RepositoryResult<Unit> =
            RepositoryResult.Success(Unit)

        override suspend fun restore(id: String): RepositoryResult<Unit> =
            RepositoryResult.Success(Unit)

        override suspend fun setTags(
            id: String,
            tagNames: Collection<String>,
        ): RepositoryResult<Unit> = RepositoryResult.Success(Unit)
    }

    private companion object {
        const val ITEM_ID = "00000000-0000-0000-0000-000000000001"

        fun baseNote(): VaultNote = VaultNote(
            id = ITEM_ID,
            title = "Title",
            body = "Body",
            color = VaultItemColor.DEFAULT,
            ocrText = "",
            isPinned = false,
            isFavorite = false,
            isArchived = false,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            localRevision = 1L,
            remoteRevision = null,
            lastSyncedRevision = null,
            serverVersionToken = null,
            syncStatus = ItemSyncStatus.PENDING,
            deletedAtEpochMillis = null,
            conflictOriginId = null,
            tags = emptyList(),
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
