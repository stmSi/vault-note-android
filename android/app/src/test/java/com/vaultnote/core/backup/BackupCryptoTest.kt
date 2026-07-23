package com.vaultnote.core.backup

import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.RepositoryResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {
    private val crypto = BackupCrypto()
    private val salt = ByteArray(BackupFormat.SALT_BYTES) { index -> (index + 1).toByte() }
    private val binding = "authenticated manifest".encodeToByteArray()

    @Test
    fun `round trip authenticates before releasing plaintext and uses unique nonces`() = runBlocking {
        val key = crypto.deriveKey(
            "correct horse battery staple".toCharArray(),
            salt,
            BackupFormat.KDF_ITERATIONS,
        ).successValue()
        try {
            val plaintext = ByteArray(256 * 1024) { index -> (index * 37).toByte() }
            val first = encrypt(key, BackupFormat.DATABASE_PATH, plaintext)
            val second = encrypt(key, BackupFormat.DATABASE_PATH, plaintext)
            val restored = ByteArrayOutputStream()

            val result = crypto.decryptVerifiedTo(
                path = BackupFormat.DATABASE_PATH,
                key = key,
                manifestBinding = binding,
                openInput = { ByteArrayInputStream(first) },
                output = restored,
                authenticationFailure = AppError.BackupValidationReason.WRONG_KEY,
            ).successValue()

            assertEquals(plaintext.size.toLong(), result)
            assertArrayEquals(plaintext, restored.toByteArray())
            assertNotEquals(first.toList(), second.toList())
        } finally {
            key.close()
        }
    }

    @Test
    fun `wrong password corruption and path substitution expose no plaintext`() = runBlocking {
        val rightKey = crypto.deriveKey(
            "correct horse battery staple".toCharArray(),
            salt,
            BackupFormat.KDF_ITERATIONS,
        ).successValue()
        val wrongKey = crypto.deriveKey(
            "different backup password".toCharArray(),
            salt,
            BackupFormat.KDF_ITERATIONS,
        ).successValue()
        try {
            val encrypted = encrypt(
                rightKey,
                BackupFormat.DATABASE_PATH,
                "private database content".encodeToByteArray(),
            )
            assertAuthenticationFailure(
                key = wrongKey,
                path = BackupFormat.DATABASE_PATH,
                encrypted = encrypted,
                expectedReason = AppError.BackupValidationReason.WRONG_KEY,
            )

            val corrupted = encrypted.copyOf().also { bytes ->
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            }
            assertAuthenticationFailure(
                key = rightKey,
                path = BackupFormat.DATABASE_PATH,
                encrypted = corrupted,
                expectedReason = AppError.BackupValidationReason.CHECKSUM_MISMATCH,
            )

            assertAuthenticationFailure(
                key = rightKey,
                path = BackupFormat.CHECKSUMS_PATH,
                encrypted = encrypted,
                expectedReason = AppError.BackupValidationReason.CHECKSUM_MISMATCH,
            )
        } finally {
            rightKey.close()
            wrongKey.close()
        }
    }

    private suspend fun encrypt(
        key: BackupCrypto.BackupKey,
        path: String,
        plaintext: ByteArray,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        crypto.encryptEntry(path, key, binding, output) { it.write(plaintext) }
        return output.toByteArray()
    }

    private fun assertAuthenticationFailure(
        key: BackupCrypto.BackupKey,
        path: String,
        encrypted: ByteArray,
        expectedReason: AppError.BackupValidationReason,
    ) {
        val output = ByteArrayOutputStream()
        val result = crypto.decryptVerifiedTo(
            path = path,
            key = key,
            manifestBinding = binding,
            openInput = { ByteArrayInputStream(encrypted) },
            output = output,
            authenticationFailure = expectedReason,
        )

        assertTrue(result is RepositoryResult.Failure)
        val error = (result as RepositoryResult.Failure).error
        assertTrue(error is AppError.BackupValidationFailure)
        assertEquals(expectedReason, (error as AppError.BackupValidationFailure).reason)
        assertEquals(0, output.size())
    }

    private fun <T> RepositoryResult<T>.successValue(): T =
        (this as RepositoryResult.Success<T>).value
}
