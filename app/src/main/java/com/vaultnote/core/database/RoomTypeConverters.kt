package com.vaultnote.core.database

import androidx.room.TypeConverter
import com.vaultnote.core.common.model.AttachmentUploadStatus
import com.vaultnote.core.common.model.ItemSyncStatus
import com.vaultnote.core.common.model.OcrState
import com.vaultnote.core.common.model.SyncOperationState
import com.vaultnote.core.common.model.SyncOperationType
import com.vaultnote.core.common.model.VaultItemType

class RoomTypeConverters {
    @TypeConverter
    fun vaultItemTypeToString(value: VaultItemType): String = value.name

    @TypeConverter
    fun stringToVaultItemType(value: String): VaultItemType = enumValueOf(value)

    @TypeConverter
    fun itemSyncStatusToString(value: ItemSyncStatus): String = value.name

    @TypeConverter
    fun stringToItemSyncStatus(value: String): ItemSyncStatus = enumValueOf(value)

    @TypeConverter
    fun attachmentUploadStatusToString(value: AttachmentUploadStatus): String = value.name

    @TypeConverter
    fun stringToAttachmentUploadStatus(value: String): AttachmentUploadStatus = enumValueOf(value)

    @TypeConverter
    fun ocrStateToString(value: OcrState): String = value.name

    @TypeConverter
    fun stringToOcrState(value: String): OcrState = enumValueOf(value)

    @TypeConverter
    fun syncOperationTypeToString(value: SyncOperationType): String = value.name

    @TypeConverter
    fun stringToSyncOperationType(value: String): SyncOperationType = enumValueOf(value)

    @TypeConverter
    fun syncOperationStateToString(value: SyncOperationState): String = value.name

    @TypeConverter
    fun stringToSyncOperationState(value: String): SyncOperationState = enumValueOf(value)
}
