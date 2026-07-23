package com.vaultnote.feature.viewer

import android.net.Uri
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.AttachmentDeleteResult
import com.vaultnote.core.common.model.AttachmentImportResult
import com.vaultnote.core.common.model.AttachmentUploadStatus
import com.vaultnote.core.common.model.OcrState
import com.vaultnote.core.common.model.OpenableAttachment
import com.vaultnote.core.common.model.VaultAttachment
import com.vaultnote.core.ocr.OcrBatchResult
import com.vaultnote.core.ocr.OcrRepository
import com.vaultnote.core.repository.AttachmentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AttachmentViewerViewModelTest {
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `swiping changes the selected secure attachment used by rename and export`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val first = attachment("first", "first.pdf")
        val second = attachment("second", "second.pdf")
        val repository = FakeAttachmentRepository(listOf(first, second))
        val exporter = FakeAttachmentExporter()
        val viewModel = AttachmentViewerViewModel(
            initialAttachmentId = first.id,
            repository = repository,
            ocrRepository = FakeOcrRepository,
            exporter = exporter,
        )
        advanceUntilIdle()

        val initial = viewModel.state.value as AttachmentViewerState.Content
        assertEquals(listOf(first.id, second.id), initial.attachments.map(VaultAttachment::id))
        assertEquals(first.id, initial.openableAttachment?.attachment?.id)

        viewModel.selectAttachment(second.id)
        advanceUntilIdle()

        val selected = viewModel.state.value as AttachmentViewerState.Content
        assertEquals(second.id, selected.selectedAttachmentId)
        assertEquals(second.id, selected.openableAttachment?.attachment?.id)

        viewModel.rename("travel.pdf")
        advanceUntilIdle()

        assertEquals(second.id, repository.lastRenamedAttachmentId)
        assertEquals("travel.pdf", repository.lastRequestedName)
        assertEquals("travel.pdf", viewModel.state.value.content().selectedAttachment?.displayName)
        assertNotNull(viewModel.prepareSave())
        assertEquals(second.id, exporter.lastPreparedAttachmentId)
        assertTrue(viewModel.state.value.content().isSaving)

        viewModel.completeSave(null)
        assertFalse(viewModel.state.value.content().isSaving)
        assertEquals(second.id, exporter.lastCancelledAttachmentId)
    }

    private fun AttachmentViewerState.content(): AttachmentViewerState.Content =
        this as AttachmentViewerState.Content

    private fun attachment(id: String, displayName: String): VaultAttachment = VaultAttachment(
        id = id,
        parentItemId = "parent",
        displayName = displayName,
        mimeType = "application/pdf",
        fileSizeBytes = 100L,
        imageWidth = null,
        imageHeight = null,
        pdfPageCount = 1,
        sha256Checksum = id.repeat(64).take(64),
        remotePath = null,
        thumbnailUri = null,
        encryptionFormatVersion = 2,
        uploadStatus = AttachmentUploadStatus.PENDING,
        createdAtEpochMillis = 1L,
        ocrState = OcrState.NOT_APPLICABLE,
        ocrFailureCode = null,
    )

    private class FakeAttachmentRepository(initial: List<VaultAttachment>) : AttachmentRepository {
        private val attachments = MutableStateFlow(initial)
        var lastRenamedAttachmentId: String? = null
        var lastRequestedName: String? = null

        override fun observeActiveFiles(limit: Int, offset: Int): Flow<List<VaultAttachment>> =
            attachments

        override fun observeActiveFilesMatchingName(
            searchText: String,
            limit: Int,
            offset: Int,
        ): Flow<List<VaultAttachment>> = attachments

        override fun observeForItem(itemId: String): Flow<List<VaultAttachment>> = attachments

        override fun observeById(attachmentId: String): Flow<VaultAttachment?> =
            attachments.map { values -> values.firstOrNull { it.id == attachmentId } }

        override suspend fun importFromUri(
            parentItemId: String,
            sourceUri: Uri,
            displayName: String?,
        ): RepositoryResult<AttachmentImportResult> = error("Not used")

        override suspend fun getById(
            attachmentId: String,
        ): RepositoryResult<VaultAttachment> = RepositoryResult.Success(requireAttachment(attachmentId))

        override suspend fun getOpenableAttachment(
            attachmentId: String,
        ): RepositoryResult<OpenableAttachment> = RepositoryResult.Success(
            OpenableAttachment(
                attachment = requireAttachment(attachmentId),
                contentUri = Uri.parse("content://vaultnote.test/$attachmentId"),
            ),
        )

        override suspend fun delete(
            attachmentId: String,
        ): RepositoryResult<AttachmentDeleteResult> = error("Not used")

        override suspend fun rename(
            attachmentId: String,
            displayName: String,
        ): RepositoryResult<VaultAttachment> {
            lastRenamedAttachmentId = attachmentId
            lastRequestedName = displayName
            val renamed = requireAttachment(attachmentId).copy(displayName = displayName)
            attachments.value = attachments.value.map { current ->
                if (current.id == attachmentId) renamed else current
            }
            return RepositoryResult.Success(renamed)
        }

        override suspend fun reconcileFileCleanup(): RepositoryResult<Unit> =
            RepositoryResult.Success(Unit)

        override suspend fun migrateLegacyAttachments(limit: Int): RepositoryResult<Int> =
            RepositoryResult.Success(0)

        private fun requireAttachment(attachmentId: String): VaultAttachment =
            requireNotNull(attachments.value.firstOrNull { it.id == attachmentId })
    }

    private object FakeOcrRepository : OcrRepository {
        override suspend fun processPending(limit: Int): RepositoryResult<OcrBatchResult> =
            RepositoryResult.Success(OcrBatchResult(0, false))

        override suspend fun processAttachment(attachmentId: String): RepositoryResult<Boolean> =
            RepositoryResult.Success(false)

        override suspend fun retry(attachmentId: String): RepositoryResult<Boolean> =
            RepositoryResult.Success(false)

        override fun isRetryable(failureCode: String?): Boolean = false
    }

    private class FakeAttachmentExporter : AttachmentExporter {
        var lastPreparedAttachmentId: String? = null
        var lastCancelledAttachmentId: String? = null

        override fun prepare(
            attachmentId: String,
        ): RepositoryResult<PreparedAttachmentExport> {
            lastPreparedAttachmentId = attachmentId
            return RepositoryResult.Success(PreparedAttachmentExport(attachmentId, "test-token"))
        }

        override suspend fun save(
            prepared: PreparedAttachmentExport,
            destination: Uri,
        ): RepositoryResult<Unit> = RepositoryResult.Success(Unit)

        override fun cancel(prepared: PreparedAttachmentExport) {
            lastCancelledAttachmentId = prepared.attachmentId
        }
    }
}
