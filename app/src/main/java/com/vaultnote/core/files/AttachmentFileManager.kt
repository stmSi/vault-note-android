package com.vaultnote.core.files

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.DefaultDispatcherProvider
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.RepositoryResult
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

interface AttachmentFileManager {
    fun planAttachmentPaths(attachmentId: String): RepositoryResult<PlannedAttachmentPaths>

    suspend fun inspect(uri: Uri): RepositoryResult<AttachmentPreview>

    suspend fun importAttachment(uri: Uri, attachmentId: String): RepositoryResult<PreparedAttachment>

    suspend fun removePrepared(prepared: PreparedAttachment): RepositoryResult<Unit>

    suspend fun removeStored(
        localRelativePath: String,
        thumbnailRelativePath: String?,
    ): RepositoryResult<Unit>

    fun resolveAttachmentPath(relativePath: String): RepositoryResult<File>

    fun resolveThumbnailPath(relativePath: String): RepositoryResult<File>

    suspend fun cleanupAbandonedFiles(): RepositoryResult<CleanupResult>
}

class AndroidAttachmentFileManager(
    context: Context,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
    private val thumbnailGenerator: ThumbnailGenerator = AndroidThumbnailGenerator(dispatchers),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AttachmentFileManager {
    private val contentResolver: ContentResolver = context.applicationContext.contentResolver
    private val storage = ConfinedAttachmentStorage(File(context.applicationContext.filesDir, VAULT_DIRECTORY))
    private val validator = AttachmentContentValidator()
    private val copier = StreamingFileCopier()

    override fun planAttachmentPaths(
        attachmentId: String,
    ): RepositoryResult<PlannedAttachmentPaths> {
        val attachmentPath = when (val result = storage.attachmentRelativePath(attachmentId)) {
            is RepositoryResult.Success -> result.value
            is RepositoryResult.Failure -> return result
        }
        val thumbnailPath = when (val result = storage.thumbnailRelativePath(attachmentId)) {
            is RepositoryResult.Success -> result.value
            is RepositoryResult.Failure -> return result
        }
        return RepositoryResult.Success(
            PlannedAttachmentPaths(
                localRelativePath = attachmentPath,
                thumbnailRelativePath = thumbnailPath,
            ),
        )
    }

    override suspend fun inspect(uri: Uri): RepositoryResult<AttachmentPreview> =
        withContext(dispatchers.io) { inspectOnIo(uri) }

    override suspend fun importAttachment(
        uri: Uri,
        attachmentId: String,
    ): RepositoryResult<PreparedAttachment> = withContext(dispatchers.io) {
        coroutineContext.ensureActive()
        val preview = when (val result = inspectOnIo(uri)) {
            is RepositoryResult.Success -> result.value
            is RepositoryResult.Failure -> return@withContext result
        }
        when (val directories = storage.ensureDirectories()) {
            is RepositoryResult.Success -> Unit
            is RepositoryResult.Failure -> return@withContext directories
        }
        val relativePath = when (val result = storage.attachmentRelativePath(attachmentId)) {
            is RepositoryResult.Success -> result.value
            is RepositoryResult.Failure -> return@withContext result
        }
        val thumbnailRelativePath = when (val result = storage.thumbnailRelativePath(attachmentId)) {
            is RepositoryResult.Success -> result.value
            is RepositoryResult.Failure -> return@withContext result
        }
        val destination = when (val result = storage.resolveAttachment(relativePath)) {
            is RepositoryResult.Success -> result.value
            is RepositoryResult.Failure -> return@withContext result
        }
        val thumbnailDestination = when (val result = storage.resolveThumbnail(thumbnailRelativePath)) {
            is RepositoryResult.Success -> result.value
            is RepositoryResult.Failure -> return@withContext result
        }
        if (destination.exists() || thumbnailDestination.exists()) {
            return@withContext RepositoryResult.Failure(
                AppError.InvalidInput(field = "attachment_id", reason = "already_exists"),
            )
        }

        val temporary = storage.newAttachmentTemporaryFile()
        var attachmentCommitted = false
        var thumbnailCreated = false
        try {
            val copied = contentResolver.openInputStream(uri)?.use { input ->
                copier.copy(
                    input = input,
                    temporaryFile = temporary,
                    storageDirectory = storage.attachmentsDirectory,
                    declaredSize = preview.declaredSize,
                )
            } ?: return@withContext RepositoryResult.Failure(AppError.CorruptedFile)
            val copy = when (copied) {
                is RepositoryResult.Success -> copied.value
                is RepositoryResult.Failure -> return@withContext copied
            }

            val validated = when (
                val result = validator.validateStored(
                    file = temporary,
                    filename = preview.originalFilename,
                    claimedMimeType = preview.mimeType,
                )
            ) {
                is RepositoryResult.Success -> result.value
                is RepositoryResult.Failure -> return@withContext result
            }
            coroutineContext.ensureActive()
            val storedMetadata = probeStoredMetadata(temporary, validated.format)
                ?: return@withContext RepositoryResult.Failure(AppError.CorruptedFile)

            var thumbnailWarning: AppError? = null
            val generatedThumbnail = when (
                val result = thumbnailGenerator.generate(
                    source = temporary,
                    mimeType = validated.format.canonicalMimeType,
                    destination = thumbnailDestination,
                )
            ) {
                is RepositoryResult.Success -> {
                    thumbnailCreated = result.value != null
                    result.value
                }
                is RepositoryResult.Failure -> {
                    thumbnailWarning = result.error
                    null
                }
            }
            coroutineContext.ensureActive()
            moveAtomically(temporary, destination)
            attachmentCommitted = true

            RepositoryResult.Success(
                value = PreparedAttachment(
                    attachmentId = attachmentId,
                    originalFilename = preview.originalFilename,
                    mimeType = validated.format.canonicalMimeType,
                    fileSize = copy.byteCount,
                    sha256Checksum = copy.sha256,
                    localRelativePath = relativePath,
                    thumbnailRelativePath = if (generatedThumbnail != null) thumbnailRelativePath else null,
                    encryptionFormatVersion = ATTACHMENT_ENCRYPTION_FORMAT_NONE,
                    format = validated.format,
                    imageWidth = storedMetadata.imageWidth,
                    imageHeight = storedMetadata.imageHeight,
                    pdfPageCount = storedMetadata.pdfPageCount,
                ),
                warning = thumbnailWarning,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            RepositoryResult.Failure(AppError.PermissionDenied)
        } catch (_: FileNotFoundException) {
            RepositoryResult.Failure(AppError.PermissionDenied)
        } catch (_: IOException) {
            RepositoryResult.Failure(AppError.CorruptedFile)
        } catch (_: IllegalArgumentException) {
            RepositoryResult.Failure(AppError.CorruptedFile)
        } catch (_: IllegalStateException) {
            RepositoryResult.Failure(AppError.CorruptedFile)
        } finally {
            temporary.delete()
            if (!attachmentCommitted) destination.delete()
            if (!attachmentCommitted && thumbnailCreated) thumbnailDestination.delete()
        }
    }

    override suspend fun removePrepared(prepared: PreparedAttachment): RepositoryResult<Unit> =
        removeStored(prepared.localRelativePath, prepared.thumbnailRelativePath)

    override suspend fun removeStored(
        localRelativePath: String,
        thumbnailRelativePath: String?,
    ): RepositoryResult<Unit> = withContext(dispatchers.io) {
        val attachment = when (val result = storage.resolveAttachment(localRelativePath)) {
            is RepositoryResult.Success -> result.value
            is RepositoryResult.Failure -> return@withContext result
        }
        val thumbnail = when {
            thumbnailRelativePath == null -> null
            else -> when (val result = storage.resolveThumbnail(thumbnailRelativePath)) {
                is RepositoryResult.Success -> result.value
                is RepositoryResult.Failure -> return@withContext result
            }
        }
        try {
            if (attachment.exists() && !attachment.delete()) {
                return@withContext RepositoryResult.Failure(AppError.PermissionDenied)
            }
            if (thumbnail != null && thumbnail.exists() && !thumbnail.delete()) {
                return@withContext RepositoryResult.Failure(AppError.PermissionDenied)
            }
            RepositoryResult.Success(Unit)
        } catch (_: SecurityException) {
            RepositoryResult.Failure(AppError.PermissionDenied)
        }
    }

    override fun resolveAttachmentPath(relativePath: String): RepositoryResult<File> =
        storage.resolveAttachment(relativePath)

    override fun resolveThumbnailPath(relativePath: String): RepositoryResult<File> =
        storage.resolveThumbnail(relativePath)

    override suspend fun cleanupAbandonedFiles(): RepositoryResult<CleanupResult> =
        withContext(dispatchers.io) {
            when (val directories = storage.ensureDirectories()) {
                is RepositoryResult.Success -> Unit
                is RepositoryResult.Failure -> return@withContext directories
            }
            val cutoff = nowMillis() - ABANDONED_FILE_AGE_MILLIS
            var deleted = 0
            var failed = 0
            for (directory in listOf(storage.attachmentsDirectory, storage.thumbnailsDirectory)) {
                val candidates = directory.listFiles()
                    ?: return@withContext RepositoryResult.Failure(AppError.PermissionDenied)
                for (candidate in candidates) {
                    coroutineContext.ensureActive()
                    if (
                        candidate.isFile &&
                        candidate.name.startsWith(PENDING_FILE_PREFIX) &&
                        candidate.lastModified() <= cutoff
                    ) {
                        if (candidate.delete()) deleted += 1 else failed += 1
                    }
                }
            }
            RepositoryResult.Success(CleanupResult(deletedFiles = deleted, failedDeletions = failed))
        }

    private fun inspectOnIo(uri: Uri): RepositoryResult<AttachmentPreview> {
        return try {
            if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
                return RepositoryResult.Failure(AppError.UnsupportedFile)
            }
            val metadata = queryExternalMetadata(uri)
            if (metadata.declaredSize != null && metadata.declaredSize > MAX_ATTACHMENT_BYTES) {
                return RepositoryResult.Failure(AppError.FileTooLarge(MAX_ATTACHMENT_BYTES))
            }
            val sanitizedName = when (val result = FilenameSanitizer.sanitize(metadata.displayName)) {
                is RepositoryResult.Success -> result.value
                is RepositoryResult.Failure -> return result
            }
            val sample = contentResolver.openInputStream(uri)?.use(validator::readContentSample)
                ?: return RepositoryResult.Failure(AppError.CorruptedFile)
            val validated = when (
                val result = validator.validatePreview(
                    filename = sanitizedName,
                    claimedMimeType = metadata.claimedMimeType,
                    sample = sample,
                )
            ) {
                is RepositoryResult.Success -> result.value
                is RepositoryResult.Failure -> return result
            }
            val metadataProbe = probeUriMetadata(uri, validated.format)
            RepositoryResult.Success(
                AttachmentPreview(
                    originalFilename = sanitizedName,
                    mimeType = validated.format.canonicalMimeType,
                    declaredSize = metadata.declaredSize,
                    format = validated.format,
                    validationLevel = validated.validationLevel,
                    imageWidth = metadataProbe.imageWidth,
                    imageHeight = metadataProbe.imageHeight,
                    pdfPageCount = metadataProbe.pdfPageCount,
                ),
            )
        } catch (_: SecurityException) {
            RepositoryResult.Failure(AppError.PermissionDenied)
        } catch (_: FileNotFoundException) {
            RepositoryResult.Failure(AppError.PermissionDenied)
        } catch (_: IOException) {
            RepositoryResult.Failure(AppError.CorruptedFile)
        } catch (_: IllegalArgumentException) {
            RepositoryResult.Failure(AppError.CorruptedFile)
        } catch (_: IllegalStateException) {
            RepositoryResult.Failure(AppError.CorruptedFile)
        }
    }

    private fun queryExternalMetadata(uri: Uri): ExternalMetadata {
        var displayName: String? = null
        var declaredSize: Long? = null
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    declaredSize = cursor.getLong(sizeIndex).takeIf { it >= 0L }
                }
            }
        }
        val safeCandidate = displayName ?: uri.lastPathSegment
            ?: throw IllegalArgumentException("Missing display name")
        return ExternalMetadata(
            displayName = safeCandidate,
            declaredSize = declaredSize,
            claimedMimeType = contentResolver.getType(uri),
        )
    }

    private fun probeUriMetadata(uri: Uri, format: AttachmentFormat): ProbedMetadata = when (format.category) {
        AttachmentCategory.IMAGE -> {
            val bounds = try {
                contentResolver.openInputStream(uri)?.use(::readImageBounds)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
            val orientation = try {
                contentResolver.openInputStream(uri)?.use(::readExifOrientation)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
            val oriented = bounds?.let { dimensions ->
                orientedDimensions(
                    dimensions.first,
                    dimensions.second,
                    orientation ?: ExifInterface.ORIENTATION_NORMAL,
                )
            }
            ProbedMetadata(oriented?.first, oriented?.second, null)
        }
        AttachmentCategory.PDF -> {
            val pageCount = try {
                contentResolver.openFileDescriptor(uri, "r")?.use(::readPdfPageCount)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
            ProbedMetadata(null, null, pageCount)
        }
        AttachmentCategory.TEXT,
        AttachmentCategory.DOCUMENT,
        -> ProbedMetadata(null, null, null)
    }

    private fun probeStoredMetadata(file: File, format: AttachmentFormat): ProbedMetadata? = when (format.category) {
        AttachmentCategory.IMAGE -> {
            val bounds = file.inputStream().buffered().use(::readImageBounds) ?: return null
            val oriented = orientedDimensions(bounds.first, bounds.second, readExifOrientation(file))
            ProbedMetadata(oriented.first, oriented.second, null)
        }
        AttachmentCategory.PDF -> {
            val pageCount = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use(::readPdfPageCount)
                ?: return null
            ProbedMetadata(null, null, pageCount)
        }
        AttachmentCategory.TEXT,
        AttachmentCategory.DOCUMENT,
        -> ProbedMetadata(null, null, null)
    }

    private fun readImageBounds(input: java.io.InputStream): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(input, null, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
    }

    private fun readPdfPageCount(descriptor: ParcelFileDescriptor): Int? =
        PdfRenderer(descriptor).use { renderer -> renderer.pageCount.takeIf { it > 0 } }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            if (destination.exists() || !source.renameTo(destination)) {
                throw IOException("Atomic rename failed")
            }
        }
    }

    private data class ExternalMetadata(
        val displayName: String,
        val declaredSize: Long?,
        val claimedMimeType: String?,
    )

    private data class ProbedMetadata(
        val imageWidth: Int?,
        val imageHeight: Int?,
        val pdfPageCount: Int?,
    )

    companion object {
        private const val VAULT_DIRECTORY = "vault"
        private const val PENDING_FILE_PREFIX = ".pending-"
        private const val ABANDONED_FILE_AGE_MILLIS = 24L * 60L * 60L * 1000L
    }
}
