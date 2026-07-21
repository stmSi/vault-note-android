package com.vaultnote.core.encryption

import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.RepositoryResult
import java.io.ByteArrayOutputStream
import java.io.File
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AesGcmEncryptionServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val keys = TestKeyProvider()
    private val service = AesGcmEncryptionService(
        keyProvider = keys,
        dispatchers = TestDispatchers,
        minimumFreeSpaceReserveBytes = 0L,
        availableSpaceBytes = { Long.MAX_VALUE },
    )
    private val context = EncryptionContext("attachment_1", EncryptedFilePurpose.ATTACHMENT)

    @Test
    fun `round trip authenticates before returning plaintext`() = runBlocking {
        val plaintext = ByteArray(512 * 1024) { index -> (index * 31).toByte() }
        val source = temporaryFolder.newFile("source.bin").apply { writeBytes(plaintext) }
        val encrypted = File(temporaryFolder.root, "encrypted.bin")

        val info = service.encryptFileAtomically(source, encrypted, context, false).successValue()
        val output = ByteArrayOutputStream()
        val decrypted = service.decryptVerifiedTo(encrypted, context, output).successValue()

        assertEquals(CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION, info.formatVersion)
        assertEquals(plaintext.size.toLong(), decrypted.plaintextLength)
        assertArrayEquals(plaintext, output.toByteArray())
        assertFalse(encrypted.readBytes().containsSubsequence(plaintext.copyOfRange(0, 64)))
    }

    @Test
    fun `same plaintext receives a unique nonce and ciphertext`() = runBlocking {
        val source = temporaryFolder.newFile("same.bin").apply { writeText("same private bytes") }
        val first = File(temporaryFolder.root, "first.bin")
        val second = File(temporaryFolder.root, "second.bin")

        service.encryptFileAtomically(source, first, context, false).successValue()
        service.encryptFileAtomically(source, second, context, false).successValue()

        assertNotEquals(first.readBytes().toList(), second.readBytes().toList())
        assertNotEquals(
            first.readBytes().copyOfRange(NONCE_OFFSET, NONCE_OFFSET + NONCE_BYTES).toList(),
            second.readBytes().copyOfRange(NONCE_OFFSET, NONCE_OFFSET + NONCE_BYTES).toList(),
        )
    }

    @Test
    fun `corrupted authentication tag exposes no plaintext`() = runBlocking {
        val source = temporaryFolder.newFile("tag-source.bin").apply { writeText("private") }
        val encrypted = File(temporaryFolder.root, "tag-encrypted.bin")
        service.encryptFileAtomically(source, encrypted, context, false).successValue()
        val corrupted = encrypted.readBytes().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        }
        encrypted.writeBytes(corrupted)
        val output = ByteArrayOutputStream()

        val result = service.decryptVerifiedTo(encrypted, context, output)

        assertTrue(result is RepositoryResult.Failure && result.error is AppError.DecryptionFailure)
        assertEquals(0, output.size())
    }

    @Test
    fun `record identity and purpose are authenticated`() = runBlocking {
        val source = temporaryFolder.newFile("aad-source.bin").apply { writeText("private") }
        val encrypted = File(temporaryFolder.root, "aad-encrypted.bin")
        service.encryptFileAtomically(source, encrypted, context, false).successValue()

        val wrongId = service.inspectAndVerify(
            encrypted,
            context.copy(recordId = "attachment_2"),
        )
        val wrongPurpose = service.inspectAndVerify(
            encrypted,
            context.copy(purpose = EncryptedFilePurpose.THUMBNAIL),
        )

        assertTrue(wrongId is RepositoryResult.Failure)
        assertTrue(wrongPurpose is RepositoryResult.Failure)
    }

    @Test
    fun `key version remains decryptable after rotation`() = runBlocking {
        val source = temporaryFolder.newFile("rotation-source.bin").apply { writeText("versioned") }
        val encryptedV1 = File(temporaryFolder.root, "v1.bin")
        service.encryptFileAtomically(source, encryptedV1, context, false).successValue()
        keys.rotateTo(2)
        val encryptedV2 = File(temporaryFolder.root, "v2.bin")
        val v2Info = service.encryptFileAtomically(source, encryptedV2, context, false).successValue()

        val first = ByteArrayOutputStream()
        val second = ByteArrayOutputStream()
        service.decryptVerifiedTo(encryptedV1, context, first).successValue()
        service.decryptVerifiedTo(encryptedV2, context, second).successValue()

        assertEquals(2, v2Info.keyVersion)
        assertArrayEquals("versioned".encodeToByteArray(), first.toByteArray())
        assertArrayEquals(first.toByteArray(), second.toByteArray())
    }

    @Test
    fun `missing historical key fails without output`() = runBlocking {
        val source = temporaryFolder.newFile("missing-source.bin").apply { writeText("private") }
        val encrypted = File(temporaryFolder.root, "missing.bin")
        service.encryptFileAtomically(source, encrypted, context, false).successValue()
        keys.remove(1)
        val output = ByteArrayOutputStream()

        val result = service.decryptVerifiedTo(encrypted, context, output)

        assertTrue(result is RepositoryResult.Failure && result.error is AppError.DecryptionFailure)
        assertEquals(0, output.size())
    }

    private class TestKeyProvider : EncryptionKeyProvider {
        private val keys = linkedMapOf(1 to key(1))
        override var currentKeyVersion: Int = 1
            private set

        override fun getKey(keyVersion: Int): SecretKey? = keys[keyVersion]

        override fun getOrCreateCurrentKey(): SecretKey =
            keys.getOrPut(currentKeyVersion) { key(currentKeyVersion) }

        fun rotateTo(version: Int) {
            currentKeyVersion = version
            keys.getOrPut(version) { key(version) }
        }

        fun remove(version: Int) {
            keys.remove(version)
        }

        companion object {
            private fun key(version: Int): SecretKey = SecretKeySpec(
                ByteArray(32) { index -> (version * 37 + index).toByte() },
                "AES",
            )
        }
    }

    private object TestDispatchers : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private fun <T> RepositoryResult<T>.successValue(): T =
        (this as RepositoryResult.Success<T>).value

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        return (0..size - needle.size).any { start ->
            needle.indices.all { offset -> this[start + offset] == needle[offset] }
        }
    }

    private companion object {
        const val NONCE_OFFSET = 4 + 1 + 4 + 1 + 8
        const val NONCE_BYTES = 12
    }
}
