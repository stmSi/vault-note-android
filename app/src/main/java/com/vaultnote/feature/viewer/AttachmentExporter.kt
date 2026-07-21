package com.vaultnote.feature.viewer

import android.content.ContentResolver
import android.net.Uri
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.encryption.EncryptedFilePurpose
import com.vaultnote.core.security.ExternalAttachmentGrantRegistry
import com.vaultnote.core.security.SecureAttachmentContentSource
import com.vaultnote.core.security.SecureAttachmentUriFactory
import java.io.BufferedOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.withContext

class PreparedAttachmentExport internal constructor(
    internal val attachmentId: String,
    internal val accessToken: String,
) {
    override fun toString(): String = "PreparedAttachmentExport(redacted)"
}

/** Streams authenticated plaintext directly to a user-selected SAF destination. */
interface AttachmentExporter {
    fun prepare(attachmentId: String): RepositoryResult<PreparedAttachmentExport>

    suspend fun save(
        prepared: PreparedAttachmentExport,
        destination: Uri,
    ): RepositoryResult<Unit>

    fun cancel(prepared: PreparedAttachmentExport)
}

internal class AndroidAttachmentExporter(
    private val contentResolver: ContentResolver,
    private val contentSource: SecureAttachmentContentSource,
    private val externalGrants: ExternalAttachmentGrantRegistry,
    private val dispatchers: DispatcherProvider,
) : AttachmentExporter {
    override fun prepare(attachmentId: String): RepositoryResult<PreparedAttachmentExport> = try {
        if (!SecureAttachmentUriFactory.SAFE_ID.matches(attachmentId)) {
            RepositoryResult.Failure(AppError.CorruptedFile)
        } else {
            RepositoryResult.Success(
                PreparedAttachmentExport(
                    attachmentId = attachmentId,
                    accessToken = externalGrants.issue(attachmentId),
                ),
            )
        }
    } catch (_: IllegalArgumentException) {
        RepositoryResult.Failure(AppError.CorruptedFile)
    } catch (_: SecurityException) {
        RepositoryResult.Failure(AppError.PermissionDenied)
    }

    override suspend fun save(
        prepared: PreparedAttachmentExport,
        destination: Uri,
    ): RepositoryResult<Unit> = withContext(dispatchers.io) {
        var completed = false
        try {
            if (destination.scheme != ContentResolver.SCHEME_CONTENT) {
                return@withContext RepositoryResult.Failure(AppError.PermissionDenied)
            }
            val rawOutput = contentResolver.openOutputStream(destination, WRITE_MODE)
                ?: return@withContext RepositoryResult.Failure(AppError.PermissionDenied)
            rawOutput.use { output ->
                val buffered = BufferedOutputStream(output, BUFFER_SIZE_BYTES)
                when (
                    val result = contentSource.writeVerifiedContent(
                        attachmentId = prepared.attachmentId,
                        purpose = EncryptedFilePurpose.ATTACHMENT,
                        externalAccessToken = prepared.accessToken,
                        output = buffered,
                    )
                ) {
                    is RepositoryResult.Failure -> return@withContext result
                    is RepositoryResult.Success -> Unit
                }
                buffered.flush()
            }
            completed = true
            RepositoryResult.Success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: FileNotFoundException) {
            RepositoryResult.Failure(AppError.PermissionDenied)
        } catch (_: SecurityException) {
            RepositoryResult.Failure(AppError.PermissionDenied)
        } catch (_: IOException) {
            RepositoryResult.Failure(AppError.InsufficientStorage())
        } finally {
            externalGrants.revoke(prepared.accessToken)
            if (!completed) discardPartialDestination(destination)
        }
    }

    override fun cancel(prepared: PreparedAttachmentExport) {
        externalGrants.revoke(prepared.accessToken)
    }

    private fun discardPartialDestination(destination: Uri) {
        val deleted = runCatching {
            contentResolver.delete(destination, null, null) > 0
        }.getOrDefault(false)
        if (!deleted) {
            runCatching {
                contentResolver.openOutputStream(destination, TRUNCATE_MODE)?.use { }
            }
        }
    }

    private companion object {
        const val WRITE_MODE = "w"
        const val TRUNCATE_MODE = "wt"
        const val BUFFER_SIZE_BYTES = 64 * 1024
    }
}
