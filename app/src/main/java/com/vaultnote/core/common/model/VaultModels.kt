package com.vaultnote.core.common.model

enum class VaultItemType {
    NOTE,
    DOCUMENT,
    IMAGE,
    LINK,
}

enum class ItemSyncStatus {
    LOCAL_ONLY,
    PENDING,
    SYNCING,
    SYNCED,
    CONFLICT,
    FAILED,
}

enum class AttachmentUploadStatus {
    LOCAL_ONLY,
    PENDING,
    UPLOADING,
    UPLOADED,
    FAILED_RETRYABLE,
    FAILED_PERMANENT,
}

enum class OcrState {
    NOT_APPLICABLE,
    PENDING,
    PROCESSING,
    COMPLETE,
    FAILED,
}

enum class SyncOperationType {
    UPSERT_ITEM,
    DELETE_ITEM,
    UPLOAD_ATTACHMENT,
    DELETE_ATTACHMENT,
}

enum class SyncOperationState {
    PENDING,
    RUNNING,
    RETRY_WAIT,
    COMPLETED,
    FAILED_PERMANENT,
}

data class VaultTag(
    val id: String,
    val name: String,
)

data class VaultItemSummary(
    val id: String,
    val type: VaultItemType,
    val title: String,
    val bodyPreview: String,
    val isPinned: Boolean,
    val isFavorite: Boolean,
    val isArchived: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val syncStatus: ItemSyncStatus,
    val tags: List<VaultTag>,
)

data class VaultNote(
    val id: String,
    val title: String,
    val body: String,
    val ocrText: String,
    val isPinned: Boolean,
    val isFavorite: Boolean,
    val isArchived: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val localRevision: Long,
    val remoteRevision: Long?,
    val lastSyncedRevision: Long?,
    val serverVersionToken: String?,
    val syncStatus: ItemSyncStatus,
    val deletedAtEpochMillis: Long?,
    val conflictOriginId: String?,
    val tags: List<VaultTag>,
)
