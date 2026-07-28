package com.vaultnote.core.sync.lan

import android.util.Base64
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.DefaultDispatcherProvider
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.RepositoryResult
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CancellationException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class SyncEnvelopePurpose(val wireCode: Byte, val hkdfLabel: String) {
    ITEM(1, "VaultNote Sync v3 Item"),
    ATTACHMENT(2, "VaultNote Sync v3 Attachment"),
    KEY_CHECK(3, "VaultNote Sync v3 Key Check"),
}

data class SyncEnvelopeInfo(
    val purpose: SyncEnvelopePurpose,
    val keyVersion: Int,
    val plaintextLength: Long,
)

/**
 * Protocol-3 AES-256-GCM envelope implementation. Purpose keys are separated with HKDF-SHA256,
 * every write uses a random 96-bit nonce, and file decryption authenticates a full first pass
 * before any plaintext is released to its caller.
 */
class SyncEnvelopeCrypto(
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    suspend fun deriveMasterKey(
        password: CharArray,
        saltBase64Url: String,
        iterations: Int,
    ): RepositoryResult<ByteArray> = withContext(dispatchers.default) {
        if (
            password.size !in MIN_PASSWORD_CHARACTERS..MAX_PASSWORD_CHARACTERS ||
            iterations != REQUIRED_PBKDF2_ITERATIONS
        ) {
            return@withContext RepositoryResult.Failure(
                AppError.InvalidInput("sync_password", "invalid"),
            )
        }
        var salt: ByteArray? = null
        var specification: PBEKeySpec? = null
        try {
            salt = Base64.decode(
                saltBase64Url.replace('-', '+').replace('_', '/').padBase64(),
                Base64.DEFAULT,
            )
            if (salt.size != PBKDF2_SALT_BYTES) {
                return@withContext RepositoryResult.Failure(
                    AppError.InvalidInput("sync_kdf", "invalid_salt"),
                )
            }
            specification = PBEKeySpec(password, salt, iterations, MASTER_KEY_BITS)
            val key = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
                .generateSecret(specification)
                .encoded
            if (key.size != MASTER_KEY_BYTES) {
                key.fill(0)
                RepositoryResult.Failure(AppError.EncryptionFailure())
            } else {
                RepositoryResult.Success(key)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            RepositoryResult.Failure(AppError.InvalidInput("sync_kdf", "invalid_salt"))
        } catch (_: GeneralSecurityException) {
            RepositoryResult.Failure(AppError.EncryptionFailure())
        } finally {
            salt?.fill(0)
            specification?.clearPassword()
        }
    }

    suspend fun encryptBytes(
        masterKey: ByteArray,
        vaultId: String,
        objectId: String,
        purpose: SyncEnvelopePurpose,
        plaintext: ByteArray,
    ): RepositoryResult<ByteArray> = withContext(dispatchers.default) {
        if (!validContext(masterKey, vaultId, objectId) || plaintext.size > MAX_ITEM_BYTES) {
            return@withContext RepositoryResult.Failure(
                AppError.InvalidInput("sync_envelope", "invalid"),
            )
        }
        var purposeKey: ByteArray? = null
        try {
            purposeKey = derivePurposeKey(masterKey, purpose)
            val nonce = ByteArray(GCM_NONCE_BYTES).also(secureRandom::nextBytes)
            val header = encodeHeader(purpose, nonce, plaintext.size.toLong())
            val cipher = newCipher(
                Cipher.ENCRYPT_MODE,
                purposeKey,
                nonce,
                additionalData(header, vaultId, objectId),
            )
            RepositoryResult.Success(header + cipher.doFinal(plaintext))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: GeneralSecurityException) {
            RepositoryResult.Failure(AppError.EncryptionFailure())
        } finally {
            purposeKey?.fill(0)
        }
    }

    suspend fun decryptBytes(
        masterKey: ByteArray,
        vaultId: String,
        objectId: String,
        expectedPurpose: SyncEnvelopePurpose,
        envelope: ByteArray,
    ): RepositoryResult<ByteArray> = withContext(dispatchers.default) {
        if (!validContext(masterKey, vaultId, objectId) || envelope.size > MAX_ITEM_ENVELOPE_BYTES) {
            return@withContext RepositoryResult.Failure(AppError.DecryptionFailure())
        }
        var purposeKey: ByteArray? = null
        try {
            val parsed = parseHeader(envelope)
            if (
                parsed.info.purpose != expectedPurpose ||
                parsed.info.plaintextLength > MAX_ITEM_BYTES ||
                envelope.size.toLong() != parsed.header.size + parsed.info.plaintextLength + GCM_TAG_BYTES
            ) {
                return@withContext RepositoryResult.Failure(AppError.DecryptionFailure())
            }
            purposeKey = derivePurposeKey(masterKey, expectedPurpose)
            val cipher = newCipher(
                Cipher.DECRYPT_MODE,
                purposeKey,
                parsed.nonce,
                additionalData(parsed.header, vaultId, objectId),
            )
            val plaintext = cipher.doFinal(envelope, parsed.header.size, envelope.size - parsed.header.size)
            if (plaintext.size.toLong() != parsed.info.plaintextLength) {
                plaintext.fill(0)
                RepositoryResult.Failure(AppError.DecryptionFailure())
            } else {
                RepositoryResult.Success(plaintext)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AEADBadTagException) {
            RepositoryResult.Failure(AppError.DecryptionFailure())
        } catch (_: GeneralSecurityException) {
            RepositoryResult.Failure(AppError.DecryptionFailure())
        } catch (_: IllegalArgumentException) {
            RepositoryResult.Failure(AppError.DecryptionFailure())
        } catch (_: IOException) {
            RepositoryResult.Failure(AppError.DecryptionFailure())
        } finally {
            purposeKey?.fill(0)
        }
    }

    suspend fun encryptFileAtomically(
        masterKey: ByteArray,
        vaultId: String,
        objectId: String,
        plaintextLength: Long,
        destination: File,
        plaintextProducer: suspend (OutputStream) -> RepositoryResult<Unit>,
    ): RepositoryResult<SyncEnvelopeInfo> = withContext(dispatchers.io) {
        if (
            !validContext(masterKey, vaultId, objectId) ||
            plaintextLength !in 0..MAX_ATTACHMENT_BYTES ||
            destination.parentFile == null
        ) {
            return@withContext RepositoryResult.Failure(
                AppError.InvalidInput("sync_attachment", "invalid"),
            )
        }
        val parent = requireNotNull(destination.parentFile)
        if (!parent.isDirectory && !parent.mkdirs()) {
            return@withContext RepositoryResult.Failure(AppError.InsufficientStorage())
        }
        val temporary = File(parent, ".pending-sync-${UUID.randomUUID()}.tmp")
        var purposeKey: ByteArray? = null
        try {
            currentCoroutineContext().ensureActive()
            purposeKey = derivePurposeKey(masterKey, SyncEnvelopePurpose.ATTACHMENT)
            val nonce = ByteArray(GCM_NONCE_BYTES).also(secureRandom::nextBytes)
            val header = encodeHeader(SyncEnvelopePurpose.ATTACHMENT, nonce, plaintextLength)
            val cipher = newCipher(
                Cipher.ENCRYPT_MODE,
                purposeKey,
                nonce,
                additionalData(header, vaultId, objectId),
            )
            FileOutputStream(temporary).use { fileOutput ->
                val buffered = BufferedOutputStream(fileOutput, BUFFER_BYTES)
                buffered.write(header)
                val encrypting = CipherUpdatingOutputStream(buffered, cipher)
                when (val produced = plaintextProducer(encrypting)) {
                    is RepositoryResult.Success -> Unit
                    is RepositoryResult.Failure -> return@withContext produced
                }
                if (encrypting.plaintextBytes != plaintextLength) {
                    return@withContext RepositoryResult.Failure(AppError.CorruptedFile)
                }
                encrypting.finish()
                buffered.flush()
                fileOutput.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
            RepositoryResult.Success(
                SyncEnvelopeInfo(
                    SyncEnvelopePurpose.ATTACHMENT,
                    CURRENT_KEY_VERSION,
                    plaintextLength,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            RepositoryResult.Failure(AppError.EncryptionFailure())
        } catch (_: GeneralSecurityException) {
            RepositoryResult.Failure(AppError.EncryptionFailure())
        } catch (_: UnsupportedOperationException) {
            RepositoryResult.Failure(AppError.EncryptionFailure())
        } finally {
            purposeKey?.fill(0)
            if (temporary.exists()) temporary.delete()
        }
    }

    suspend fun decryptFileVerifiedTo(
        masterKey: ByteArray,
        vaultId: String,
        objectId: String,
        encryptedFile: File,
        expectedPlaintextLength: Long,
        output: OutputStream,
    ): RepositoryResult<SyncEnvelopeInfo> = withContext(dispatchers.io) {
        if (
            !validContext(masterKey, vaultId, objectId) ||
            expectedPlaintextLength !in 0..MAX_ATTACHMENT_BYTES ||
            !encryptedFile.isFile
        ) {
            return@withContext RepositoryResult.Failure(AppError.DecryptionFailure())
        }
        var purposeKey: ByteArray? = null
        try {
            RandomAccessFile(encryptedFile, "r").use { input ->
                val parsed = readHeader(input, encryptedFile.length())
                if (
                    parsed.info.purpose != SyncEnvelopePurpose.ATTACHMENT ||
                    parsed.info.plaintextLength != expectedPlaintextLength
                ) {
                    return@withContext RepositoryResult.Failure(AppError.DecryptionFailure())
                }
                purposeKey = derivePurposeKey(masterKey, SyncEnvelopePurpose.ATTACHMENT)
                decryptFilePass(input, parsed, purposeKey, vaultId, objectId, null)
                currentCoroutineContext().ensureActive()
                decryptFilePass(input, parsed, purposeKey, vaultId, objectId, output)
                output.flush()
                RepositoryResult.Success(parsed.info)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AEADBadTagException) {
            RepositoryResult.Failure(AppError.DecryptionFailure())
        } catch (_: IOException) {
            RepositoryResult.Failure(AppError.DecryptionFailure())
        } catch (_: GeneralSecurityException) {
            RepositoryResult.Failure(AppError.DecryptionFailure())
        } finally {
            purposeKey?.fill(0)
        }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_BYTES).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toLowerHex()
    }

    private suspend fun decryptFilePass(
        input: RandomAccessFile,
        parsed: ParsedEnvelope,
        purposeKey: ByteArray,
        vaultId: String,
        objectId: String,
        output: OutputStream?,
    ) {
        input.seek(parsed.header.size.toLong())
        val cipher = newCipher(
            Cipher.DECRYPT_MODE,
            purposeKey,
            parsed.nonce,
            additionalData(parsed.header, vaultId, objectId),
        )
        var remaining = parsed.info.plaintextLength + GCM_TAG_BYTES
        var plaintextBytes = 0L
        val buffer = ByteArray(BUFFER_BYTES)
        while (remaining > 0L) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Truncated sync attachment")
            if (read == 0) continue
            remaining -= read
            cipher.update(buffer, 0, read)?.let { plaintext ->
                plaintextBytes += plaintext.size
                output?.write(plaintext)
            }
        }
        cipher.doFinal().let { plaintext ->
            plaintextBytes += plaintext.size
            output?.write(plaintext)
        }
        if (plaintextBytes != parsed.info.plaintextLength) {
            throw GeneralSecurityException("Sync attachment length mismatch")
        }
    }

    private fun derivePurposeKey(
        masterKey: ByteArray,
        purpose: SyncEnvelopePurpose,
    ): ByteArray {
        val extract = Mac.getInstance(HMAC_ALGORITHM)
        extract.init(SecretKeySpec(ByteArray(HKDF_HASH_BYTES), HMAC_ALGORITHM))
        val pseudoRandomKey = extract.doFinal(masterKey)
        return try {
            val expand = Mac.getInstance(HMAC_ALGORITHM)
            expand.init(SecretKeySpec(pseudoRandomKey, HMAC_ALGORITHM))
            expand.update(purpose.hkdfLabel.toByteArray(StandardCharsets.UTF_8))
            expand.doFinal(byteArrayOf(1)).copyOf(MASTER_KEY_BYTES)
        } finally {
            pseudoRandomKey.fill(0)
        }
    }

    private fun newCipher(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
    ): Cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        updateAAD(aad)
    }

    private fun encodeHeader(
        purpose: SyncEnvelopePurpose,
        nonce: ByteArray,
        plaintextLength: Long,
    ): ByteArray = ByteBuffer.allocate(HEADER_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .put(MAGIC)
        .put(ENVELOPE_VERSION)
        .put(purpose.wireCode)
        .putShort(CURRENT_KEY_VERSION.toShort())
        .put(nonce)
        .putLong(plaintextLength)
        .array()

    private fun parseHeader(envelope: ByteArray): ParsedEnvelope {
        if (envelope.size < HEADER_BYTES + GCM_TAG_BYTES) throw EOFException("Truncated envelope")
        return decodeHeader(envelope.copyOfRange(0, HEADER_BYTES), envelope.size.toLong())
    }

    private fun readHeader(input: RandomAccessFile, fileLength: Long): ParsedEnvelope {
        val header = ByteArray(HEADER_BYTES)
        input.readFully(header)
        return decodeHeader(header, fileLength)
    }

    private fun decodeHeader(header: ByteArray, envelopeLength: Long): ParsedEnvelope {
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        if (!magic.contentEquals(MAGIC) || buffer.get() != ENVELOPE_VERSION) {
            throw GeneralSecurityException("Unsupported sync envelope")
        }
        val purposeCode = buffer.get()
        val purpose = SyncEnvelopePurpose.entries.firstOrNull { it.wireCode == purposeCode }
            ?: throw GeneralSecurityException("Invalid sync purpose")
        val keyVersion = buffer.short.toInt() and 0xffff
        if (keyVersion != CURRENT_KEY_VERSION) throw GeneralSecurityException("Invalid key version")
        val nonce = ByteArray(GCM_NONCE_BYTES).also(buffer::get)
        val plaintextLength = buffer.long
        if (plaintextLength < 0L || envelopeLength != HEADER_BYTES + plaintextLength + GCM_TAG_BYTES) {
            throw GeneralSecurityException("Invalid sync envelope length")
        }
        return ParsedEnvelope(
            header,
            nonce,
            SyncEnvelopeInfo(purpose, keyVersion, plaintextLength),
        )
    }

    private fun additionalData(
        header: ByteArray,
        vaultId: String,
        objectId: String,
    ): ByteArray {
        val vault = vaultId.toByteArray(StandardCharsets.UTF_8)
        val objectBytes = objectId.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(AAD_PREFIX.size + header.size + 4 + vault.size + 4 + objectBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(AAD_PREFIX)
            .put(header)
            .putInt(vault.size)
            .put(vault)
            .putInt(objectBytes.size)
            .put(objectBytes)
            .array()
    }

    private fun validContext(masterKey: ByteArray, vaultId: String, objectId: String): Boolean =
        masterKey.size == MASTER_KEY_BYTES &&
            vaultId.length in 1..128 &&
            objectId.length in 1..128 &&
            SAFE_ID.matches(vaultId) &&
            SAFE_ID.matches(objectId)

    private fun String.padBase64(): String = this + "=".repeat((4 - length % 4) % 4)

    private fun ByteArray.toLowerHex(): String = joinToString(separator = "") {
        "%02x".format(it.toInt() and 0xff)
    }

    private data class ParsedEnvelope(
        val header: ByteArray,
        val nonce: ByteArray,
        val info: SyncEnvelopeInfo,
    )

    private class CipherUpdatingOutputStream(
        private val destination: OutputStream,
        private val cipher: Cipher,
    ) : OutputStream() {
        var plaintextBytes: Long = 0L
            private set
        private var finished = false

        override fun write(value: Int) {
            write(byteArrayOf(value.toByte()))
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            check(!finished)
            if (length == 0) return
            plaintextBytes = Math.addExact(plaintextBytes, length.toLong())
            cipher.update(buffer, offset, length)?.let(destination::write)
        }

        override fun flush() {
            destination.flush()
        }

        fun finish() {
            check(!finished)
            cipher.doFinal()?.let(destination::write)
            finished = true
        }
    }

    companion object {
        const val REQUIRED_PBKDF2_ITERATIONS = 600_000
        const val MASTER_KEY_BYTES = 32
        const val KEY_CHECK_OBJECT_ID = "key-check"
        const val MAX_ATTACHMENT_BYTES = 100L * 1024L * 1024L
        const val MAX_ITEM_BYTES = 2 * 1024 * 1024
        val KEY_CHECK_PLAINTEXT =
            "VaultNote Sync Key Check v3".toByteArray(StandardCharsets.UTF_8)
        private const val MIN_PASSWORD_CHARACTERS = 8
        private const val MAX_PASSWORD_CHARACTERS = 1_024
        private const val PBKDF2_SALT_BYTES = 32
        private const val MASTER_KEY_BITS = MASTER_KEY_BYTES * 8
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val HKDF_HASH_BYTES = 32
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_NONCE_BYTES = 12
        private const val GCM_TAG_BYTES = 16
        private const val GCM_TAG_BITS = 128
        private const val CURRENT_KEY_VERSION = 1
        private const val HEADER_BYTES = 4 + 1 + 1 + 2 + GCM_NONCE_BYTES + 8
        private const val MAX_ITEM_ENVELOPE_BYTES = MAX_ITEM_BYTES + HEADER_BYTES + GCM_TAG_BYTES
        private const val BUFFER_BYTES = 64 * 1024
        private val MAGIC = byteArrayOf(0x56, 0x4E, 0x53, 0x33)
        private val ENVELOPE_VERSION: Byte = 1
        private val AAD_PREFIX = "VaultNote Sync Envelope".toByteArray(StandardCharsets.UTF_8)
        private val SAFE_ID = Regex("[A-Za-z0-9_-]+")
    }
}
