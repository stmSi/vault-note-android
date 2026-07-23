package com.vaultnote.feature.files

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.AttachmentDeleteResult
import com.vaultnote.core.common.model.AttachmentImportResult
import com.vaultnote.core.common.model.OpenableAttachment
import com.vaultnote.core.common.model.VaultAttachment
import com.vaultnote.core.repository.AttachmentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModelTest {
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `rapid filename input is debounced and uses files-only repository query`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = RecordingAttachmentRepository()
        val viewModel = FilesViewModel(repository, SavedStateHandle())
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.collect { }
        }
        advanceUntilIdle()
        assertEquals(1, repository.activeQueries)

        viewModel.updateSearchText("b")
        viewModel.updateSearchText("bk")
        viewModel.updateSearchText("bkk")
        advanceTimeBy(149L)
        runCurrent()
        assertTrue(repository.filenameQueries.isEmpty())

        advanceTimeBy(1L)
        runCurrent()

        assertEquals(listOf("bkk"), repository.filenameQueries)
        assertEquals("bkk", viewModel.state.value.searchText)
        collection.cancel()
    }

    private class RecordingAttachmentRepository : AttachmentRepository {
        private val files = MutableStateFlow(emptyList<VaultAttachment>())
        var activeQueries: Int = 0
        val filenameQueries = mutableListOf<String>()

        override fun observeActiveFiles(limit: Int, offset: Int): Flow<List<VaultAttachment>> {
            activeQueries += 1
            return files
        }

        override fun observeActiveFilesMatchingName(
            searchText: String,
            limit: Int,
            offset: Int,
        ): Flow<List<VaultAttachment>> {
            filenameQueries += searchText
            return files
        }

        override fun observeForItem(itemId: String): Flow<List<VaultAttachment>> = files

        override fun observeById(attachmentId: String): Flow<VaultAttachment?> =
            MutableStateFlow(null)

        override suspend fun importFromUri(
            parentItemId: String,
            sourceUri: Uri,
            displayName: String?,
        ): RepositoryResult<AttachmentImportResult> = error("Not used")

        override suspend fun getById(
            attachmentId: String,
        ): RepositoryResult<VaultAttachment> = error("Not used")

        override suspend fun getOpenableAttachment(
            attachmentId: String,
        ): RepositoryResult<OpenableAttachment> = error("Not used")

        override suspend fun delete(
            attachmentId: String,
        ): RepositoryResult<AttachmentDeleteResult> = error("Not used")

        override suspend fun rename(
            attachmentId: String,
            displayName: String,
        ): RepositoryResult<VaultAttachment> = error("Not used")

        override suspend fun reconcileFileCleanup(): RepositoryResult<Unit> =
            RepositoryResult.Success(Unit)

        override suspend fun migrateLegacyAttachments(limit: Int): RepositoryResult<Int> =
            RepositoryResult.Success(0)
    }
}
