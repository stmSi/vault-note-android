package com.vaultnote.core.files

const val MAX_ATTACHMENT_BYTES: Long = 100L * 1024L * 1024L
const val MAX_ATTACHMENTS_PER_IMPORT: Int = 20
const val MINIMUM_FREE_SPACE_RESERVE_BYTES: Long = 32L * 1024L * 1024L
const val ATTACHMENT_ENCRYPTION_FORMAT_NONE: Int = 0

enum class AttachmentCategory {
    IMAGE,
    PDF,
    TEXT,
    DOCUMENT,
}

enum class AttachmentValidationLevel {
    PRELIMINARY,
    FULL,
}

enum class AttachmentFormat(
    val category: AttachmentCategory,
    val canonicalMimeType: String,
    val extensions: Set<String>,
    internal val acceptedMimeTypes: Set<String>,
) {
    JPEG(
        category = AttachmentCategory.IMAGE,
        canonicalMimeType = "image/jpeg",
        extensions = setOf("jpg", "jpeg"),
        acceptedMimeTypes = setOf("image/jpeg", "image/jpg", "image/pjpeg"),
    ),
    PNG(
        category = AttachmentCategory.IMAGE,
        canonicalMimeType = "image/png",
        extensions = setOf("png"),
        acceptedMimeTypes = setOf("image/png"),
    ),
    GIF(
        category = AttachmentCategory.IMAGE,
        canonicalMimeType = "image/gif",
        extensions = setOf("gif"),
        acceptedMimeTypes = setOf("image/gif"),
    ),
    WEBP(
        category = AttachmentCategory.IMAGE,
        canonicalMimeType = "image/webp",
        extensions = setOf("webp"),
        acceptedMimeTypes = setOf("image/webp"),
    ),
    HEIF(
        category = AttachmentCategory.IMAGE,
        canonicalMimeType = "image/heif",
        extensions = setOf("heif", "heic"),
        acceptedMimeTypes = setOf("image/heif", "image/heic", "image/heif-sequence", "image/heic-sequence"),
    ),
    PDF(
        category = AttachmentCategory.PDF,
        canonicalMimeType = "application/pdf",
        extensions = setOf("pdf"),
        acceptedMimeTypes = setOf("application/pdf"),
    ),
    PLAIN_TEXT(
        category = AttachmentCategory.TEXT,
        canonicalMimeType = "text/plain",
        extensions = setOf("txt", "text", "md", "markdown"),
        acceptedMimeTypes = setOf("text/plain", "text/markdown", "text/x-markdown"),
    ),
    JSON(
        category = AttachmentCategory.TEXT,
        canonicalMimeType = "application/json",
        extensions = setOf("json"),
        acceptedMimeTypes = setOf("application/json", "text/json"),
    ),
    CSV(
        category = AttachmentCategory.TEXT,
        canonicalMimeType = "text/csv",
        extensions = setOf("csv"),
        acceptedMimeTypes = setOf("text/csv", "application/csv", "text/comma-separated-values"),
    ),
    RTF(
        category = AttachmentCategory.TEXT,
        canonicalMimeType = "application/rtf",
        extensions = setOf("rtf"),
        acceptedMimeTypes = setOf("application/rtf", "text/rtf"),
    ),
    DOCX(
        category = AttachmentCategory.DOCUMENT,
        canonicalMimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        extensions = setOf("docx"),
        acceptedMimeTypes = setOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    ),
    XLSX(
        category = AttachmentCategory.DOCUMENT,
        canonicalMimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        extensions = setOf("xlsx"),
        acceptedMimeTypes = setOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ),
    PPTX(
        category = AttachmentCategory.DOCUMENT,
        canonicalMimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        extensions = setOf("pptx"),
        acceptedMimeTypes = setOf("application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    ),
    ODT(
        category = AttachmentCategory.DOCUMENT,
        canonicalMimeType = "application/vnd.oasis.opendocument.text",
        extensions = setOf("odt"),
        acceptedMimeTypes = setOf("application/vnd.oasis.opendocument.text"),
    ),
    ODS(
        category = AttachmentCategory.DOCUMENT,
        canonicalMimeType = "application/vnd.oasis.opendocument.spreadsheet",
        extensions = setOf("ods"),
        acceptedMimeTypes = setOf("application/vnd.oasis.opendocument.spreadsheet"),
    ),
    ODP(
        category = AttachmentCategory.DOCUMENT,
        canonicalMimeType = "application/vnd.oasis.opendocument.presentation",
        extensions = setOf("odp"),
        acceptedMimeTypes = setOf("application/vnd.oasis.opendocument.presentation"),
    ),
}

data class AttachmentPreview(
    val originalFilename: String,
    val mimeType: String,
    val declaredSize: Long?,
    val format: AttachmentFormat,
    val validationLevel: AttachmentValidationLevel,
    val imageWidth: Int?,
    val imageHeight: Int?,
    val pdfPageCount: Int?,
)

data class PreparedAttachment(
    val attachmentId: String,
    val originalFilename: String,
    val mimeType: String,
    val fileSize: Long,
    val sha256Checksum: String,
    val localRelativePath: String,
    val thumbnailRelativePath: String?,
    val encryptionFormatVersion: Int = ATTACHMENT_ENCRYPTION_FORMAT_NONE,
    val format: AttachmentFormat,
    val imageWidth: Int?,
    val imageHeight: Int?,
    val pdfPageCount: Int?,
)

data class PlannedAttachmentPaths(
    val localRelativePath: String,
    val thumbnailRelativePath: String,
)

data class ThumbnailMetadata(
    val width: Int,
    val height: Int,
    val mimeType: String,
)

data class CleanupResult(
    val deletedFiles: Int,
    val failedDeletions: Int,
)
