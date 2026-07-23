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
    suspend fun getMetadata(
        attachmentId: String,
        purpose: EncryptedFilePurpose,
        externalAccessToken: String?,
    ): RepositoryResult<SecureAttachmentMetadata>

    suspend fun writeVerifiedContent(
        attachmentId: String,
        purpose: EncryptedFilePurpose,
        externalAccessToken: String?,
        output: OutputStream,
    ): RepositoryResult<Unit>
}

data class SecureAttachmentMetadata(
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
)

class RoomSecureAttachmentContentSource(
    database: VaultDatabase,
    private val fileManager: AttachmentFileManager,
    private val lockManager: VaultLockManager,
    private val externalGrants: ExternalAttachmentGrantRegistry,
    private val dispatchers: DispatcherProvider,
) : SecureAttachmentContentSource {
    private val attachments = database.attachmentDao()

    override suspend fun getMetadata(
        attachmentId: String,
        purpose: EncryptedFilePurpose,
        externalAccessToken: String?,
    ): RepositoryResult<SecureAttachmentMetadata> = withContext(dispatchers.io) {
        if (!SecureAttachmentUriFactory.SAFE_ID.matches(attachmentId)) {
            return@withContext RepositoryResult.Failure(AppError.CorruptedFile)
        }
        if (!isAccessAllowed(attachmentId, purpose, externalAccessToken, consumeRead = false)) {
            return@withContext RepositoryResult.Failure(AppError.AuthenticationExpired)
        }
        val attachment = getAttachment(attachmentId)
        if (attachment is RepositoryResult.Failure) return@withContext attachment
        val entity = (attachment as RepositoryResult.Success).value
        if (entity.encryptionFormatVersion != CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION) {
            return@withContext RepositoryResult.Failure(AppError.DecryptionFailure())
        }
        when (purpose) {
            EncryptedFilePurpose.ATTACHMENT -> RepositoryResult.Success(
                SecureAttachmentMetadata(
                    displayName = entity.originalFilename,
                    mimeType = entity.mimeType,
                    sizeBytes = entity.fileSize,
                ),
            )
            EncryptedFilePurpose.THUMBNAIL -> {
                if (entity.thumbnailPath == null) {
                    RepositoryResult.Failure(AppError.CorruptedFile)
                } else {
                    RepositoryResult.Success(
                        SecureAttachmentMetadata(
                            displayName = "${entity.id}.webp",
                            mimeType = THUMBNAIL_MIME_TYPE,
                            sizeBytes = null,
                        ),
                    )
                }
            }
        }
    }

    override suspend fun writeVerifiedContent(
        attachmentId: String,
        purpose: EncryptedFilePurpose,
        externalAccessToken: String?,
        output: OutputStream,
    ): RepositoryResult<Unit> = withContext(dispatchers.io) {
        if (!SecureAttachmentUriFactory.SAFE_ID.matches(attachmentId)) {
            return@withContext RepositoryResult.Failure(AppError.CorruptedFile)
        }
        if (!isAccessAllowed(attachmentId, purpose, externalAccessToken, consumeRead = true)) {
            return@withContext RepositoryResult.Failure(AppError.AuthenticationExpired)
        }
        val attachmentResult = getAttachment(attachmentId)
        if (attachmentResult is RepositoryResult.Failure) return@withContext attachmentResult
        val attachment = (attachmentResult as RepositoryResult.Success).value
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

    private fun isAccessAllowed(
        attachmentId: String,
        purpose: EncryptedFilePurpose,
        externalAccessToken: String?,
        consumeRead: Boolean,
    ): Boolean {
        if (externalAccessToken == null) return lockManager.isContentAccessAllowed()
        if (purpose != EncryptedFilePurpose.ATTACHMENT) return false
        return if (consumeRead) {
            externalGrants.acquireContentRead(attachmentId, externalAccessToken)
        } else {
            externalGrants.validate(attachmentId, externalAccessToken)
        }
    }

    private suspend fun getAttachment(
        attachmentId: String,
    ): RepositoryResult<com.vaultnote.core.database.entity.AttachmentEntity> = try {
        attachments.getById(attachmentId)?.let { entity -> RepositoryResult.Success(entity) }
            ?: RepositoryResult.Failure(AppError.CorruptedFile)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        RepositoryResult.Failure(
            AppError.DatabaseFailure(OPERATION_OPEN_SECURE_CONTENT, failure),
        )
    }

    private companion object {
        const val OPERATION_OPEN_SECURE_CONTENT = "open_secure_attachment_content"
        const val THUMBNAIL_MIME_TYPE = "image/webp"
    }
}
