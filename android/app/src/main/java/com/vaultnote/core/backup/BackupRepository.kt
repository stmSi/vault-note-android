package com.vaultnote.core.backup

import android.content.ContentResolver
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.StatFs
import androidx.room.withTransaction
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.Clock
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.IdGenerator
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.SyncOperationState
import com.vaultnote.core.common.model.SyncOperationType
import com.vaultnote.core.database.VaultDatabase
import com.vaultnote.core.database.entity.AttachmentFileCleanupEntity
import com.vaultnote.core.database.entity.SearchDocumentEntity
import com.vaultnote.core.database.entity.SyncOperationEntity
import com.vaultnote.core.files.RestoredAttachmentStorage
import com.vaultnote.core.files.StagedRestoredAttachment
import com.vaultnote.core.security.VaultLockManager
import com.vaultnote.core.sync.SyncScheduleResult
import com.vaultnote.core.sync.SyncScheduler
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PreparedBackupExport internal constructor(
    internal val password: CharArray,
    internal val protection: BackupProtection,
) {
    internal fun clear() = password.fill('\u0000')
    override fun toString(): String = "PreparedBackupExport(redacted)"
}

class PreparedBackupRestore internal constructor(
    internal val stagingDirectory: File,
    val backupSummary: BackupSummary,
    internal val copiedItemCount: Long,
) {
    override fun toString(): String = "PreparedBackupRestore(redacted)"
}

interface BackupRepository {
    fun prepareExport(
        password: CharArray,
        protection: BackupProtection = BackupProtection.ENCRYPTED,
    ): RepositoryResult<PreparedBackupExport>

    suspend fun export(
        prepared: PreparedBackupExport,
        destination: Uri,
    ): RepositoryResult<BackupSummary>

    fun cancelExport(prepared: PreparedBackupExport)

    suspend fun prepareRestore(
        source: Uri,
        password: CharArray,
    ): RepositoryResult<PreparedBackupRestore>

    suspend fun commitRestore(
        prepared: PreparedBackupRestore,
    ): RepositoryResult<RestoreSummary>

    fun cancelRestore(prepared: PreparedBackupRestore)

    suspend fun discardDestination(destination: Uri)
}

internal fun interface BackupAttachmentReader {
    suspend fun writeVerifiedAttachment(
        attachmentId: String,
        relativePath: String,
        output: OutputStream,
    ): RepositoryResult<Unit>
}

