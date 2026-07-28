package com.vaultnote.core.sync.lan

import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.RepositoryResult
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncEnvelopeCryptoTest {
    private val crypto = SyncEnvelopeCrypto(TestDispatchers)
    private val directory = File(
        System.getProperty("java.io.tmpdir"),
        "vaultnote-sync-envelope-test",
    )

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `item envelope round trips and binds vault item and purpose`() = runBlocking {
        val plaintext = "Bangkok filename searchable".encodeToByteArray()
        val envelope = crypto.encryptBytes(
            MASTER_KEY,
            VAULT_ID,
            ITEM_ID,
            SyncEnvelopePurpose.ITEM,
            plaintext,
        ).successValue()

        assertArrayEquals(
            plaintext,
            crypto.decryptBytes(
                MASTER_KEY,
                VAULT_ID,
                ITEM_ID,
                SyncEnvelopePurpose.ITEM,
                envelope,
            ).successValue(),
        )
        assertTrue(
            crypto.decryptBytes(
                MASTER_KEY,
                "another_vault",
                ITEM_ID,
                SyncEnvelopePurpose.ITEM,
                envelope,
            ) is RepositoryResult.Failure,
        )
        assertTrue(
            crypto.decryptBytes(
                MASTER_KEY,
                VAULT_ID,
                "another_item",
                SyncEnvelopePurpose.ITEM,
                envelope,
            ) is RepositoryResult.Failure,
        )
        assertTrue(
            crypto.decryptBytes(
                MASTER_KEY,
                VAULT_ID,
                ITEM_ID,
                SyncEnvelopePurpose.KEY_CHECK,
                envelope,
            ) is RepositoryResult.Failure,
        )
    }

    @Test
    fun `corrupted item envelope is rejected`() = runBlocking {
        val envelope = crypto.encryptBytes(
            MASTER_KEY,
            VAULT_ID,
            ITEM_ID,
            SyncEnvelopePurpose.ITEM,
            "private note".encodeToByteArray(),
        ).successValue()
        envelope[envelope.lastIndex] = (envelope.last().toInt() xor 1).toByte()

        assertTrue(
            crypto.decryptBytes(
                MASTER_KEY,
                VAULT_ID,
                ITEM_ID,
                SyncEnvelopePurpose.ITEM,
                envelope,
            ) is RepositoryResult.Failure,
        )
    }

    @Test
    fun `attachment corruption exposes no plaintext`() = runBlocking {
        directory.mkdirs()
        val plaintext = ByteArray(192 * 1024) { index -> (index % 251).toByte() }
        val envelopeFile = File(directory, "attachment.vns3")
        crypto.encryptFileAtomically(
            masterKey = MASTER_KEY,
            vaultId = VAULT_ID,
            objectId = ATTACHMENT_ID,
            plaintextLength = plaintext.size.toLong(),
            destination = envelopeFile,
        ) { output ->
            output.write(plaintext)
            RepositoryResult.Success(Unit)
        }.successValue()

        val corrupted = envelopeFile.readBytes()
        corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()
        envelopeFile.outputStream().use { it.write(corrupted) }
        val destination = ByteArrayOutputStream()
        val result = crypto.decryptFileVerifiedTo(
            MASTER_KEY,
            VAULT_ID,
            ATTACHMENT_ID,
            envelopeFile,
            plaintext.size.toLong(),
            destination,
        )

        assertTrue(result is RepositoryResult.Failure)
        assertEquals(0, destination.size())
    }

    @Test
    fun `producer length mismatch leaves no destination`() = runBlocking {
        directory.mkdirs()
        val destination = File(directory, "invalid.vns3")
        val result = crypto.encryptFileAtomically(
            masterKey = MASTER_KEY,
            vaultId = VAULT_ID,
            objectId = ATTACHMENT_ID,
            plaintextLength = 10L,
            destination = destination,
        ) { output ->
            output.write(byteArrayOf(1, 2, 3))
            RepositoryResult.Success(Unit)
        }

        assertTrue(result is RepositoryResult.Failure)
        assertFalse(destination.exists())
        assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith(".pending-sync-") })
    }

    private fun <T> RepositoryResult<T>.successValue(): T {
        assertTrue(this is RepositoryResult.Success)
        return (this as RepositoryResult.Success<T>).value
    }

    private object TestDispatchers : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private companion object {
        val MASTER_KEY = ByteArray(32) { index -> (index + 1).toByte() }
        const val VAULT_ID = "vault_test"
        const val ITEM_ID = "item_123"
        const val ATTACHMENT_ID = "attachment_123"
    }
}
