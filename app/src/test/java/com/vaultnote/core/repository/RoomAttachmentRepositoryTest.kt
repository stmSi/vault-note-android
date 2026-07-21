package com.vaultnote.core.repository

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.Clock
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.IdGenerator
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.SyncOperationType
import com.vaultnote.core.common.model.SyncOperationState
import com.vaultnote.core.database.VaultDatabase
import com.vaultnote.core.database.entity.AttachmentFileCleanupEntity
import com.vaultnote.core.database.entity.SyncOperationEntity
import com.vaultnote.core.files.AttachmentFileManager
import com.vaultnote.core.files.AttachmentFormat
import com.vaultnote.core.files.AttachmentPreview
import com.vaultnote.core.files.AttachmentValidationLevel
import com.vaultnote.core.files.CleanupResult
import com.vaultnote.core.files.PreparedAttachment
import com.vaultnote.core.files.PlannedAttachmentPaths
import com.vaultnote.core.encryption.CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION
import com.vaultnote.core.encryption.EncryptedFilePurpose
import com.vaultnote.core.security.SecureAttachmentUriFactory
import com.vaultnote.core.sync.SyncScheduleResult
import com.vaultnote.core.sync.SyncScheduler
import com.vaultnote.core.search.RoomSearchRepository
import com.vaultnote.core.search.SearchQueryCompilation
import com.vaultnote.core.search.SearchQueryCompiler
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomAttachmentRepositoryTest {
    private lateinit var database: VaultDatabase
    private lateinit var files: FakeAttachmentFileManager
    private lateinit var scheduler: TestSyncScheduler
    private lateinit var repository: AttachmentRepository
    private lateinit var vaultRepository: VaultRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        scheduler = TestSyncScheduler()
        val ids = SequenceIdGenerator()
        files = FakeAttachmentFileManager(File(context.cacheDir, "attachment-repository-test"))
        files.clear()
        vaultRepository = RoomVaultRepository(
            database = database,
            syncScheduler = scheduler,
            dispatchers = TestDispatchers,
            clock = IncrementingClock(),
            idGenerator = ids,
        )
        repository = RoomAttachmentRepository(
            database = database,
            fileManager = files,
            syncScheduler = scheduler,
            dispatchers = TestDispatchers,
            clock = IncrementingClock(),
            idGenerator = ids,
            secureUris = SecureAttachmentUriFactory(context),
        )
    }

    @After
    fun tearDown() {
        database.close()
        files.clear()
    }

    @Test
    fun `validated import persists metadata revision search queues and openable file`() = runBlocking {
        val itemId = vaultRepository.createNote("Receipt", "Details").successValue()

        val result = repository.importFromUri(itemId, SOURCE_URI).successValue()

        assertFalse(result.wasDuplicate)
        val attachment = repository.observeForItem(itemId).first().single()
        assertEquals(result.attachment.id, attachment.id)
        assertEquals("paper.pdf", attachment.displayName)
        assertEquals("application/pdf", attachment.mimeType)
        assertEquals(9L, attachment.fileSizeBytes)
        assertEquals(2, attachment.pdfPageCount)
        assertNotNull(attachment.thumbnailUri)
        assertEquals(
            "content",
            repository.getOpenableAttachment(attachment.id).successValue().contentUri.scheme,
        )

        val parent = requireNotNull(vaultRepository.observeNote(itemId).first())
        assertEquals(2L, parent.localRevision)
        assertEquals(
            "paper.pdf",
            database.searchDao().getDocumentForItem(itemId)?.attachmentFilenames,
        )
        assertEquals(1, searchMatchCount("paper"))
        assertEquals("extension token", 1, searchMatchCount("pdf"))
        assertEquals("combined filename tokens", 1, searchMatchCount("paper pdf"))
        val filenameQuery = SearchQueryCompiler.compile("paper") as SearchQueryCompilation.Valid
        val filenameResults = RoomSearchRepository(database.searchDao(), TestDispatchers)
            .observe(filenameQuery.query, 10)
            .first()
        assertEquals(listOf(itemId), filenameResults.map { it.itemId })
        val incrementalFilenameQuery = SearchQueryCompiler.compile("p")
            as SearchQueryCompilation.Valid
        val incrementalFilenameResults = RoomSearchRepository(
            database.searchDao(),
            TestDispatchers,
        ).observe(incrementalFilenameQuery.query, 10).first()
        assertEquals(listOf(itemId), incrementalFilenameResults.map { it.itemId })
        val fullFilenameQuery = SearchQueryCompiler.compile("paper.pdf")
            as SearchQueryCompilation.Valid
        val fullFilenameResults = RoomSearchRepository(database.searchDao(), TestDispatchers)
            .observe(fullFilenameQuery.query, 10)
            .first()
        assertEquals(listOf(itemId), fullFilenameResults.map { it.itemId })
        val upload = requireNotNull(
            database.syncOperationDao().getByDedupeKey("attachment:${attachment.id}"),
        )
        val itemUpsert = requireNotNull(
            database.syncOperationDao().getByDedupeKey("item:$itemId"),
        )
        assertEquals(SyncOperationType.UPLOAD_ATTACHMENT, upload.operationType)
        assertEquals(2L, upload.targetRevision)
        assertEquals(SyncOperationType.UPSERT_ITEM, itemUpsert.operationType)
        assertEquals(2L, itemUpsert.targetRevision)
    }

    @Test
    fun `same parent and checksum deduplicate and remove redundant prepared files`() = runBlocking {
        val itemId = vaultRepository.createNote().successValue()
        val first = repository.importFromUri(itemId, SOURCE_URI).successValue()

        val duplicate = repository.importFromUri(itemId, SOURCE_URI).successValue()

        assertTrue(duplicate.wasDuplicate)
        assertEquals(first.attachment.id, duplicate.attachment.id)
        assertEquals(1, repository.observeForItem(itemId).first().size)
        assertEquals(2L, requireNotNull(vaultRepository.observeNote(itemId).first()).localRevision)
        assertEquals(1, files.removedStoredCount)
        assertFalse(files.lastRemovedStoredContentFile?.exists() == true)
        assertEquals(0, database.attachmentFileCleanupDao().count())
    }

    @Test
    fun `delete updates parent search and sync tombstone before best effort file cleanup`() = runBlocking {
        val itemId = vaultRepository.createNote().successValue()
        val attachmentId = repository.importFromUri(itemId, SOURCE_URI)
            .successValue()
            .attachment.id

        val deletion = repository.delete(attachmentId).successValue()

        assertFalse(deletion.cleanupPending)
        assertFalse(deletion.syncDelayed)
        assertTrue(repository.observeForItem(itemId).first().isEmpty())
        assertEquals("", database.searchDao().getDocumentForItem(itemId)?.attachmentFilenames)
        assertEquals(0, searchMatchCount("paper"))
        assertEquals(3L, requireNotNull(vaultRepository.observeNote(itemId).first()).localRevision)
        val delete = requireNotNull(
            database.syncOperationDao().getByDedupeKey("attachment:$attachmentId"),
        )
        assertEquals(SyncOperationType.DELETE_ATTACHMENT, delete.operationType)
        assertEquals(3L, delete.targetRevision)
        assertEquals(1, files.removedStoredCount)
        assertFalse(files.contentFile(attachmentId).exists())
        assertEquals(0, database.attachmentFileCleanupDao().count())
    }

    @Test
    fun `failed file import removes planned files and journal`() = runBlocking {
        val itemId = vaultRepository.createNote().successValue()
        files.importFailure = AppError.CorruptedFile

        val result = repository.importFromUri(itemId, SOURCE_URI)

        assertTrue(result is RepositoryResult.Failure)
        assertEquals(AppError.CorruptedFile, (result as RepositoryResult.Failure).error)
        assertEquals(1, files.removedStoredCount)
        assertFalse(files.lastImportedAttachmentId?.let(files::contentFile)?.exists() == true)
        assertEquals(0, database.attachmentFileCleanupDao().count())
    }

    @Test
    fun `database failure after prepared import checks commit then cleans files and journal`() =
        runBlocking {
            val itemId = vaultRepository.createNote().successValue()
            files.afterImport = {
                runBlocking {
                    database.syncOperationDao().insert(
                        SyncOperationEntity(
                            operationId = "id-4",
                            dedupeKey = "forced-operation-id-collision",
                            itemId = null,
                            attachmentId = null,
                            operationType = SyncOperationType.UPSERT_ITEM,
                            targetRevision = 0L,
                            state = SyncOperationState.PENDING,
                            attemptCount = 0,
                            nextAttemptAt = 0L,
                            leaseToken = null,
                            leaseExpiresAt = null,
                            createdAt = 0L,
                            updatedAt = 0L,
                            lastErrorCode = null,
                        ),
                    )
                }
            }

            val result = repository.importFromUri(itemId, SOURCE_URI)

            assertTrue(result is RepositoryResult.Failure)
            assertTrue((result as RepositoryResult.Failure).error is AppError.DatabaseFailure)
            assertEquals(1, files.removedStoredCount)
            assertFalse(files.lastImportedAttachmentId?.let(files::contentFile)?.exists() == true)
            assertEquals(0, database.attachmentFileCleanupDao().count())
            assertTrue(repository.observeForItem(itemId).first().isEmpty())
            assertEquals(1L, requireNotNull(vaultRepository.observeNote(itemId).first()).localRevision)
        }

    @Test
    fun `delete cleanup failure remains journaled and reconciliation retries it`() = runBlocking {
        val itemId = vaultRepository.createNote().successValue()
        val attachmentId = repository.importFromUri(itemId, SOURCE_URI)
            .successValue()
            .attachment.id
        files.removeStoredFailure = AppError.PermissionDenied

        scheduler.result = SyncScheduleResult.Rejected("test_sync_delay")
        val deletion = repository.delete(attachmentId)

        assertTrue(deletion is RepositoryResult.Success)
        val deletionSuccess = deletion as RepositoryResult.Success
        assertTrue(deletionSuccess.value.cleanupPending)
        assertTrue(deletionSuccess.value.syncDelayed)
        assertNull(deletionSuccess.warning)
        assertNotNull(database.attachmentFileCleanupDao().getByCleanupId("delete:$attachmentId"))
        assertTrue(files.contentFile(attachmentId).isFile)

        files.removeStoredFailure = null
        repository.reconcileFileCleanup().requireSuccess()

        assertFalse(files.contentFile(attachmentId).exists())
        assertNull(database.attachmentFileCleanupDao().getByCleanupId("delete:$attachmentId"))
        assertEquals(1, files.cleanupAbandonedCount)
    }

    @Test
    fun `reconciliation never deletes paths referenced by a live attachment`() = runBlocking {
        val itemId = vaultRepository.createNote().successValue()
        val attachmentId = repository.importFromUri(itemId, SOURCE_URI)
            .successValue()
            .attachment.id
        val live = requireNotNull(database.attachmentDao().getById(attachmentId))
        database.attachmentFileCleanupDao().upsert(
            AttachmentFileCleanupEntity(
                cleanupId = "test-live-reference",
                localRelativePath = live.localEncryptedPath,
                thumbnailRelativePath = live.thumbnailPath,
                createdAt = 1L,
                attemptCount = 0,
                lastAttemptAt = null,
            ),
        )
        val cleanupCallsBefore = files.removedStoredCount

        repository.reconcileFileCleanup().requireSuccess()

        assertTrue(files.contentFile(attachmentId).isFile)
        assertNotNull(database.attachmentDao().getById(attachmentId))
        assertNull(database.attachmentFileCleanupDao().getByCleanupId("test-live-reference"))
        assertEquals(cleanupCallsBefore, files.removedStoredCount)
    }

    @Test
    fun `mismatched prepared paths remain separately journaled when immediate cleanup fails`() =
        runBlocking {
            val itemId = vaultRepository.createNote().successValue()
            files.preparedLocalPathOverride = "attachments/unexpected.bin"
            files.preparedThumbnailPathOverride = "thumbnails/unexpected.webp"
            files.removeStoredFailuresRemaining = 1

            val result = repository.importFromUri(itemId, SOURCE_URI)

            assertTrue(result is RepositoryResult.Failure)
            assertEquals(AppError.PermissionDenied, (result as RepositoryResult.Failure).error)
            val remaining = database.attachmentFileCleanupDao().getOldest(10).single()
            assertEquals("unexpected-import:id-3", remaining.cleanupId)
            assertEquals("attachments/unexpected.bin", remaining.localRelativePath)
            assertTrue(files.resolveFile(remaining.localRelativePath).isFile)

            repository.reconcileFileCleanup().requireSuccess()

            assertEquals(0, database.attachmentFileCleanupDao().count())
            assertFalse(files.resolveFile(remaining.localRelativePath).exists())
            assertTrue(repository.observeForItem(itemId).first().isEmpty())
        }

    @Test
    fun `mismatched paths are reference safely cleaned when unexpected journal insert fails`() =
        runBlocking {
            val itemId = vaultRepository.createNote().successValue()
            files.preparedLocalPathOverride = "attachments/unexpected-insert-failure.bin"
            files.preparedThumbnailPathOverride = "thumbnails/unexpected-insert-failure.webp"
            database.openHelper.writableDatabase.execSQL(
                """
                CREATE TRIGGER fail_unexpected_cleanup_insert
                BEFORE INSERT ON attachment_file_cleanup_journal
                WHEN NEW.cleanup_id LIKE 'unexpected-import:%'
                BEGIN
                    SELECT RAISE(ABORT, 'forced unexpected journal failure');
                END
                """.trimIndent(),
            )

            val result = repository.importFromUri(itemId, SOURCE_URI)

            assertTrue(result is RepositoryResult.Failure)
            assertTrue((result as RepositoryResult.Failure).error is AppError.DatabaseFailure)
            assertEquals(0, database.attachmentFileCleanupDao().count())
            assertFalse(files.resolveFile("attachments/unexpected-insert-failure.bin").exists())
            assertFalse(files.resolveFile("thumbnails/unexpected-insert-failure.webp").exists())
            assertTrue(repository.observeForItem(itemId).first().isEmpty())
        }

    @Test
    fun `non content URI is rejected before any file access`() = runBlocking {
        val itemId = vaultRepository.createNote().successValue()

        val result = repository.importFromUri(itemId, Uri.parse("file:///data/local/private.pdf"))

        assertTrue(result is RepositoryResult.Failure)
        assertEquals(AppError.UnsupportedFile, (result as RepositoryResult.Failure).error)
        assertEquals(0, files.importCount)
    }

    @Test
    fun `legacy encryption migration verifies the expected checksum before updating metadata`() =
        runBlocking {
            val itemId = vaultRepository.createNote().successValue()
            val attachmentId = repository.importFromUri(itemId, SOURCE_URI)
                .successValue()
                .attachment.id
            database.openHelper.writableDatabase.execSQL(
                "UPDATE attachments SET encryption_format_version = 0 WHERE id = ?",
                arrayOf(attachmentId),
            )

            val migrated = repository.migrateLegacyAttachments(8).successValue()

            assertEquals(1, migrated)
            assertEquals(attachmentId, files.lastEncryptionUpgradeId)
            assertEquals(CHECKSUM, files.lastExpectedPlaintextSha256)
            assertEquals(
                CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION,
                requireNotNull(database.attachmentDao().getById(attachmentId)).encryptionFormatVersion,
            )
        }

    @Test
    fun `failed legacy encryption leaves metadata at the legacy version`() = runBlocking {
        val itemId = vaultRepository.createNote().successValue()
        val attachmentId = repository.importFromUri(itemId, SOURCE_URI)
            .successValue()
            .attachment.id
        database.openHelper.writableDatabase.execSQL(
            "UPDATE attachments SET encryption_format_version = 0 WHERE id = ?",
            arrayOf(attachmentId),
        )
        files.encryptionUpgradeFailure = AppError.CorruptedFile

        val result = repository.migrateLegacyAttachments(8)

        assertTrue(result is RepositoryResult.Failure)
        assertEquals(
            0,
            requireNotNull(database.attachmentDao().getById(attachmentId)).encryptionFormatVersion,
        )
    }

    private fun <T> RepositoryResult<T>.successValue(): T = when (this) {
        is RepositoryResult.Success -> value
        is RepositoryResult.Failure -> throw AssertionError(
            "Expected success, got ${error::class.simpleName}",
        )
    }

    private fun <T> RepositoryResult<T>.requireSuccess() {
        assertFalse(this is RepositoryResult.Failure)
    }

    private fun searchMatchCount(term: String): Int =
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM search_fts WHERE search_fts MATCH ?",
            arrayOf(term),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private object TestDispatchers : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    private class IncrementingClock : Clock {
        private var value = 1_000L

        override fun nowEpochMillis(): Long = value++
    }

    private class SequenceIdGenerator : IdGenerator {
        private val next = AtomicInteger(1)

        override fun newId(): String = "id-${next.getAndIncrement()}"
    }

    private class TestSyncScheduler : SyncScheduler {
        var result: SyncScheduleResult = SyncScheduleResult.Scheduled

        override fun requestSync(): SyncScheduleResult = result
    }

    private class FakeAttachmentFileManager(private val root: File) : AttachmentFileManager {
        var removedPreparedCount: Int = 0
            private set
        var removedStoredCount: Int = 0
            private set
        var importCount: Int = 0
            private set
        var cleanupAbandonedCount: Int = 0
            private set
        var lastRedundantContentFile: File? = null
            private set
        var lastRemovedStoredContentFile: File? = null
            private set
        var lastImportedAttachmentId: String? = null
            private set
        var lastEncryptionUpgradeId: String? = null
            private set
        var lastExpectedPlaintextSha256: String? = null
            private set
        var afterImport: (() -> Unit)? = null
        var importFailure: AppError? = null
        var removeStoredFailure: AppError? = null
        var removeStoredFailuresRemaining: Int = 0
        var preparedLocalPathOverride: String? = null
        var preparedThumbnailPathOverride: String? = null
        var encryptionUpgradeFailure: AppError? = null

        override fun planAttachmentPaths(
            attachmentId: String,
        ): RepositoryResult<PlannedAttachmentPaths> = RepositoryResult.Success(
            PlannedAttachmentPaths(
                localRelativePath = "attachments/$attachmentId.bin",
                thumbnailRelativePath = "thumbnails/$attachmentId.webp",
            ),
        )

        override suspend fun inspect(uri: Uri): RepositoryResult<AttachmentPreview> =
            RepositoryResult.Success(
                AttachmentPreview(
                    originalFilename = "paper.pdf",
                    mimeType = "application/pdf",
                    declaredSize = 9L,
                    format = AttachmentFormat.PDF,
                    validationLevel = AttachmentValidationLevel.FULL,
                    imageWidth = null,
                    imageHeight = null,
                    pdfPageCount = 2,
                ),
            )

        override suspend fun importAttachment(
            uri: Uri,
            attachmentId: String,
        ): RepositoryResult<PreparedAttachment> {
            importCount += 1
            lastImportedAttachmentId = attachmentId
            val localPath = preparedLocalPathOverride ?: "attachments/$attachmentId.bin"
            val thumbnailPath = preparedThumbnailPathOverride ?: "thumbnails/$attachmentId.webp"
            val content = resolve(localPath)
            val thumbnail = resolve(thumbnailPath)
            content.parentFile?.mkdirs()
            thumbnail.parentFile?.mkdirs()
            content.writeBytes("PDF bytes".encodeToByteArray())
            thumbnail.writeBytes("thumbnail".encodeToByteArray())
            afterImport?.invoke()
            importFailure?.let { return RepositoryResult.Failure(it) }
            return RepositoryResult.Success(
                PreparedAttachment(
                    attachmentId = attachmentId,
                    originalFilename = "paper.pdf",
                    mimeType = "application/pdf",
                    fileSize = 9L,
                    sha256Checksum = CHECKSUM,
                    localRelativePath = localPath,
                    thumbnailRelativePath = thumbnailPath,
                    encryptionFormatVersion = CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION,
                    format = AttachmentFormat.PDF,
                    imageWidth = null,
                    imageHeight = null,
                    pdfPageCount = 2,
                ),
            )
        }

        override suspend fun removePrepared(
            prepared: PreparedAttachment,
        ): RepositoryResult<Unit> {
            removedPreparedCount += 1
            lastRedundantContentFile = resolve(prepared.localRelativePath)
            resolve(prepared.localRelativePath).delete()
            prepared.thumbnailRelativePath?.let(::resolve)?.delete()
            return RepositoryResult.Success(Unit)
        }

        override suspend fun removeStored(
            localRelativePath: String,
            thumbnailRelativePath: String?,
        ): RepositoryResult<Unit> {
            removedStoredCount += 1
            lastRemovedStoredContentFile = resolve(localRelativePath)
            if (removeStoredFailuresRemaining > 0) {
                removeStoredFailuresRemaining -= 1
                return RepositoryResult.Failure(AppError.PermissionDenied)
            }
            removeStoredFailure?.let { return RepositoryResult.Failure(it) }
            resolve(localRelativePath).delete()
            thumbnailRelativePath?.let(::resolve)?.delete()
            return RepositoryResult.Success(Unit)
        }

        override suspend fun upgradeStoredEncryption(
            attachmentId: String,
            localRelativePath: String,
            thumbnailRelativePath: String?,
            storedFormatVersion: Int,
            expectedPlaintextSha256: String,
        ): RepositoryResult<Int> {
            lastEncryptionUpgradeId = attachmentId
            lastExpectedPlaintextSha256 = expectedPlaintextSha256
            encryptionUpgradeFailure?.let { return RepositoryResult.Failure(it) }
            return RepositoryResult.Success(CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION)
        }

        override suspend fun decryptStored(
            attachmentId: String,
            purpose: EncryptedFilePurpose,
            relativePath: String,
            output: OutputStream,
        ): RepositoryResult<Unit> {
            resolve(relativePath).inputStream().use { it.copyTo(output) }
            return RepositoryResult.Success(Unit)
        }

        override fun resolveAttachmentPath(relativePath: String): RepositoryResult<File> =
            RepositoryResult.Success(resolve(relativePath))

        override fun resolveThumbnailPath(relativePath: String): RepositoryResult<File> =
            RepositoryResult.Success(resolve(relativePath))

        override suspend fun cleanupAbandonedFiles(): RepositoryResult<CleanupResult> {
            cleanupAbandonedCount += 1
            return RepositoryResult.Success(CleanupResult(deletedFiles = 0, failedDeletions = 0))
        }

        fun contentFile(attachmentId: String): File =
            File(root, "attachments/$attachmentId.bin")

        fun resolveFile(relativePath: String): File = resolve(relativePath)

        private fun thumbnailFile(attachmentId: String): File =
            File(root, "thumbnails/$attachmentId.webp")

        private fun resolve(relativePath: String): File = File(root, relativePath)

        fun clear() {
            root.deleteRecursively()
            removedPreparedCount = 0
            removedStoredCount = 0
            importCount = 0
            cleanupAbandonedCount = 0
            lastRedundantContentFile = null
            lastRemovedStoredContentFile = null
            lastImportedAttachmentId = null
            lastEncryptionUpgradeId = null
            lastExpectedPlaintextSha256 = null
            afterImport = null
            importFailure = null
            removeStoredFailure = null
            removeStoredFailuresRemaining = 0
            preparedLocalPathOverride = null
            preparedThumbnailPathOverride = null
            encryptionUpgradeFailure = null
        }
    }

    private companion object {
        val SOURCE_URI: Uri = Uri.parse("content://vaultnote.test/paper.pdf")
        const val CHECKSUM: String =
            "52c91cca7e140d64f84ea3287d62d68f12bf6b493e9443885c10bd6dcf7f53a1"
    }
}
