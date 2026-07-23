package com.vaultnote.core.backup

import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.RepositoryResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupManifestCodecTest {
    private val manifest = BackupManifest(
        archiveId = ByteArray(BackupFormat.ARCHIVE_ID_BYTES) { it.toByte() },
        createdAtEpochMillis = 1_725_000_000_000L,
        salt = ByteArray(BackupFormat.SALT_BYTES) { (it * 3).toByte() },
        kdfIterations = BackupFormat.KDF_ITERATIONS,
        checksumsCiphertextSize = 512L,
        checksumsCiphertextSha256 = "a".repeat(64),
    )

    @Test
    fun `version one manifest round trips with exact cryptographic parameters`() {
        val decoded = BackupManifestCodec.decode(BackupManifestCodec.encode(manifest)).successValue()

        assertArrayEquals(manifest.archiveId, decoded.archiveId)
        assertArrayEquals(manifest.salt, decoded.salt)
        assertEquals(manifest.createdAtEpochMillis, decoded.createdAtEpochMillis)
        assertEquals(BackupFormat.KDF_ITERATIONS, decoded.kdfIterations)
        assertEquals(manifest.checksumsCiphertextSize, decoded.checksumsCiphertextSize)
        assertEquals(manifest.checksumsCiphertextSha256, decoded.checksumsCiphertextSha256)
        assertEquals(BackupProtection.ENCRYPTED, decoded.protection)
    }

    @Test
    fun `version two plaintext manifest round trips without cryptographic parameters`() {
        val plaintext = manifest.copy(
            salt = byteArrayOf(),
            kdfIterations = 0,
            formatVersion = BackupFormat.PLAINTEXT_VERSION,
            minimumReaderVersion = BackupFormat.PLAINTEXT_MIN_READER_VERSION,
            protection = BackupProtection.PLAINTEXT,
            checksumsPath = BackupFormat.PLAINTEXT_CHECKSUMS_PATH,
        )

        val decoded = BackupManifestCodec.decode(
            BackupManifestCodec.encode(plaintext),
        ).successValue()

        assertEquals(BackupProtection.PLAINTEXT, decoded.protection)
        assertTrue(decoded.salt.isEmpty())
        assertEquals(0, decoded.kdfIterations)
        assertEquals(BackupFormat.PLAINTEXT_CHECKSUMS_PATH, decoded.checksumsPath)
    }

    @Test
    fun `unknown versions and manifest extensions are rejected`() {
        val encoded = BackupManifestCodec.encode(manifest).decodeToString()
        assertReason(
            encoded.replace("\"formatVersion\":1", "\"formatVersion\":99")
                .encodeToByteArray(),
            AppError.BackupValidationReason.UNSUPPORTED_VERSION,
        )
        assertReason(
            encoded.dropLast(1).plus(",\"unexpected\":true}").encodeToByteArray(),
            AppError.BackupValidationReason.INVALID_MANIFEST,
        )
        assertReason(
            encoded.dropLast(1).plus(",\"magic\":\"VaultNoteBackup\"}").encodeToByteArray(),
            AppError.BackupValidationReason.INVALID_MANIFEST,
        )
    }

    @Test
    fun `malformed salt checksum and truncated JSON are rejected`() {
        val encoded = BackupManifestCodec.encode(manifest).decodeToString()
        assertReason(
            encoded.replace("\"salt\":\"", "\"salt\":\"not-base64!")
                .encodeToByteArray(),
            AppError.BackupValidationReason.INVALID_MANIFEST,
        )
        assertReason(
            encoded.replace("a".repeat(64), "A".repeat(64)).encodeToByteArray(),
            AppError.BackupValidationReason.INVALID_MANIFEST,
        )
        assertReason(encoded.dropLast(1).encodeToByteArray(), AppError.BackupValidationReason.INVALID_MANIFEST)
    }

    private fun assertReason(bytes: ByteArray, expected: AppError.BackupValidationReason) {
        val result = BackupManifestCodec.decode(bytes)
        assertTrue(result is RepositoryResult.Failure)
        val error = (result as RepositoryResult.Failure).error
        assertTrue(error is AppError.BackupValidationFailure)
        assertEquals(expected, (error as AppError.BackupValidationFailure).reason)
    }

    private fun <T> RepositoryResult<T>.successValue(): T =
        (this as RepositoryResult.Success<T>).value
}
