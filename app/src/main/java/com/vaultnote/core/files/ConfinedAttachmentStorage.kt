package com.vaultnote.core.files

import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.RepositoryResult
import java.io.File
import java.io.IOException
import java.util.UUID

internal class ConfinedAttachmentStorage(private val vaultRoot: File) {
    val attachmentsDirectory: File = File(vaultRoot, ATTACHMENTS_DIRECTORY)
    val thumbnailsDirectory: File = File(vaultRoot, THUMBNAILS_DIRECTORY)

    fun ensureDirectories(): RepositoryResult<Unit> {
        return try {
            if (!attachmentsDirectory.isDirectory && !attachmentsDirectory.mkdirs()) {
                return RepositoryResult.Failure(AppError.InsufficientStorage())
            }
            if (!thumbnailsDirectory.isDirectory && !thumbnailsDirectory.mkdirs()) {
                return RepositoryResult.Failure(AppError.InsufficientStorage())
            }
            RepositoryResult.Success(Unit)
        } catch (_: SecurityException) {
            RepositoryResult.Failure(AppError.PermissionDenied)
        }
    }

    fun attachmentRelativePath(attachmentId: String): RepositoryResult<String> =
        safeId(attachmentId)?.let { RepositoryResult.Success("$ATTACHMENTS_DIRECTORY/$it.bin") }
            ?: invalidId()

    fun thumbnailRelativePath(attachmentId: String): RepositoryResult<String> =
        safeId(attachmentId)?.let { RepositoryResult.Success("$THUMBNAILS_DIRECTORY/$it.webp") }
            ?: invalidId()

    fun newAttachmentTemporaryFile(): File =
        File(attachmentsDirectory, ".pending-attachment-${UUID.randomUUID()}.tmp")

    fun resolveAttachment(relativePath: String): RepositoryResult<File> =
        resolve(relativePath, ATTACHMENTS_DIRECTORY, attachmentsDirectory)

    fun resolveThumbnail(relativePath: String): RepositoryResult<File> =
        resolve(relativePath, THUMBNAILS_DIRECTORY, thumbnailsDirectory)

    private fun resolve(
        relativePath: String,
        expectedDirectoryName: String,
        expectedDirectory: File,
    ): RepositoryResult<File> {
        if (
            relativePath.isBlank() ||
            relativePath.indexOf('\u0000') >= 0 ||
            relativePath.indexOf('\\') >= 0 ||
            File(relativePath).isAbsolute
        ) {
            return unsafeStoredPath()
        }
        val segments = relativePath.split('/')
        if (
            segments.size != 2 ||
            segments[0] != expectedDirectoryName ||
            segments[1].isBlank() ||
            segments[1] == "." ||
            segments[1] == ".."
        ) {
            return unsafeStoredPath()
        }

        return try {
            val directory = expectedDirectory.canonicalFile
            val candidate = File(vaultRoot, relativePath).canonicalFile
            val expectedPrefix = directory.path + File.separator
            if (!candidate.path.startsWith(expectedPrefix) || candidate.parentFile != directory) {
                unsafeStoredPath()
            } else {
                RepositoryResult.Success(candidate)
            }
        } catch (_: IOException) {
            RepositoryResult.Failure(AppError.CorruptedFile)
        } catch (_: SecurityException) {
            RepositoryResult.Failure(AppError.PermissionDenied)
        }
    }

    private fun safeId(value: String): String? =
        value.takeIf { it.length in 1..128 && SAFE_ID.matches(it) }

    private fun invalidId(): RepositoryResult.Failure =
        RepositoryResult.Failure(AppError.InvalidInput(field = "attachment_id", reason = "invalid"))

    private fun unsafeStoredPath(): RepositoryResult.Failure =
        RepositoryResult.Failure(AppError.InvalidInput(field = "stored_path", reason = "outside_vault"))

    companion object {
        private const val ATTACHMENTS_DIRECTORY = "attachments"
        private const val THUMBNAILS_DIRECTORY = "thumbnails"
        private val SAFE_ID = Regex("[A-Za-z0-9_-]+")
    }
}
