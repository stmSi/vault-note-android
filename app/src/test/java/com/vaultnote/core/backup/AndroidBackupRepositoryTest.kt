package com.vaultnote.core.backup

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vaultnote.core.common.Clock
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.IdGenerator
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.AttachmentUploadStatus
import com.vaultnote.core.common.model.ItemSyncStatus
import com.vaultnote.core.common.model.OcrState
import com.vaultnote.core.common.model.VaultItemColor
import com.vaultnote.core.common.model.VaultItemType
import com.vaultnote.core.database.VaultDatabase
import com.vaultnote.core.database.entity.AttachmentEntity
import com.vaultnote.core.database.entity.VaultItemEntity
import com.vaultnote.core.encryption.CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION
import com.vaultnote.core.files.RestoredAttachmentStorage
import com.vaultnote.core.files.StagedRestoredAttachment
import com.vaultnote.core.security.LockPolicy
import com.vaultnote.core.security.VaultLockManager
import com.vaultnote.core.sync.SyncScheduleResult
import com.vaultnote.core.sync.SyncScheduler
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidBackupRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: VaultDatabase
    private lateinit var provider: ArchiveProvider
    private lateinit var resolver: ContentResolver
    private lateinit var restoredStorage: RecordingAttachmentStorage
    private lateinit var repository: BackupRepository
    private val syncRequests = AtomicInteger()
    private var failSyncScheduling = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        provider = ArchiveProvider()
        provider.attachInfo(
            context,
            ProviderInfo().apply { authority = ARCHIVE_AUTHORITY },
        )
        resolver = ContentResolver.wrap(provider)
        restoredStorage = RecordingAttachmentStorage(context)
        val lockManager = VaultLockManager().apply { applyPolicy(LockPolicy.DEFAULT) }
        val sequence = AtomicInteger()
        repository = AndroidBackupRepository(
            context = context,
            database = database,
            attachmentReader = BackupAttachmentReader { attachmentId, relativePath, output ->
                check(attachmentId == ORIGINAL_ATTACHMENT_ID)
                check(relativePath == ORIGINAL_LOCAL_PATH)
                output.write(ATTACHMENT_PAYLOAD)
                RepositoryResult.Success(Unit)
            },
            restoredAttachmentStore = restoredStorage,
            lockManager = lockManager,
            syncScheduler = SyncScheduler {
                if (failSyncScheduling) error("scheduler unavailable")
                syncRequests.incrementAndGet()
                SyncScheduleResult.Scheduled
            },
            dispatchers = TestDispatchers,
            clock = Clock { FIXED_TIME },
            idGenerator = IdGenerator { "generated_${sequence.incrementAndGet()}" },
            resolver = resolver,
            availableBytes = { Long.MAX_VALUE },
        )
    }

    @After
    fun tearDown() {
        database.close()
        provider.archive.delete()
        restoredStorage.directory.deleteRecursively()
    }

    @Test
    fun `export then restore stages first and preserves colliding content as a copy`() = runBlocking {
        database.vaultItemDao().insert(originalItem())
        database.attachmentDao().insert(originalAttachment())
        val export = repository.prepareExport(PASSWORD.copyOf()).successValue()

        val exported = repository.export(export, ARCHIVE_URI).successValue()

        assertEquals(1L, exported.itemCount)
        assertEquals(1L, exported.attachmentCount)
        assertTrue(provider.archive.length() > 0L)
        assertEquals(1L, database.backupDao().countItems())
        Shadows.shadowOf(resolver).registerInputStream(
            ARCHIVE_URI,
            FileInputStream(provider.archive),
        )

        val prepared = repository.prepareRestore(ARCHIVE_URI, PASSWORD.copyOf()).successValue()

        assertEquals(1L, prepared.backupSummary.itemCount)
        assertEquals(1L, prepared.backupSummary.attachmentCount)
        assertEquals(1L, prepared.copiedItemCount)
        assertEquals(1L, database.backupDao().countItems())

        failSyncScheduling = true
        val restoredResult = repository.commitRestore(prepared)
        val restored = restoredResult.successValue()
        val items = database.backupDao().getItemsPage("", 10)
        val attachments = database.backupDao().getAttachmentsPage("", 10)

        assertEquals(1L, restored.restoredItemCount)
        assertEquals(1L, restored.restoredAttachmentCount)
        assertEquals(1L, restored.copiedItemCount)
        assertEquals(2, items.size)
        val copy = items.single { it.id != ORIGINAL_ID }
        assertNotEquals(ORIGINAL_ID, copy.id)
        assertEquals("Encrypted backup note", copy.title)
        assertEquals("private body", copy.body)
        assertEquals(VaultItemColor.BLUE, copy.color)
        val copiedAttachment = attachments.single { it.id != ORIGINAL_ATTACHMENT_ID }
        assertEquals(copy.id, copiedAttachment.parentItemId)
        assertEquals(ORIGINAL_FILENAME, copiedAttachment.originalFilename)
        assertEquals(2, attachments.size)
        assertTrue(restoredStorage.restoredPayloads.single().contentEquals(ATTACHMENT_PAYLOAD))
        assertEquals(
            ORIGINAL_FILENAME,
            database.searchDao().getDocumentForItem(copy.id)?.attachmentFilenames,
        )
        assertTrue(database.syncOperationDao().getByDedupeKey("item:${copy.id}") != null)
        assertTrue(
            database.syncOperationDao()
                .getByDedupeKey("attachment:${copiedAttachment.id}") != null,
        )
        assertTrue((restoredResult as RepositoryResult.Success).warning != null)
        assertEquals(0, syncRequests.get())
    }

    @Test
    fun `plaintext export is explicit and restores without a password`() = runBlocking {
        database.vaultItemDao().insert(originalItem())
        database.attachmentDao().insert(originalAttachment())
        val export = repository.prepareExport(
            CharArray(0),
            BackupProtection.PLAINTEXT,
        ).successValue()

        val exported = repository.export(export, ARCHIVE_URI).successValue()

        assertEquals(BackupProtection.PLAINTEXT, exported.protection)
        ZipFile(provider.archive).use { zip ->
            val names = zip.entries().asSequence().map(ZipEntry::getName).toSet()
            assertTrue(BackupFormat.PLAINTEXT_DATABASE_PATH in names)
            assertTrue(BackupFormat.PLAINTEXT_CHECKSUMS_PATH in names)
            assertTrue(BackupFormat.DATABASE_PATH !in names)
            val databaseText = zip.getInputStream(
                zip.getEntry(BackupFormat.PLAINTEXT_DATABASE_PATH),
            ).bufferedReader().use { it.readText() }
            assertTrue(databaseText.contains("Encrypted backup note"))
        }
        Shadows.shadowOf(resolver).registerInputStream(
            ARCHIVE_URI,
            FileInputStream(provider.archive),
        )

        val prepared = repository.prepareRestore(ARCHIVE_URI, CharArray(0)).successValue()

        assertEquals(BackupProtection.PLAINTEXT, prepared.backupSummary.protection)
        assertEquals(1L, prepared.backupSummary.itemCount)
        assertEquals(1L, prepared.backupSummary.attachmentCount)
        repository.cancelRestore(prepared)
    }

    @Test
    fun `plaintext restore rejects a changed entry before modifying live data`() = runBlocking {
        database.vaultItemDao().insert(originalItem())
        database.attachmentDao().insert(originalAttachment())
        val export = repository.prepareExport(
            CharArray(0),
            BackupProtection.PLAINTEXT,
        ).successValue()
        repository.export(export, ARCHIVE_URI).successValue()
        rewriteArchiveEntry(BackupFormat.PLAINTEXT_DATABASE_PATH) { bytes ->
            bytes.copyOf().also { changed ->
                changed[changed.lastIndex / 2] = (changed[changed.lastIndex / 2].toInt() xor 1).toByte()
            }
        }
        Shadows.shadowOf(resolver).registerInputStream(
            ARCHIVE_URI,
            FileInputStream(provider.archive),
        )

        val result = repository.prepareRestore(ARCHIVE_URI, CharArray(0))

        assertTrue(result is RepositoryResult.Failure)
        val error = (result as RepositoryResult.Failure).error
        assertTrue(error is AppError.BackupValidationFailure)
        assertEquals(
            AppError.BackupValidationReason.CHECKSUM_MISMATCH,
            (error as AppError.BackupValidationFailure).reason,
        )
        assertEquals(1L, database.backupDao().countItems())
    }

    @Test
    fun `restore rejects path traversal before parsing or changing live data`() = runBlocking {
        database.vaultItemDao().insert(originalItem())
        ZipOutputStream(provider.archive.outputStream()).use { zip ->
            listOf(
                BackupFormat.MANIFEST_PATH,
                BackupFormat.CHECKSUMS_PATH,
                BackupFormat.DATABASE_PATH,
                "attachments/../escape.bin",
            ).forEach { path ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
        }
        Shadows.shadowOf(resolver).registerInputStream(
            ARCHIVE_URI,
            FileInputStream(provider.archive),
        )

        val result = repository.prepareRestore(ARCHIVE_URI, PASSWORD.copyOf())

        assertTrue(result is RepositoryResult.Failure)
        val error = (result as RepositoryResult.Failure).error
        assertTrue(error is AppError.BackupValidationFailure)
        assertEquals(
            AppError.BackupValidationReason.UNSAFE_ARCHIVE_ENTRY,
            (error as AppError.BackupValidationFailure).reason,
        )
        assertEquals(1L, database.backupDao().countItems())
        assertTrue(!File(context.cacheDir, "escape.bin").exists())
    }

    private fun originalItem() = VaultItemEntity(
        id = ORIGINAL_ID,
        type = VaultItemType.NOTE,
        color = VaultItemColor.BLUE,
        title = "Encrypted backup note",
        body = "private body",
        ocrText = "",
        isPinned = true,
        isFavorite = true,
        isArchived = false,
        createdAt = FIXED_TIME - 1_000L,
        updatedAt = FIXED_TIME,
        localRevision = 3L,
        remoteRevision = 3L,
        lastSyncedRevision = 3L,
        serverVersionToken = "not-exported",
        syncStatus = ItemSyncStatus.SYNCED,
        deletedAt = null,
        conflictOriginId = null,
    )

    private fun originalAttachment() = AttachmentEntity(
        id = ORIGINAL_ATTACHMENT_ID,
        parentItemId = ORIGINAL_ID,
        originalFilename = ORIGINAL_FILENAME,
        mimeType = "application/pdf",
        fileSize = ATTACHMENT_PAYLOAD.size.toLong(),
        imageWidth = null,
        imageHeight = null,
        pdfPageCount = 1,
        sha256Checksum = ATTACHMENT_SHA256,
        localEncryptedPath = ORIGINAL_LOCAL_PATH,
        remotePath = "not-exported",
        thumbnailPath = "not-exported",
        encryptionFormatVersion = CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION,
        uploadStatus = AttachmentUploadStatus.UPLOADED,
        createdAt = FIXED_TIME,
        ocrState = OcrState.COMPLETE,
        extractedOcrText = "searchable contract",
        ocrSourceChecksum = ATTACHMENT_SHA256,
        ocrFailureCode = null,
        ocrUpdatedAt = FIXED_TIME,
    )

    private fun rewriteArchiveEntry(path: String, transform: (ByteArray) -> ByteArray) {
        val replacement = File(provider.archive.parentFile, "rewritten-backup.vnb")
        ZipFile(provider.archive).use { source ->
            ZipOutputStream(replacement.outputStream()).use { destination ->
                val entries = source.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val bytes = source.getInputStream(entry).use { it.readBytes() }
                    destination.putNextEntry(ZipEntry(entry.name))
                    destination.write(if (entry.name == path) transform(bytes) else bytes)
                    destination.closeEntry()
                }
            }
        }
        check(provider.archive.delete())
        check(replacement.renameTo(provider.archive))
    }

    private class ArchiveProvider : ContentProvider() {
        lateinit var archive: File

        override fun onCreate(): Boolean {
            archive = File(requireNotNull(context).cacheDir, "backup-repository-test.vnb")
            archive.delete()
            return true
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val flags = if (mode.startsWith("r")) {
                ParcelFileDescriptor.MODE_READ_ONLY
            } else {
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE or
                    ParcelFileDescriptor.MODE_WRITE_ONLY
            }
            return ParcelFileDescriptor.open(archive, flags)
        }

        override fun getType(uri: Uri): String = "application/octet-stream"
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            if (archive.delete()) 1 else 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    private class RecordingAttachmentStorage(context: Context) : RestoredAttachmentStorage {
        val directory = File(context.cacheDir, "recording-restore-storage").apply {
            deleteRecursively()
            check(mkdirs())
        }
        val restoredPayloads = mutableListOf<ByteArray>()

        override suspend fun stage(
            plaintext: File,
            attachmentId: String,
            filename: String,
            mimeType: String,
            expectedSize: Long,
            expectedSha256: String,
        ): RepositoryResult<StagedRestoredAttachment> {
            val bytes = plaintext.readBytes()
            check(filename == ORIGINAL_FILENAME)
            check(mimeType == "application/pdf")
            check(bytes.size.toLong() == expectedSize)
            check(bytes.sha256() == expectedSha256)
            val pending = File(directory, ".pending-$attachmentId").apply { writeBytes(bytes) }
            return RepositoryResult.Success(
                StagedRestoredAttachment(
                    attachmentId = attachmentId,
                    pendingFile = pending,
                    destinationFile = File(directory, "$attachmentId.vne"),
                    relativePath = "attachments/$attachmentId.vne",
                ),
            )
        }

        override fun commit(staged: StagedRestoredAttachment): RepositoryResult<Unit> {
            check(staged.pendingFile.renameTo(staged.destinationFile))
            restoredPayloads += staged.destinationFile.readBytes()
            return RepositoryResult.Success(Unit)
        }

        override fun discard(staged: StagedRestoredAttachment) {
            staged.pendingFile.delete()
            staged.destinationFile.delete()
        }

        private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private object TestDispatchers : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private fun <T> RepositoryResult<T>.successValue(): T = when (this) {
        is RepositoryResult.Success -> value
        is RepositoryResult.Failure -> throw AssertionError(
            "Expected success, got ${error::class.simpleName}: $error",
        )
    }

    private companion object {
        const val ARCHIVE_AUTHORITY = "com.vaultnote.test.backup"
        val ARCHIVE_URI: Uri = Uri.parse("content://$ARCHIVE_AUTHORITY/archive.vnb")
        const val ORIGINAL_ID = "original_note"
        const val ORIGINAL_ATTACHMENT_ID = "original_attachment"
        const val ORIGINAL_FILENAME = "contract.pdf"
        const val ORIGINAL_LOCAL_PATH = "attachments/original_attachment.vne"
        const val FIXED_TIME = 1_725_000_000_000L
        val PASSWORD = "correct horse battery staple".toCharArray()
        val ATTACHMENT_PAYLOAD = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n".encodeToByteArray()
        val ATTACHMENT_SHA256: String = MessageDigest.getInstance("SHA-256")
            .digest(ATTACHMENT_PAYLOAD)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
