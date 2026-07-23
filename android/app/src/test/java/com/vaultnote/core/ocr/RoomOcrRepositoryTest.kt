package com.vaultnote.core.ocr

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vaultnote.core.common.Clock
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.AttachmentUploadStatus
import com.vaultnote.core.common.model.OcrState
import com.vaultnote.core.database.VaultDatabase
import com.vaultnote.core.database.entity.AttachmentEntity
import com.vaultnote.core.encryption.CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION
import com.vaultnote.core.repository.RoomVaultRepository
import com.vaultnote.core.search.RoomSearchRepository
import com.vaultnote.core.search.SearchQueryCompilation
import com.vaultnote.core.search.SearchQueryCompiler
import com.vaultnote.core.sync.CoalescingFakeSyncScheduler
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomOcrRepositoryTest {
    private lateinit var database: VaultDatabase
    private lateinit var plaintext: File
    private lateinit var processor: RecordingProcessor
    private lateinit var repository: OcrRepository
    private var now = 20_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        plaintext = File(context.cacheDir, "ocr-repository-test.tmp").apply { writeText("fixture") }
        processor = RecordingProcessor(OcrProcessResult.Success("Invoice total 42"))
        repository = RoomOcrRepository(
            database,
            OcrPlaintextStore { _, _ ->
                plaintext.writeText("fixture")
                RepositoryResult.Success(OcrPlaintextLease(plaintext))
            },
            processor,
            TestDispatchers,
            Clock { now++ },
        )
    }

    @After
    fun tearDown() {
        database.close()
        plaintext.delete()
    }

    @Test
    fun `successful OCR is persisted indexed and unchanged files are not reprocessed`() = runBlocking {
        val itemId = createItem()
        insertPendingAttachment(itemId)

        assertEquals(1, repository.processPending(2).successValue().processedCount)
        val attachment = requireNotNull(database.attachmentDao().getById(ATTACHMENT_ID))
        assertEquals(OcrState.COMPLETE, attachment.ocrState)
        assertEquals(CHECKSUM, attachment.ocrSourceChecksum)
        assertEquals("Invoice total 42", database.vaultItemDao().getById(itemId)?.ocrText)

        val compiled = SearchQueryCompiler.compile("invoice") as SearchQueryCompilation.Valid
        val matches = RoomSearchRepository(database.searchDao(), TestDispatchers)
            .observe(compiled.query, 10)
            .first()
        assertEquals(listOf(itemId), matches.map { it.itemId })

        assertEquals(0, repository.processPending(2).successValue().processedCount)
        assertEquals(1, processor.invocations)
    }

    @Test
    fun `retryable engine failure is persisted and may be explicitly retried`() = runBlocking {
        val itemId = createItem()
        insertPendingAttachment(itemId)
        processor.result = OcrProcessResult.Failure(OcrFailureCode.ENGINE_UNAVAILABLE)

        repository.processPending(1).successValue()

        val failed = requireNotNull(database.attachmentDao().getById(ATTACHMENT_ID))
        assertEquals(OcrState.FAILED, failed.ocrState)
        assertEquals(OcrFailureCode.ENGINE_UNAVAILABLE.name, failed.ocrFailureCode)
        assertTrue(repository.isRetryable(failed.ocrFailureCode))
        assertTrue(repository.retry(ATTACHMENT_ID).successValue())
        assertEquals(OcrState.PENDING, database.attachmentDao().getById(ATTACHMENT_ID)?.ocrState)
        assertFalse(repository.retry(ATTACHMENT_ID).successValue())
    }

    private suspend fun createItem(): String {
        val vault = RoomVaultRepository(
            database,
            CoalescingFakeSyncScheduler(),
            TestDispatchers,
            Clock { now++ },
            com.vaultnote.core.common.IdGenerator { "item-1" },
        )
        return vault.createNote("Receipt", "Stored locally").successValue()
    }

    private suspend fun insertPendingAttachment(itemId: String) {
        database.attachmentDao().insert(
            AttachmentEntity(
                id = ATTACHMENT_ID,
                parentItemId = itemId,
                originalFilename = "scan.png",
                mimeType = "image/png",
                fileSize = 7L,
                imageWidth = 10,
                imageHeight = 10,
                pdfPageCount = null,
                sha256Checksum = CHECKSUM,
                localEncryptedPath = "attachments/test.bin",
                remotePath = null,
                thumbnailPath = null,
                encryptionFormatVersion = CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION,
                uploadStatus = AttachmentUploadStatus.PENDING,
                createdAt = now++,
                ocrState = OcrState.PENDING,
                extractedOcrText = "",
                ocrSourceChecksum = null,
                ocrFailureCode = null,
                ocrUpdatedAt = null,
            ),
        )
    }

    private class RecordingProcessor(var result: OcrProcessResult) : OcrProcessor {
        var invocations = 0
        override suspend fun recognize(input: OcrInput): OcrProcessResult {
            invocations += 1
            return result
        }
    }

    private object TestDispatchers : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private fun <T> RepositoryResult<T>.successValue(): T =
        (this as RepositoryResult.Success<T>).value

    private companion object {
        const val ATTACHMENT_ID = "attachment-1"
        val CHECKSUM = "a".repeat(64)
    }
}
