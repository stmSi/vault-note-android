package com.vaultnote.core.files

import android.content.Context
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.encryption.EncryptedFilePurpose
import com.vaultnote.core.encryption.EncryptionContext
import com.vaultnote.core.encryption.EncryptionService
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class StagedRestoredAttachment(
    val attachmentId: String,
    val pendingFile: File,
    val destinationFile: File,
    val relativePath: String,
) {
    override fun toString(): String = "StagedRestoredAttachment(redacted)"
}

internal interface RestoredAttachmentStorage {
    suspend fun stage(
        plaintext: File,
        attachmentId: String,
        filename: String,
        mimeType: String,
        expectedSize: Long,
        expectedSha256: String,
    ): RepositoryResult<StagedRestoredAttachment>

    fun commit(staged: StagedRestoredAttachment): RepositoryResult<Unit>
    fun discard(staged: StagedRestoredAttachment)
}

/**
 * Validates restored plaintext, then re-encrypts it under the destination device's Keystore key.
 * Pending ciphertext stays on the same filesystem as its final path so commit is an atomic rename.
 */
internal class RestoredAttachmentStore(
    context: Context,
    private val encryptionService: EncryptionService,
    private val dispatchers: DispatcherProvider,
) : RestoredAttachmentStorage {
    private val storage = ConfinedAttachmentStorage(
        File(context.applicationContext.filesDir, VAULT_DIRECTORY),
    )
    private val validator = AttachmentContentValidator()

    override suspend fun stage(
        plaintext: File,
        attachmentId: String,
        filename: String,
        mimeType: String,
        expectedSize: Long,
        expectedSha256: String,
    ): RepositoryResult<StagedRestoredAttachment> = withContext(dispatchers.io) {
        if (!plaintext.isFile || plaintext.length() != expectedSize) {
            return@withContext RepositoryResult.Failure(AppError.CorruptedFile)
        }
        val actualChecksum = when (val result = checksum(plaintext)) {
            is RepositoryResult.Success -> result.value
            is RepositoryResult.Failure -> return@withContext result
        }
        if (actualChecksum != expectedSha256) {
            return@withContext RepositoryResult.Failure(AppError.CorruptedFile)
        }
        when (val validation = validator.validateStored(plaintext, filename, mimeType)) {
            is RepositoryResult.Failure -> return@withContext validation
            is RepositoryResult.Success -> {
                if (validation.value.format.canonicalMimeType != mimeType) {
                    return@withContext RepositoryResult.Failure(AppError.UnsupportedFile)
                }
            }
        }
        when (val directories = storage.ensureDirectories()) {
            is RepositoryResult.Failure -> return@withContext directories
            is RepositoryResult.Success -> Unit
        }
        val relativePath = when (val planned = storage.attachmentRelativePath(attachmentId)) {
            is RepositoryResult.Success -> planned.value
            is RepositoryResult.Failure -> return@withContext planned
        }
        val destination = when (val resolved = storage.resolveAttachment(relativePath)) {
            is RepositoryResult.Success -> resolved.value
            is RepositoryResult.Failure -> return@withContext resolved
        }
        if (destination.exists()) {
            return@withContext RepositoryResult.Failure(
                AppError.InvalidInput("restore_attachment_id", "destination_exists"),
            )
        }
        val pending = File(
            storage.attachmentsDirectory,
            ".pending-restore-${UUID.randomUUID()}.tmp",
        )
        when (
            val encrypted = encryptionService.encryptFileAtomically(
                plaintext = plaintext,
                destination = pending,
                context = EncryptionContext(attachmentId, EncryptedFilePurpose.ATTACHMENT),
                replaceExisting = false,
            )
        ) {
            is RepositoryResult.Failure -> {
                pending.delete()
                return@withContext encrypted
            }
            is RepositoryResult.Success -> Unit
        }
        RepositoryResult.Success(
            StagedRestoredAttachment(
                attachmentId = attachmentId,
                pendingFile = pending,
                destinationFile = destination,
                relativePath = relativePath,
            ),
        )
    }

    override fun commit(staged: StagedRestoredAttachment): RepositoryResult<Unit> = try {
        if (!staged.pendingFile.isFile || staged.destinationFile.exists()) {
            RepositoryResult.Failure(AppError.CorruptedFile)
        } else {
            try {
                Files.move(
                    staged.pendingFile.toPath(),
                    staged.destinationFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staged.pendingFile.toPath(), staged.destinationFile.toPath())
            }
            RepositoryResult.Success(Unit)
        }
    } catch (_: IOException) {
        RepositoryResult.Failure(AppError.InsufficientStorage())
    } catch (_: SecurityException) {
        RepositoryResult.Failure(AppError.PermissionDenied)
    }

    override fun discard(staged: StagedRestoredAttachment) {
        staged.pendingFile.delete()
        staged.destinationFile.delete()
    }

    private suspend fun checksum(file: File): RepositoryResult<String> = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_BYTES).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        RepositoryResult.Success(
            digest.digest().joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        RepositoryResult.Failure(AppError.CorruptedFile)
    } catch (_: SecurityException) {
        RepositoryResult.Failure(AppError.PermissionDenied)
    }

    private companion object {
        const val VAULT_DIRECTORY = "vault"
        const val BUFFER_BYTES = 64 * 1024
    }
}
