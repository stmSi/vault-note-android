package com.vaultnote.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.vaultnote.core.common.model.AttachmentUploadStatus
import com.vaultnote.core.common.model.OcrState

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = VaultItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_item_id"],
            onUpdate = ForeignKey.NO_ACTION,
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["parent_item_id"], name = "index_attachments_parent_item_id"),
        Index(value = ["sha256_checksum"], name = "index_attachments_sha256_checksum"),
        Index(value = ["upload_status"], name = "index_attachments_upload_status"),
        Index(value = ["created_at", "id"], name = "index_attachments_created_at_id"),
    ],
)
data class AttachmentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "parent_item_id")
    val parentItemId: String,
    @ColumnInfo(name = "original_filename")
    val originalFilename: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "file_size")
    val fileSize: Long,
    @ColumnInfo(name = "image_width")
    val imageWidth: Int?,
    @ColumnInfo(name = "image_height")
    val imageHeight: Int?,
    @ColumnInfo(name = "pdf_page_count")
    val pdfPageCount: Int?,
    @ColumnInfo(name = "sha256_checksum")
    val sha256Checksum: String,
    @ColumnInfo(name = "local_encrypted_path")
    val localEncryptedPath: String,
    @ColumnInfo(name = "remote_path")
    val remotePath: String?,
    @ColumnInfo(name = "thumbnail_path")
    val thumbnailPath: String?,
    @ColumnInfo(name = "encryption_format_version")
    val encryptionFormatVersion: Int,
    @ColumnInfo(name = "upload_status")
    val uploadStatus: AttachmentUploadStatus,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "ocr_state")
    val ocrState: OcrState,
    @ColumnInfo(name = "extracted_ocr_text")
    val extractedOcrText: String,
    @ColumnInfo(name = "ocr_source_checksum")
    val ocrSourceChecksum: String?,
    @ColumnInfo(name = "ocr_failure_code")
    val ocrFailureCode: String?,
    @ColumnInfo(name = "ocr_updated_at")
    val ocrUpdatedAt: Long?,
)