internal class AndroidBackupRepository(
    context: Context,
    private val database: VaultDatabase,
    private val attachmentReader: BackupAttachmentReader,
    private val restoredAttachmentStore: RestoredAttachmentStorage,
    private val lockManager: VaultLockManager,
    private val syncScheduler: SyncScheduler,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val crypto: BackupCrypto = BackupCrypto(),
    private val resolver: ContentResolver = context.applicationContext.contentResolver,
    private val availableBytes: (File) -> Long = { directory ->
        StatFs(directory.path).availableBytes
    },
    private val syncFile: (FileOutputStream) -> Unit = { output -> output.fd.sync() },
) : BackupRepository {
    private val applicationContext = context.applicationContext
    private val databaseCodec = BackupDatabaseCodec(database.backupDao())
    private val operationMutex = Mutex()
    private val exportRoot = File(applicationContext.cacheDir, EXPORT_DIRECTORY)
    private val restoreRoot = File(applicationContext.cacheDir, RESTORE_DIRECTORY)

    override fun prepareExport(
        password: CharArray,
        protection: BackupProtection,
    ): RepositoryResult<PreparedBackupExport> {
        if (!lockManager.isContentAccessAllowed()) {
            password.fill('\u0000')
            return RepositoryResult.Failure(AppError.AuthenticationExpired)
        }
        val validation = if (protection == BackupProtection.ENCRYPTED) {
            validatePassword(password)
        } else {
            null
        }
        if (validation != null) {
            password.fill('\u0000')
            return RepositoryResult.Failure(validation)
        }
        val retainedPassword = if (protection == BackupProtection.ENCRYPTED) {
            password.copyOf()
        } else {
            CharArray(0)
        }
        return RepositoryResult.Success(PreparedBackupExport(retainedPassword, protection)).also {
            password.fill('\u0000')
        }
    }

    override suspend fun export(
        prepared: PreparedBackupExport,
        destination: Uri,
    ): RepositoryResult<BackupSummary> = operationMutex.withLock {
        withContext(dispatchers.io) {
            var completed = false
            var archive: File? = null
            try {
                requireUnlocked()
                if (destination.scheme != ContentResolver.SCHEME_CONTENT) {
                    return@withContext RepositoryResult.Failure(AppError.PermissionDenied)
                }
                cleanupAbandoned(exportRoot)
                ensureDirectory(exportRoot)
                val stats = database.withTransaction {
                    Triple(
                        database.backupDao().countItems(),
                        database.backupDao().countAttachments(),
                        database.backupDao().totalAttachmentBytes(),
                    )
                }
                if (
                    stats.first !in 0..BackupFormat.MAX_ITEM_COUNT ||
                    stats.second !in 0..BackupFormat.MAX_ATTACHMENT_COUNT ||
                    stats.third < 0L
                ) {
                    return@withContext validationFailure(
                        AppError.BackupValidationReason.LIMIT_EXCEEDED,
                    )
                }
                ensurePrivateCapacity(exportRoot, estimatedArchiveBytes(stats.third))
                archive = File(exportRoot, ".pending-${UUID.randomUUID()}.vnb")
                val summary = buildArchive(prepared, archive)
                copyArchiveToDestination(archive, destination)
                completed = true
                RepositoryResult.Success(summary)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (aborted: BackupAbort) {
                RepositoryResult.Failure(aborted.error)
            } catch (_: SecurityException) {
                RepositoryResult.Failure(AppError.PermissionDenied)
            } catch (_: IOException) {
                RepositoryResult.Failure(AppError.InsufficientStorage())
            } catch (failure: SQLiteException) {
                RepositoryResult.Failure(AppError.DatabaseFailure(OPERATION_EXPORT, failure))
            } catch (_: RuntimeException) {
                RepositoryResult.Failure(AppError.EncryptionFailure())
            } finally {
                prepared.clear()
                archive?.delete()
                if (!completed) discardDestination(destination)
            }
        }
    }

    override fun cancelExport(prepared: PreparedBackupExport) {
        prepared.clear()
    }

    override suspend fun prepareRestore(
        source: Uri,
        password: CharArray,
    ): RepositoryResult<PreparedBackupRestore> = operationMutex.withLock {
        withContext(dispatchers.io) {
            var staging: RestoreStagingStore? = null
            var stagingDirectory: File? = null
            var keepStaging = false
            try {
                if (!lockManager.isContentAccessAllowed()) {
                    return@withContext RepositoryResult.Failure(AppError.AuthenticationExpired)
                }
                if (source.scheme != ContentResolver.SCHEME_CONTENT) {
                    return@withContext RepositoryResult.Failure(AppError.PermissionDenied)
                }
                cleanupAbandoned(restoreRoot)
                ensureDirectory(restoreRoot)
                stagingDirectory = File(restoreRoot, "restore-${UUID.randomUUID()}")
                ensureDirectory(stagingDirectory)
                staging = RestoreStagingStore.create(stagingDirectory)
                val archive = File(stagingDirectory, ARCHIVE_FILE)
                copyArchiveFromSource(source, archive)
                ZipFile(archive).use { zip ->
                    val names = inspectArchive(zip)
                    val manifestBytes = readEntryBounded(
                        zip,
                        requireNotNull(zip.getEntry(BackupFormat.MANIFEST_PATH)),
                        BackupFormat.MAX_MANIFEST_BYTES.toLong(),
                    )
                    val manifest = when (val decoded = BackupManifestCodec.decode(manifestBytes)) {
                        is RepositoryResult.Success -> decoded.value
                        is RepositoryResult.Failure -> abort(decoded.error)
                    }
                    requireUnlocked()
                    val mapping = when (manifest.protection) {
                        BackupProtection.ENCRYPTED -> {
                            validatePassword(password)?.let(::abort)
                            prepareEncryptedRestore(zip, names, manifest, password, staging)
                        }
                        BackupProtection.PLAINTEXT ->
                            preparePlaintextRestore(zip, names, manifest, staging)
                    }
                    val counts = staging.counts()
                    val summary = BackupSummary(
                        itemCount = counts.first,
                        attachmentCount = counts.second,
                        createdAtEpochMillis = manifest.createdAtEpochMillis,
                        protection = manifest.protection,
                    )
                    archive.delete()
                    staging.close()
                    keepStaging = true
                    return@withContext RepositoryResult.Success(
                        PreparedBackupRestore(
                            stagingDirectory = stagingDirectory,
                            backupSummary = summary,
                            copiedItemCount = mapping.copiedItems,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (aborted: BackupAbort) {
                RepositoryResult.Failure(aborted.error)
            } catch (_: ZipException) {
                validationFailure(AppError.BackupValidationReason.INVALID_MANIFEST)
            } catch (_: SQLiteConstraintException) {
                validationFailure(AppError.BackupValidationReason.INVALID_DATA)
            } catch (failure: SQLiteException) {
                RepositoryResult.Failure(AppError.DatabaseFailure(OPERATION_RESTORE_STAGE, failure))
            } catch (_: IllegalArgumentException) {
                validationFailure(AppError.BackupValidationReason.INVALID_DATA)
            } catch (_: IllegalStateException) {
                validationFailure(AppError.BackupValidationReason.INVALID_DATA)
            } catch (_: SecurityException) {
                RepositoryResult.Failure(AppError.PermissionDenied)
            } catch (_: IOException) {
                validationFailure(AppError.BackupValidationReason.INVALID_DATA)
            } finally {
                password.fill('\u0000')
                if (!keepStaging) {
                    staging?.let { current ->
                        if (current.isReadyForCommit()) discardStagedFiles(current)
                        current.delete()
                    }
                    if (staging == null) stagingDirectory?.deleteRecursively()
                }
            }
        }
    }

    private suspend fun prepareEncryptedRestore(
        zip: ZipFile,
        names: Set<String>,
        manifest: BackupManifest,
        password: CharArray,
        staging: RestoreStagingStore,
    ): RestoreMappingStats {
        val key = when (
            val derived = withContext(dispatchers.default) {
                crypto.deriveKey(password, manifest.salt, manifest.kdfIterations)
            }
        ) {
            is RepositoryResult.Success -> derived.value
            is RepositoryResult.Failure -> abort(derived.error)
        }
        try {
            val binding = BackupManifestCodec.binding(manifest)
            val checksumsEntry = zip.getEntry(manifest.checksumsPath)
                ?: abortValidation(AppError.BackupValidationReason.MISSING_ENTRY)
            verifyArchiveEntry(
                zip,
                checksumsEntry,
                manifest.checksumsCiphertextSize,
                manifest.checksumsCiphertextSha256,
            )
            val checksumsPlaintext = File(staging.directory, CHECKSUMS_FILE)
            try {
                FileOutputStream(checksumsPlaintext).use { output ->
                    when (
                        val decrypted = crypto.decryptVerifiedTo(
                            path = manifest.checksumsPath,
                            key = key,
                            manifestBinding = binding,
                            openInput = { bufferedZipInput(zip, checksumsEntry) },
                            output = BufferedOutputStream(output, BUFFER_BYTES),
                            authenticationFailure = AppError.BackupValidationReason.WRONG_KEY,
                        )
                    ) {
                        is RepositoryResult.Success -> Unit
                        is RepositoryResult.Failure -> abort(decrypted.error)
                    }
                    syncFile(output)
                }
                val checksumPaths = readAndVerifyChecksums(
                    zip = zip,
                    staging = staging,
                    read = { consume ->
                        checksumsPlaintext.reader(StandardCharsets.UTF_8).use { reader ->
                            BackupChecksumsCodec.read(reader, consume)
                        }
                    },
                )
                verifyExpectedNames(
                    names,
                    checksumPaths,
                    BackupFormat.DATABASE_PATH,
                    manifest.checksumsPath,
                )
            } finally {
                checksumsPlaintext.delete()
            }

            val databaseEntry = zip.getEntry(BackupFormat.DATABASE_PATH)
                ?: abortValidation(AppError.BackupValidationReason.MISSING_ENTRY)
            requireUnlocked()
            val databasePlaintext = File(staging.directory, DATABASE_PLAINTEXT_FILE)
            try {
                FileOutputStream(databasePlaintext).use { output ->
                    when (
                        val decrypted = crypto.decryptVerifiedTo(
                            path = BackupFormat.DATABASE_PATH,
                            key = key,
                            manifestBinding = binding,
                            openInput = { bufferedZipInput(zip, databaseEntry) },
                            output = BufferedOutputStream(output, BUFFER_BYTES),
                            authenticationFailure =
                                AppError.BackupValidationReason.CHECKSUM_MISMATCH,
                        )
                    ) {
                        is RepositoryResult.Success -> Unit
                        is RepositoryResult.Failure -> abort(decrypted.error)
                    }
                    syncFile(output)
                }
                readDatabaseSnapshot(databasePlaintext, staging)
            } finally {
                databasePlaintext.delete()
            }
            verifyStagedArchiveCounts(staging)
            val mapping = planRestoreMappings(staging)
            stageRestoredAttachments(zip, key, binding, staging)
            return mapping
        } finally {
            key.close()
        }
    }

    private suspend fun preparePlaintextRestore(
        zip: ZipFile,
        names: Set<String>,
        manifest: BackupManifest,
        staging: RestoreStagingStore,
    ): RestoreMappingStats {
        val checksumsEntry = zip.getEntry(manifest.checksumsPath)
            ?: abortValidation(AppError.BackupValidationReason.MISSING_ENTRY)
        verifyArchiveEntry(
            zip,
            checksumsEntry,
            manifest.checksumsCiphertextSize,
            manifest.checksumsCiphertextSha256,
        )
        val checksumPaths = readAndVerifyChecksums(
            zip = zip,
            staging = staging,
            read = { consume ->
                bufferedZipInput(zip, checksumsEntry).reader(StandardCharsets.UTF_8).use { reader ->
                    PlaintextBackupChecksumsCodec.read(reader, consume)
                }
            },
        )
        verifyExpectedNames(
            names,
            checksumPaths,
            BackupFormat.PLAINTEXT_DATABASE_PATH,
            manifest.checksumsPath,
        )
        requireUnlocked()
        val databaseEntry = zip.getEntry(BackupFormat.PLAINTEXT_DATABASE_PATH)
            ?: abortValidation(AppError.BackupValidationReason.MISSING_ENTRY)
        val databasePlaintext = File(staging.directory, DATABASE_PLAINTEXT_FILE)
        try {
            copyZipEntryToFile(zip, databaseEntry, databasePlaintext)
            readDatabaseSnapshot(databasePlaintext, staging)
        } finally {
            databasePlaintext.delete()
        }
        verifyStagedArchiveCounts(staging)
        val mapping = planRestoreMappings(staging)
        stageRestoredPlaintextAttachments(zip, staging)
        return mapping
    }

    override suspend fun commitRestore(
        prepared: PreparedBackupRestore,
    ): RepositoryResult<RestoreSummary> = operationMutex.withLock {
        withContext(NonCancellable + dispatchers.io) {
            if (!lockManager.isContentAccessAllowed()) {
                return@withContext RepositoryResult.Failure(AppError.AuthenticationExpired)
            }
            val staging = try {
                RestoreStagingStore.open(prepared.stagingDirectory)
            } catch (_: RuntimeException) {
                return@withContext validationFailure(
                    AppError.BackupValidationReason.INVALID_DATA,
                )
            }
            var databaseTransactionStarted = false
            try {
                persistCleanupJournal(staging)
                commitStagedFiles(staging)
                databaseTransactionStarted = true
                database.withTransaction { commitStagedDatabase(staging) }
                val scheduled = try {
                    syncScheduler.requestSync()
                } catch (_: RuntimeException) {
                    SyncScheduleResult.Rejected(SYNC_SCHEDULER_UNAVAILABLE)
                }
                val warning = when (scheduled) {
                    SyncScheduleResult.Scheduled,
                    SyncScheduleResult.Coalesced,
                    -> null
                    is SyncScheduleResult.Rejected ->
                        AppError.SyncSchedulingFailure(scheduled.reason)
                }
                val counts = staging.counts()
                staging.delete()
                RepositoryResult.Success(
                    RestoreSummary(
                        restoredItemCount = counts.first,
                        restoredAttachmentCount = counts.second,
                        copiedItemCount = prepared.copiedItemCount,
                    ),
                    warning = warning,
                )
            } catch (aborted: BackupAbort) {
                if (!databaseTransactionStarted) discardStagedFiles(staging)
                staging.delete()
                RepositoryResult.Failure(aborted.error)
            } catch (failure: RuntimeException) {
                if (!databaseTransactionStarted) discardStagedFiles(staging)
                staging.delete()
                RepositoryResult.Failure(
                    AppError.DatabaseFailure(OPERATION_RESTORE, failure),
                )
            }
        }
    }

    override fun cancelRestore(prepared: PreparedBackupRestore) {
        try {
            val staging = RestoreStagingStore.open(prepared.stagingDirectory)
            discardStagedFiles(staging)
            staging.delete()
        } catch (_: RuntimeException) {
            prepared.stagingDirectory.deleteRecursively()
        }
    }

    override suspend fun discardDestination(destination: Uri) {
        withContext(dispatchers.io) {
            val deleted = try {
                resolver.delete(destination, null, null) > 0
            } catch (_: RuntimeException) {
                false
            }
            if (!deleted) {
                try {
                    resolver.openOutputStream(destination, TRUNCATE_MODE)?.use { }
                } catch (_: IOException) {
                    Unit
                } catch (_: RuntimeException) {
                    Unit
                }
            }
        }
    }

    private suspend fun buildArchive(
        prepared: PreparedBackupExport,
        destination: File,
    ): BackupSummary = when (prepared.protection) {
        BackupProtection.ENCRYPTED -> buildEncryptedArchive(prepared, destination)
        BackupProtection.PLAINTEXT -> buildPlaintextArchive(destination)
    }

    private suspend fun buildEncryptedArchive(
        prepared: PreparedBackupExport,
        destination: File,
    ): BackupSummary {
        val salt = crypto.newSalt()
        val archiveId = crypto.newArchiveId()
        val createdAt = clock.nowEpochMillis()
        val manifestBase = BackupManifest(
            archiveId = archiveId,
            createdAtEpochMillis = createdAt,
            salt = salt,
            kdfIterations = BackupFormat.KDF_ITERATIONS,
            checksumsCiphertextSize = 1L,
            checksumsCiphertextSha256 = "0".repeat(64),
        )
        val binding = BackupManifestCodec.binding(manifestBase)
        val key = when (
            val derived = withContext(dispatchers.default) {
                crypto.deriveKey(prepared.password, salt, BackupFormat.KDF_ITERATIONS)
            }
        ) {
            is RepositoryResult.Success -> derived.value
            is RepositoryResult.Failure -> abort(derived.error)
        }
        val sourcesFile = File(exportRoot, ".sources-${UUID.randomUUID()}.json")
        val checksumsFile = File(exportRoot, ".checksums-${UUID.randomUUID()}.json")
        var snapshotStats: BackupSnapshotStats? = null
        try {
            requireUnlocked()
            FileOutputStream(destination).use { fileOutput ->
                val zip = ZipOutputStream(BufferedOutputStream(fileOutput, BUFFER_BYTES))
                try {
                    zip.setLevel(Deflater.NO_COMPRESSION)
                    checksumsFile.writer(StandardCharsets.UTF_8).use { checksumText ->
                        val checksumJson = BackupChecksumsCodec.newWriter(checksumText)
                        putZipEntry(zip, BackupFormat.DATABASE_PATH)
                        val databaseChecksum = crypto.encryptEntry(
                            path = BackupFormat.DATABASE_PATH,
                            key = key,
                            manifestBinding = binding,
                            output = zip,
                        ) { encryptedOutput ->
                            sourcesFile.writer(StandardCharsets.UTF_8).use { sources ->
                                snapshotStats = database.withTransaction {
                                    databaseCodec.write(encryptedOutput, sources)
                                }
                            }
                        }
                        zip.closeEntry()
                        BackupChecksumsCodec.writeEntry(checksumJson, databaseChecksum)

                        sourcesFile.reader(StandardCharsets.UTF_8).use { sourcesText ->
                            BackupAttachmentSourcesCodec.openReader(sourcesText).use { sources ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val source = sources.next() ?: break
                                    requireUnlocked()
                                    putZipEntry(zip, source.entryPath)
                                    val checksum = crypto.encryptEntry(
                                        path = source.entryPath,
                                        key = key,
                                        manifestBinding = binding,
                                        output = zip,
                                    ) { encryptedOutput ->
                                        val verified = PlaintextVerifierOutputStream(encryptedOutput)
                                        when (
                                            val decrypted = attachmentReader.writeVerifiedAttachment(
                                                attachmentId = source.attachmentId,
                                                relativePath = source.localRelativePath,
                                                output = verified,
                                            )
                                        ) {
                                            is RepositoryResult.Failure -> abort(decrypted.error)
                                            is RepositoryResult.Success -> Unit
                                        }
                                        if (
                                            verified.byteCount != source.plaintextSize ||
                                            verified.sha256() != source.plaintextSha256
                                        ) {
                                            abort(AppError.CorruptedFile)
                                        }
                                    }
                                    zip.closeEntry()
                                    BackupChecksumsCodec.writeEntry(checksumJson, checksum)
                                }
                            }
                        }
                        BackupChecksumsCodec.finish(checksumJson)
                    }

                    putZipEntry(zip, BackupFormat.CHECKSUMS_PATH)
                    val checksumEntry = crypto.encryptEntry(
                        path = BackupFormat.CHECKSUMS_PATH,
                        key = key,
                        manifestBinding = binding,
                        output = zip,
                    ) { encryptedOutput ->
                        FileInputStream(checksumsFile).buffered(BUFFER_BYTES).use { input ->
                            input.copyTo(encryptedOutput, BUFFER_BYTES)
                        }
                    }
                    zip.closeEntry()
                    val manifest = manifestBase.copy(
                        checksumsCiphertextSize = checksumEntry.ciphertextSize,
                        checksumsCiphertextSha256 = checksumEntry.ciphertextSha256,
                    )
                    putZipEntry(zip, BackupFormat.MANIFEST_PATH)
                    zip.write(BackupManifestCodec.encode(manifest))
                    zip.closeEntry()
                    zip.finish()
                    zip.flush()
                    syncFile(fileOutput)
                } finally {
                    zip.close()
                }
            }
            val stats = requireNotNull(snapshotStats)
            return BackupSummary(
                stats.itemCount,
                stats.attachmentCount,
                createdAt,
                BackupProtection.ENCRYPTED,
            )
        } finally {
            key.close()
            sourcesFile.delete()
            checksumsFile.delete()
        }
    }

    private suspend fun buildPlaintextArchive(destination: File): BackupSummary {
        val createdAt = clock.nowEpochMillis()
        val archiveId = crypto.newArchiveId()
        val sourcesFile = File(exportRoot, ".sources-${UUID.randomUUID()}.json")
        val checksumsFile = File(exportRoot, ".checksums-${UUID.randomUUID()}.json")
        var snapshotStats: BackupSnapshotStats? = null
        try {
            requireUnlocked()
            FileOutputStream(destination).use { fileOutput ->
                val zip = ZipOutputStream(BufferedOutputStream(fileOutput, BUFFER_BYTES))
                try {
                    zip.setLevel(Deflater.NO_COMPRESSION)
                    checksumsFile.writer(StandardCharsets.UTF_8).use { checksumText ->
                        val checksumJson = PlaintextBackupChecksumsCodec.newWriter(checksumText)
                        putZipEntry(zip, BackupFormat.PLAINTEXT_DATABASE_PATH)
                        val databaseOutput = PlaintextVerifierOutputStream(zip)
                        sourcesFile.writer(StandardCharsets.UTF_8).use { sources ->
                            snapshotStats = database.withTransaction {
                                databaseCodec.write(databaseOutput, sources)
                            }
                        }
                        val databaseSha256 = databaseOutput.sha256()
                        zip.closeEntry()
                        PlaintextBackupChecksumsCodec.writeEntry(
                            checksumJson,
                            BackupEntryChecksum(
                                BackupFormat.PLAINTEXT_DATABASE_PATH,
                                databaseOutput.byteCount,
                                databaseSha256,
                            ),
                        )

                        sourcesFile.reader(StandardCharsets.UTF_8).use { sourcesText ->
                            BackupAttachmentSourcesCodec.openReader(sourcesText).use { sources ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val source = sources.next() ?: break
                                    requireUnlocked()
                                    putZipEntry(zip, source.entryPath)
                                    val attachmentOutput = PlaintextVerifierOutputStream(zip)
                                    when (
                                        val copied = attachmentReader.writeVerifiedAttachment(
                                            attachmentId = source.attachmentId,
                                            relativePath = source.localRelativePath,
                                            output = attachmentOutput,
                                        )
                                    ) {
                                        is RepositoryResult.Failure -> abort(copied.error)
                                        is RepositoryResult.Success -> Unit
                                    }
                                    val attachmentSha256 = attachmentOutput.sha256()
                                    if (
                                        attachmentOutput.byteCount != source.plaintextSize ||
                                        attachmentSha256 != source.plaintextSha256
                                    ) {
                                        abort(AppError.CorruptedFile)
                                    }
                                    zip.closeEntry()
                                    PlaintextBackupChecksumsCodec.writeEntry(
                                        checksumJson,
                                        BackupEntryChecksum(
                                            source.entryPath,
                                            attachmentOutput.byteCount,
                                            attachmentSha256,
                                        ),
                                    )
                                }
                            }
                        }
                        PlaintextBackupChecksumsCodec.finish(checksumJson)
                    }

                    putZipEntry(zip, BackupFormat.PLAINTEXT_CHECKSUMS_PATH)
                    val checksumOutput = PlaintextVerifierOutputStream(zip)
                    FileInputStream(checksumsFile).buffered(BUFFER_BYTES).use { input ->
                        input.copyTo(checksumOutput, BUFFER_BYTES)
                    }
                    val checksumsSha256 = checksumOutput.sha256()
                    zip.closeEntry()
                    val manifest = BackupManifest(
                        archiveId = archiveId,
                        createdAtEpochMillis = createdAt,
                        salt = byteArrayOf(),
                        kdfIterations = 0,
                        checksumsCiphertextSize = checksumOutput.byteCount,
                        checksumsCiphertextSha256 = checksumsSha256,
                        formatVersion = BackupFormat.PLAINTEXT_VERSION,
                        minimumReaderVersion = BackupFormat.PLAINTEXT_MIN_READER_VERSION,
                        protection = BackupProtection.PLAINTEXT,
                        checksumsPath = BackupFormat.PLAINTEXT_CHECKSUMS_PATH,
                    )
                    putZipEntry(zip, BackupFormat.MANIFEST_PATH)
                    zip.write(BackupManifestCodec.encode(manifest))
                    zip.closeEntry()
                    zip.finish()
                    zip.flush()
                    syncFile(fileOutput)
                } finally {
                    zip.close()
                }
            }
            val stats = requireNotNull(snapshotStats)
            return BackupSummary(
                stats.itemCount,
                stats.attachmentCount,
                createdAt,
                BackupProtection.PLAINTEXT,
            )
        } finally {
            sourcesFile.delete()
            checksumsFile.delete()
        }
    }

    private fun readAndVerifyChecksums(
        zip: ZipFile,
        staging: RestoreStagingStore,
        read: (((BackupEntryChecksum) -> Unit) -> Unit),
    ): Set<String> {
        val checksumPaths = linkedSetOf<String>()
        read { checksum ->
            if (!checksumPaths.add(checksum.path)) {
                abortValidation(AppError.BackupValidationReason.DUPLICATE_ENTRY)
            }
            val entry = zip.getEntry(checksum.path)
                ?: abortValidation(AppError.BackupValidationReason.MISSING_ENTRY)
            verifyArchiveEntry(
                zip,
                entry,
                checksum.ciphertextSize,
                checksum.ciphertextSha256,
            )
            staging.addArchiveEntry(checksum)
        }
        return checksumPaths
    }

    private fun verifyExpectedNames(
        names: Set<String>,
        checksumPaths: Set<String>,
        databasePath: String,
        checksumsPath: String,
    ) {
        if (databasePath !in checksumPaths) {
            abortValidation(AppError.BackupValidationReason.MISSING_ENTRY)
        }
        val expectedNames = checksumPaths + setOf(BackupFormat.MANIFEST_PATH, checksumsPath)
        if (names != expectedNames) {
            abortValidation(
                if (expectedNames.any { it !in names }) {
                    AppError.BackupValidationReason.MISSING_ENTRY
                } else {
                    AppError.BackupValidationReason.INVALID_DATA
                },
            )
        }
    }

    private fun readDatabaseSnapshot(file: File, staging: RestoreStagingStore) {
        FileInputStream(file).buffered(BUFFER_BYTES).use { input ->
            databaseCodec.read(input, staging)
        }
    }

    private fun verifyStagedArchiveCounts(staging: RestoreStagingStore) {
        if (staging.archiveEntryCount() != staging.counts().second + 1L) {
            abortValidation(AppError.BackupValidationReason.MISSING_ENTRY)
        }
    }

    private suspend fun planRestoreMappings(staging: RestoreStagingStore): RestoreMappingStats =
        staging.planMappings(
            itemDao = database.vaultItemDao(),
            tagDao = database.tagDao(),
            attachmentDao = database.attachmentDao(),
            idGenerator = idGenerator,
        )

    private fun copyZipEntryToFile(zip: ZipFile, entry: ZipEntry, destination: File) {
        FileOutputStream(destination).use { output ->
            val buffered = BufferedOutputStream(output, BUFFER_BYTES)
            bufferedZipInput(zip, entry).use { input ->
                input.copyTo(buffered, BUFFER_BYTES)
            }
            buffered.flush()
            syncFile(output)
        }
    }

    private suspend fun stageRestoredPlaintextAttachments(
        zip: ZipFile,
        staging: RestoreStagingStore,
    ) {
        var after = ""
        while (true) {
            val page = staging.attachmentPlansPage(after, BackupFormat.PAGE_SIZE)
            for (plan in page) {
                currentCoroutineContext().ensureActive()
                requireUnlocked()
                val expected = staging.archiveEntry(plan.entryPath)
                    ?: abortValidation(AppError.BackupValidationReason.INVALID_DATA)
                val entry = zip.getEntry(plan.entryPath)
                    ?: abortValidation(AppError.BackupValidationReason.INVALID_DATA)
                if (
                    expected.ciphertextSize != entry.size ||
                    expected.path != plan.entryPath ||
                    entry.size != plan.fileSize
                ) {
                    abortValidation(AppError.BackupValidationReason.CHECKSUM_MISMATCH)
                }
                val plaintext = File(staging.directory, ".attachment-${UUID.randomUUID()}.tmp")
                try {
                    copyZipEntryToFile(zip, entry, plaintext)
                    stageRestoredAttachment(staging, plan, plaintext)
                } finally {
                    plaintext.delete()
                }
            }
            if (page.size < BackupFormat.PAGE_SIZE) break
            after = page.last().originalId
        }
    }

    private suspend fun stageRestoredAttachments(
        zip: ZipFile,
        key: BackupCrypto.BackupKey,
        binding: ByteArray,
        staging: RestoreStagingStore,
    ) {
        var after = ""
        while (true) {
            val page = staging.attachmentPlansPage(after, BackupFormat.PAGE_SIZE)
            for (plan in page) {
                currentCoroutineContext().ensureActive()
                requireUnlocked()
                val expected = staging.archiveEntry(plan.entryPath)
                    ?: abortValidation(AppError.BackupValidationReason.INVALID_DATA)
                val entry = zip.getEntry(plan.entryPath)
                    ?: abortValidation(AppError.BackupValidationReason.INVALID_DATA)
                val plaintext = File(staging.directory, ".attachment-${UUID.randomUUID()}.tmp")
                try {
                    FileOutputStream(plaintext).use { fileOutput ->
                        when (
                            val decrypted = crypto.decryptVerifiedTo(
                                path = plan.entryPath,
                                key = key,
                                manifestBinding = binding,
                                openInput = { bufferedZipInput(zip, entry) },
                                output = BufferedOutputStream(fileOutput, BUFFER_BYTES),
                                authenticationFailure =
                                    AppError.BackupValidationReason.CHECKSUM_MISMATCH,
                            )
                        ) {
                            is RepositoryResult.Failure -> abort(decrypted.error)
                            is RepositoryResult.Success -> {
                                if (decrypted.value != plan.fileSize) {
                                    abortValidation(
                                        AppError.BackupValidationReason.CHECKSUM_MISMATCH,
                                    )
                                }
                            }
                        }
                        syncFile(fileOutput)
                    }
                    if (
                        expected.ciphertextSize != entry.size ||
                        expected.path != plan.entryPath
                    ) {
                        abortValidation(AppError.BackupValidationReason.CHECKSUM_MISMATCH)
                    }
                    stageRestoredAttachment(staging, plan, plaintext)
                } finally {
                    plaintext.delete()
                }
            }
            if (page.size < BackupFormat.PAGE_SIZE) break
            after = page.last().originalId
        }
    }

    private suspend fun stageRestoredAttachment(
        staging: RestoreStagingStore,
        plan: RestoreAttachmentPlan,
        plaintext: File,
    ) {
        val staged = when (
            val result = restoredAttachmentStore.stage(
                plaintext = plaintext,
                attachmentId = plan.finalId,
                filename = plan.filename,
                mimeType = plan.mimeType,
                expectedSize = plan.fileSize,
                expectedSha256 = plan.sha256,
            )
        ) {
            is RepositoryResult.Success -> result.value
            is RepositoryResult.Failure -> abort(result.error)
        }
        staging.setAttachmentStage(
            originalId = plan.originalId,
            pendingFile = staged.pendingFile,
            destinationFile = staged.destinationFile,
            relativePath = staged.relativePath,
        )
    }

    private suspend fun persistCleanupJournal(staging: RestoreStagingStore) {
        val cleanupDao = database.attachmentFileCleanupDao()
        var after = ""
        while (true) {
            val page = staging.attachmentPlansPage(after, BackupFormat.PAGE_SIZE)
            val entries = page.map { plan ->
                AttachmentFileCleanupEntity(
                    cleanupId = cleanupId(plan.finalId),
                    localRelativePath = requireNotNull(plan.relativePath),
                    thumbnailRelativePath = null,
                    createdAt = clock.nowEpochMillis(),
                    attemptCount = 0,
                    lastAttemptAt = null,
                )
            }
            if (entries.isNotEmpty()) cleanupDao.upsertAll(entries)
            if (page.size < BackupFormat.PAGE_SIZE) break
            after = page.last().originalId
        }
    }

    private fun commitStagedFiles(staging: RestoreStagingStore) {
        var after = ""
        while (true) {
            val page = staging.attachmentPlansPage(after, BackupFormat.PAGE_SIZE)
            for (plan in page) {
                val staged = plan.toStagedAttachment()
                when (val committed = restoredAttachmentStore.commit(staged)) {
                    is RepositoryResult.Success -> Unit
                    is RepositoryResult.Failure -> abort(committed.error)
                }
            }
            if (page.size < BackupFormat.PAGE_SIZE) break
            after = page.last().originalId
        }
    }

    private suspend fun commitStagedDatabase(staging: RestoreStagingStore) {
        val itemDao = database.vaultItemDao()
        val tagDao = database.tagDao()
        val attachmentDao = database.attachmentDao()
        val searchDao = database.searchDao()
        val syncDao = database.syncOperationDao()
        val cleanupDao = database.attachmentFileCleanupDao()

        var after = ""
        while (true) {
            val page = staging.readItemsPage(after, BackupFormat.PAGE_SIZE)
            page.forEach { (_, item) -> itemDao.insert(item) }
            if (page.size < BackupFormat.PAGE_SIZE) break
            after = page.last().first
        }
        after = ""
        while (true) {
            val page = staging.readNewTagsPage(after, BackupFormat.PAGE_SIZE)
            if (page.isNotEmpty()) tagDao.insertTags(page.map(Pair<String, com.vaultnote.core.database.entity.TagEntity>::second))
            if (page.size < BackupFormat.PAGE_SIZE) break
            after = page.last().first
        }
        var afterItem = ""
        var afterTag = ""
        while (true) {
            val page = staging.readItemTagsPage(afterItem, afterTag, BackupFormat.PAGE_SIZE)
            if (page.isNotEmpty()) tagDao.insertCrossRefs(page.map { it.third })
            if (page.size < BackupFormat.PAGE_SIZE) break
            afterItem = page.last().first
            afterTag = page.last().second
        }
        after = ""
        while (true) {
            val page = staging.readAttachmentsPage(after, BackupFormat.PAGE_SIZE)
            page.forEach { (_, attachment) -> attachmentDao.insert(attachment) }
            if (page.size < BackupFormat.PAGE_SIZE) break
            after = page.last().first
        }

        after = ""
        while (true) {
            val page = staging.readItemsPage(after, BackupFormat.PAGE_SIZE)
            for ((_, item) in page) {
                val tags = tagDao.getTagsForItem(item.id).joinToString("\n") { it.name }
                val filenames = attachmentDao.getSearchableFilenames(item.id).orEmpty()
                val row = searchDao.insertDocument(
                    SearchDocumentEntity(
                        itemId = item.id,
                        title = item.title,
                        body = item.body,
                        tags = tags,
                        attachmentFilenames = filenames,
                        ocrText = item.ocrText,
                    ),
                )
                check(row != -1L)
                enqueueSyncOperation(
                    syncDao = syncDao,
                    dedupeKey = "item:${item.id}",
                    itemId = item.id,
                    attachmentId = null,
                    type = if (item.deletedAt == null) {
                        SyncOperationType.UPSERT_ITEM
                    } else {
                        SyncOperationType.DELETE_ITEM
                    },
                    targetRevision = item.localRevision,
                )
            }
            if (page.size < BackupFormat.PAGE_SIZE) break
            after = page.last().first
        }

        after = ""
        while (true) {
            val page = staging.readAttachmentsPage(after, BackupFormat.PAGE_SIZE)
            for ((_, attachment) in page) {
                val parent = itemDao.getById(attachment.parentItemId) ?: error("Missing restored parent")
                if (parent.deletedAt == null) {
                    enqueueSyncOperation(
                        syncDao = syncDao,
                        dedupeKey = "attachment:${attachment.id}",
                        itemId = parent.id,
                        attachmentId = attachment.id,
                        type = SyncOperationType.UPLOAD_ATTACHMENT,
                        targetRevision = parent.localRevision,
                    )
                }
            }
            if (page.size < BackupFormat.PAGE_SIZE) break
            after = page.last().first
        }

        after = ""
        while (true) {
            val page = staging.attachmentPlansPage(after, BackupFormat.PAGE_SIZE)
            if (page.isNotEmpty()) {
                cleanupDao.deleteByCleanupIds(page.map { cleanupId(it.finalId) })
            }
            if (page.size < BackupFormat.PAGE_SIZE) break
            after = page.last().originalId
        }
    }

    private suspend fun enqueueSyncOperation(
        syncDao: com.vaultnote.core.database.dao.SyncOperationDao,
        dedupeKey: String,
        itemId: String,
        attachmentId: String?,
        type: SyncOperationType,
        targetRevision: Long,
    ) {
        val now = clock.nowEpochMillis()
        syncDao.insert(
            SyncOperationEntity(
                operationId = idGenerator.newId(),
                dedupeKey = dedupeKey,
                itemId = itemId,
                attachmentId = attachmentId,
                operationType = type,
                targetRevision = targetRevision,
                state = SyncOperationState.PENDING,
                attemptCount = 0,
                nextAttemptAt = now,
                leaseToken = null,
                leaseExpiresAt = null,
                createdAt = now,
                updatedAt = now,
                lastErrorCode = null,
            ),
        )
    }

    private fun inspectArchive(zip: ZipFile): Set<String> {
        val names = linkedSetOf<String>()
        var totalUncompressed = 0L
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory || !isSafeEntryName(entry.name)) {
                abortValidation(AppError.BackupValidationReason.UNSAFE_ARCHIVE_ENTRY)
            }
            if (!names.add(entry.name)) {
                abortValidation(AppError.BackupValidationReason.DUPLICATE_ENTRY)
            }
            if (entry.size < 0L || entry.compressedSize < 0L) {
                abortValidation(AppError.BackupValidationReason.INVALID_DATA)
            }
            totalUncompressed = try {
                Math.addExact(totalUncompressed, entry.size)
            } catch (_: ArithmeticException) {
                abortValidation(AppError.BackupValidationReason.LIMIT_EXCEEDED)
            }
            val maximumInflatedSize = if (
                entry.compressedSize >
                (Long.MAX_VALUE - COMPRESSION_ALLOWANCE_BYTES) / MAX_COMPRESSION_RATIO
            ) {
                Long.MAX_VALUE
            } else {
                entry.compressedSize * MAX_COMPRESSION_RATIO + COMPRESSION_ALLOWANCE_BYTES
            }
            if (
                totalUncompressed > BackupFormat.MAX_ARCHIVE_BYTES ||
                entry.size > maximumInflatedSize ||
                names.size > BackupFormat.MAX_ENTRY_COUNT
            ) {
                abortValidation(AppError.BackupValidationReason.LIMIT_EXCEEDED)
            }
        }
        if (BackupFormat.MANIFEST_PATH !in names) {
            abortValidation(AppError.BackupValidationReason.MISSING_ENTRY)
        }
        return names
    }

    private fun isSafeEntryName(name: String): Boolean {
        if (
            name.isBlank() || name.length > MAX_ENTRY_NAME_LENGTH ||
            name.indexOf('\u0000') >= 0 || name.indexOf('\\') >= 0 ||
            name.startsWith('/')
        ) {
            return false
        }
        val segments = name.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return false
        return name == BackupFormat.MANIFEST_PATH ||
            name == BackupFormat.CHECKSUMS_PATH ||
            name == BackupFormat.DATABASE_PATH ||
            name == BackupFormat.PLAINTEXT_CHECKSUMS_PATH ||
            name == BackupFormat.PLAINTEXT_DATABASE_PATH ||
            ATTACHMENT_ENTRY_PATTERN.matches(name)
    }

    private fun verifyArchiveEntry(
        zip: ZipFile,
        entry: ZipEntry,
        expectedSize: Long,
        expectedSha256: String,
    ) {
        if (expectedSize <= 0L || entry.size != expectedSize) {
            abortValidation(AppError.BackupValidationReason.CHECKSUM_MISMATCH)
        }
        val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
        var bytes = 0L
        bufferedZipInput(zip, entry).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                bytes = try {
                    Math.addExact(bytes, count.toLong())
                } catch (_: ArithmeticException) {
                    abortValidation(AppError.BackupValidationReason.CHECKSUM_MISMATCH)
                }
                if (bytes > expectedSize) {
                    abortValidation(AppError.BackupValidationReason.CHECKSUM_MISMATCH)
                }
                digest.update(buffer, 0, count)
            }
        }
        if (bytes != expectedSize || digest.digest().toHex() != expectedSha256) {
            abortValidation(AppError.BackupValidationReason.CHECKSUM_MISMATCH)
        }
    }

    private fun readEntryBounded(zip: ZipFile, entry: ZipEntry, maximumBytes: Long): ByteArray {
        require(entry.size in 1..maximumBytes)
        return bufferedZipInput(zip, entry).use { input ->
            val output = java.io.ByteArrayOutputStream(entry.size.toInt())
            val buffer = ByteArray(4 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                require(total <= maximumBytes)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun bufferedZipInput(zip: ZipFile, entry: ZipEntry): BufferedInputStream =
        BufferedInputStream(zip.getInputStream(entry), BUFFER_BYTES)

    private suspend fun copyArchiveFromSource(source: Uri, destination: File) {
        val declared = try {
            resolver.openAssetFileDescriptor(source, "r")?.use { it.length }
        } catch (_: IOException) {
            null
        }?.takeIf { it >= 0L }
        if (declared != null && declared > BackupFormat.MAX_ARCHIVE_BYTES) {
            abort(AppError.FileTooLarge(BackupFormat.MAX_ARCHIVE_BYTES))
        }
        ensurePrivateCapacity(destination.parentFile ?: restoreRoot, declared ?: 0L)
        val input = resolver.openInputStream(source) ?: abort(AppError.PermissionDenied)
        input.buffered(BUFFER_BYTES).use { bufferedInput ->
            FileOutputStream(destination).use { fileOutput ->
                val buffer = ByteArray(BUFFER_BYTES)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = bufferedInput.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total = Math.addExact(total, count.toLong())
                    if (total > BackupFormat.MAX_ARCHIVE_BYTES) {
                        abort(AppError.FileTooLarge(BackupFormat.MAX_ARCHIVE_BYTES))
                    }
                    fileOutput.write(buffer, 0, count)
                }
                syncFile(fileOutput)
            }
        }
    }

    private fun copyArchiveToDestination(archive: File, destination: Uri) {
        val output = resolver.openOutputStream(destination, WRITE_MODE)
            ?: abort(AppError.PermissionDenied)
        output.use { raw ->
            BufferedOutputStream(raw, BUFFER_BYTES).use { buffered ->
                FileInputStream(archive).buffered(BUFFER_BYTES).use { input ->
                    input.copyTo(buffered, BUFFER_BYTES)
                }
            }
        }
    }

    private fun ensurePrivateCapacity(directory: File, requestedBytes: Long) {
        if (requestedBytes < 0L) abort(AppError.InsufficientStorage())
        val available = availableBytes(directory)
        if (
            available < BackupFormat.PRIVATE_SPACE_RESERVE_BYTES ||
            requestedBytes > available - BackupFormat.PRIVATE_SPACE_RESERVE_BYTES
        ) {
            abort(AppError.InsufficientStorage(requestedBytes))
        }
    }

    private fun estimatedArchiveBytes(attachmentBytes: Long): Long {
        val databaseBytes = applicationContext.getDatabasePath(VaultDatabase.DATABASE_NAME)
            .length()
            .coerceAtLeast(1L)
        return try {
            Math.addExact(
                Math.addExact(attachmentBytes, Math.multiplyExact(databaseBytes, 2L)),
                BackupFormat.PRIVATE_SPACE_RESERVE_BYTES,
            )
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            abort(AppError.InsufficientStorage())
        }
    }

    private fun cleanupAbandoned(root: File) {
        if (!root.isDirectory) return
        val cutoff = clock.nowEpochMillis() - ABANDONED_AGE_MILLIS
        root.listFiles()?.forEach { candidate ->
            if (candidate.lastModified() <= cutoff) candidate.deleteRecursively()
        }
    }

    private fun discardStagedFiles(staging: RestoreStagingStore) {
        var after = ""
        while (true) {
            val page = staging.attachmentPlansPage(after, BackupFormat.PAGE_SIZE)
            page.forEach { plan ->
                if (plan.pendingFile != null && plan.destinationFile != null && plan.relativePath != null) {
                    restoredAttachmentStore.discard(plan.toStagedAttachment())
                }
            }
            if (page.size < BackupFormat.PAGE_SIZE) break
            after = page.last().originalId
        }
    }

    private fun RestoreAttachmentPlan.toStagedAttachment(): StagedRestoredAttachment =
        StagedRestoredAttachment(
            attachmentId = finalId,
            pendingFile = File(requireNotNull(pendingFile)),
            destinationFile = File(requireNotNull(destinationFile)),
            relativePath = requireNotNull(relativePath),
        )

    private fun putZipEntry(zip: ZipOutputStream, path: String) {
        zip.putNextEntry(ZipEntry(path).apply { time = ZIP_EPOCH_MILLIS })
    }

    private fun cleanupId(attachmentId: String): String = "restore:$attachmentId"

    private fun validatePassword(password: CharArray): AppError.InvalidInput? {
        if (password.any { it == '\u0000' }) {
            return AppError.InvalidInput("backup_password", "contains_nul")
        }
        var codePoints = 0
        var index = 0
        while (index < password.size) {
            val character = password[index]
            when {
                Character.isHighSurrogate(character) -> {
                    if (
                        index + 1 >= password.size ||
                        !Character.isLowSurrogate(password[index + 1])
                    ) {
                        return AppError.InvalidInput(
                            "backup_password",
                            "contains_invalid_unicode",
                        )
                    }
                    index += 2
                }
                Character.isLowSurrogate(character) -> return AppError.InvalidInput(
                    "backup_password",
                    "contains_invalid_unicode",
                )
                else -> index += 1
            }
            codePoints += 1
        }
        return if (codePoints !in BackupFormat.MIN_PASSWORD_CODE_POINTS..
            BackupFormat.MAX_PASSWORD_CODE_POINTS
        ) {
            AppError.InvalidInput(
                "backup_password",
                "must_be_${BackupFormat.MIN_PASSWORD_CODE_POINTS}_to_" +
                    "${BackupFormat.MAX_PASSWORD_CODE_POINTS}_characters",
            )
        } else {
            null
        }
    }

    private fun validationFailure(
        reason: AppError.BackupValidationReason,
    ): RepositoryResult.Failure = RepositoryResult.Failure(
        AppError.BackupValidationFailure(reason),
    )

    private fun abortValidation(reason: AppError.BackupValidationReason): Nothing =
        abort(AppError.BackupValidationFailure(reason))

    private fun requireUnlocked() {
        if (!lockManager.isContentAccessAllowed()) abort(AppError.AuthenticationExpired)
    }

    private fun abort(error: AppError): Nothing = throw BackupAbort(error)

    private class BackupAbort(val error: AppError) :
        RuntimeException(null, null, false, false)

    private class PlaintextVerifierOutputStream(destination: OutputStream) :
        FilterOutputStream(destination) {
        private val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
        var byteCount = 0L
            private set

        override fun write(value: Int) {
            out.write(value)
            digest.update(value.toByte())
            byteCount = Math.addExact(byteCount, 1L)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
            digest.update(buffer, offset, length)
            byteCount = Math.addExact(byteCount, length.toLong())
        }

        fun sha256(): String = digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private companion object {
        const val EXPORT_DIRECTORY = "backup-export"
        const val RESTORE_DIRECTORY = "backup-restore"
        const val ARCHIVE_FILE = "archive.vnb"
        const val CHECKSUMS_FILE = "checksums.json"
        const val DATABASE_PLAINTEXT_FILE = "database.json"
        const val OPERATION_RESTORE = "restore_backup"
        const val OPERATION_RESTORE_STAGE = "stage_backup_restore"
        const val OPERATION_EXPORT = "export_backup"
        const val SYNC_SCHEDULER_UNAVAILABLE = "scheduler_unavailable"
        const val WRITE_MODE = "w"
        const val TRUNCATE_MODE = "wt"
        const val BUFFER_BYTES = 64 * 1024
        const val ABANDONED_AGE_MILLIS = 60L * 60L * 1_000L
        const val MAX_ENTRY_NAME_LENGTH = 128
        const val MAX_COMPRESSION_RATIO = 4L
        const val COMPRESSION_ALLOWANCE_BYTES = 1L * 1024L * 1024L
        const val ZIP_EPOCH_MILLIS = 315_532_800_000L
        const val SHA256_ALGORITHM = "SHA-256"
        val ATTACHMENT_ENTRY_PATTERN = Regex("attachments/[0-9]{8}\\.bin")
    }
}
