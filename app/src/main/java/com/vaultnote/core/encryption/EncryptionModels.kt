package com.vaultnote.core.encryption

const val CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION: Int = 1
const val LEGACY_PLAINTEXT_FORMAT_VERSION: Int = 0

enum class EncryptedFilePurpose(val wireCode: Byte) {
    ATTACHMENT(1),
    THUMBNAIL(2),
}

data class EncryptionContext(
    val recordId: String,
    val purpose: EncryptedFilePurpose,
)

data class EncryptionEnvelopeInfo(
    val formatVersion: Int,
    val keyVersion: Int,
    val plaintextLength: Long,
)
