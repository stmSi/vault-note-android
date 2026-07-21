package com.vaultnote.core.security

import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.database.VaultDatabase
import com.vaultnote.core.encryption.CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION
import com.vaultnote.core.encryption.EncryptedFilePurpose
import com.vaultnote.core.files.AttachmentFileManager
import java.io.OutputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.withContext

interface SecureAttachmentContentSource {
    suspend fun writeVerifiedContent(
        attachmentId: String,
        purpose: EncryptedFilePurpose,
        externalAccessToken: String?,
        output: OutputStream,
    ): RepositoryResult<Unit>
}

class RoomSecureAttachmentContentSource(
    database: VaultDatabase,
    private val fileManager: AttachmentFileManager,
    private val lockManager: VaultLockManager,
    private val externalGrants: ExternalAttachmentGrantRegistry,
    private val dispatchers: DispatcherProvider,
) : SecureAttachmentContentSource {
    private val attachments = database.attachmentDao()

    override suspend fun writeVerifiedContent(
        attachmentId: String,
        purpose: EncryptedFilePurpose,
        externalAccessToken: String?,
        output: OutputStream,
    ): RepositoryResult<Unit> = withContext(dispatchers.io) {
        if (!SecureAttachmentUriFactory.SAFE_ID.matches(attachmentId)) {
            return@withContext RepositoryResult.Failure(AppError.CorruptedFile)
        }
        val externalAccess = purpose == EncryptedFilePurpose.ATTACHMENT &&
            externalGrants.consume(attachmentId, externalAccessToken)
        if (!externalAccess && !lockManager.isContentAccessAllowed()) {
            return@withContext RepositoryResult.Failure(AppError.AuthenticationExpired)
        }
        val attachment = try {
            attachments.getById(attachmentId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return@withContext RepositoryResult.Failure(
                AppError.DatabaseFailure(OPERATION_OPEN_SECURE_CONTENT, failure),
            )
        } ?: return@withContext RepositoryResult.Failure(AppError.CorruptedFile)
        if (attachment.encryptionFormatVersion != CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION) {
            return@withContext RepositoryResult.Failure(AppError.DecryptionFailure())
        }
        val relativePath = when (purpose) {
            EncryptedFilePurpose.ATTACHMENT -> attachment.localEncryptedPath
            EncryptedFilePurpose.THUMBNAIL -> attachment.thumbnailPath
                ?: return@withContext RepositoryResult.Failure(AppError.CorruptedFile)
        }
        fileManager.decryptStored(
            attachmentId = attachmentId,
            purpose = purpose,
            relativePath = relativePath,
            output = output,
        )
    }

    private companion object {
        const val OPERATION_OPEN_SECURE_CONTENT = "open_secure_attachment_content"
    }
}
