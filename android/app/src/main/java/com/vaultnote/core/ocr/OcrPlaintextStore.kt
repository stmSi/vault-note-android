package com.vaultnote.core.ocr

import android.content.Context
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.encryption.EncryptedFilePurpose
import com.vaultnote.core.files.AttachmentFileManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.withContext

class OcrPlaintextLease internal constructor(val file: File) : AutoCloseable {
    override fun close() {
        if (file.exists()) file.delete()
    }
}

fun interface OcrPlaintextStore {
    suspend fun prepare(
        attachmentId: String,
        encryptedRelativePath: String,
    ): RepositoryResult<OcrPlaintextLease>
}

/** Creates a private, short-lived plaintext lease only after the encrypted file authenticates. */
class AndroidOcrPlaintextStore(
    context: Context,
    private val fileManager: AttachmentFileManager,
    private val dispatchers: DispatcherProvider,
    private val isContentAccessAllowed: () -> Boolean,
) : OcrPlaintextStore {
    private val directory = File(context.applicationContext.cacheDir, DIRECTORY)

    override suspend fun prepare(
        attachmentId: String,
        encryptedRelativePath: String,
    ): RepositoryResult<OcrPlaintextLease> = withContext(dispatchers.io) {
        if (!isContentAccessAllowed()) {
            return@withContext RepositoryResult.Failure(AppError.PermissionDenied)
        }
        if (!directory.exists() && !directory.mkdirs()) {
            return@withContext RepositoryResult.Failure(AppError.InsufficientStorage())
        }
        cleanupStaleFiles()
        val temporary = File(directory, "ocr-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).buffered().use { output ->
                when (
                    val decrypted = fileManager.decryptStored(
                        attachmentId = attachmentId,
                        purpose = EncryptedFilePurpose.ATTACHMENT,
                        relativePath = encryptedRelativePath,
                        output = output,
                    )
                ) {
                    is RepositoryResult.Failure -> {
                        temporary.delete()
                        return@withContext decrypted
                    }
                    is RepositoryResult.Success -> Unit
                }
            }
            if (!isContentAccessAllowed()) {
                temporary.delete()
                RepositoryResult.Failure(AppError.PermissionDenied)
            } else {
                RepositoryResult.Success(OcrPlaintextLease(temporary))
            }
        } catch (cancelled: CancellationException) {
            temporary.delete()
            throw cancelled
        } catch (_: IOException) {
            temporary.delete()
            RepositoryResult.Failure(AppError.InsufficientStorage())
        }
    }

    private fun cleanupStaleFiles() {
        val cutoff = System.currentTimeMillis() - STALE_AGE_MILLIS
        directory.listFiles()?.forEach { candidate ->
            if (candidate.isFile && candidate.name.startsWith("ocr-") && candidate.lastModified() <= cutoff) {
                candidate.delete()
            }
        }
    }

    private companion object {
        const val DIRECTORY = "ocr"
        const val STALE_AGE_MILLIS = 60L * 60L * 1_000L
    }
}
