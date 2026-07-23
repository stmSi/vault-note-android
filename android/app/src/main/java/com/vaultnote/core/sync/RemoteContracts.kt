package com.vaultnote.core.sync

import com.vaultnote.core.common.model.VaultItemColor
import com.vaultnote.core.common.model.VaultItemType
import java.io.File

enum class AuthenticationState {
    AUTHENTICATED,
    EXPIRED,
}

fun interface AuthProvider {
    suspend fun authenticationState(): AuthenticationState
}

data class RemoteAttachmentReference(
    val id: String,
    val remotePath: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val plaintextSha256: String,
    val encryptionFormatVersion: Int,
)

data class RemoteItemMetadata(
    val id: String,
    val type: VaultItemType,
    val title: String,
    val body: String,
    val ocrText: String,
    val color: VaultItemColor,
    val isPinned: Boolean,
    val isFavorite: Boolean,
    val isArchived: Boolean,
    val sortPosition: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val clientRevision: Long,
    val tags: List<String>,
    val attachments: List<RemoteAttachmentReference>,
)

data class RemoteItemVersion(
    val metadata: RemoteItemMetadata,
    val serverRevision: Long,
    val versionToken: String,
)

enum class RemoteErrorCode(val retryable: Boolean) {
    NETWORK_UNAVAILABLE(true),
    SERVER_UNAVAILABLE(true),
    AUTHENTICATION_EXPIRED(false),
    INVALID_REQUEST(false),
    QUOTA_EXCEEDED(false),
    NOT_FOUND(false),
    CORRUPTED_UPLOAD(false),
}

sealed interface RemoteMutationResult {
    data class Applied(
        val serverRevision: Long,
        val versionToken: String,
    ) : RemoteMutationResult

    data class Conflict(val remote: RemoteItemVersion?) : RemoteMutationResult
    data class Failure(val code: RemoteErrorCode) : RemoteMutationResult
}

sealed interface RemoteChange {
    val serverRevision: Long

    data class Upsert(val item: RemoteItemVersion) : RemoteChange {
        override val serverRevision: Long = item.serverRevision
    }

    data class Delete(
        val itemId: String,
        override val serverRevision: Long,
        val versionToken: String,
    ) : RemoteChange
}

data class RemoteChangePage(
    val changes: List<RemoteChange>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

sealed interface RemotePullResult {
    data class Success(val page: RemoteChangePage) : RemotePullResult
    data class Failure(val code: RemoteErrorCode) : RemotePullResult
}

interface SyncApi {
    suspend fun upsertItem(
        operationId: String,
        item: RemoteItemMetadata,
        expectedVersionToken: String?,
    ): RemoteMutationResult

    suspend fun deleteItem(
        operationId: String,
        itemId: String,
        expectedVersionToken: String?,
    ): RemoteMutationResult

    suspend fun pullChanges(cursor: String?, limit: Int): RemotePullResult
}

sealed interface RemoteFileResult {
    data class Uploaded(val remotePath: String) : RemoteFileResult
    data object Deleted : RemoteFileResult
    data class Failure(val code: RemoteErrorCode) : RemoteFileResult
}

/** Transfers already-encrypted attachment envelopes. Implementations must stream [source]. */
interface RemoteFileStore {
    suspend fun uploadEncrypted(
        operationId: String,
        attachmentId: String,
        plaintextSha256: String,
        source: File,
    ): RemoteFileResult

    suspend fun verifyUpload(remotePath: String, plaintextSha256: String): Boolean

    suspend fun delete(operationId: String, attachmentId: String): RemoteFileResult
}
