package com.vaultnote.core.backup

import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.RepositoryResult
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-derived, versioned AES-GCM entry encryption for portable backup archives. */
internal class BackupCrypto(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun newSalt(): ByteArray = ByteArray(BackupFormat.SALT_BYTES).also(secureRandom::nextBytes)

    fun newArchiveId(): ByteArray =
        ByteArray(BackupFormat.ARCHIVE_ID_BYTES).also(secureRandom::nextBytes)

    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): RepositoryResult<BackupKey> {
        if (salt.size != BackupFormat.SALT_BYTES || iterations != BackupFormat.KDF_ITERATIONS) {
            return RepositoryResult.Failure(
                AppError.BackupValidationFailure(
                    AppError.BackupValidationReason.UNSUPPORTED_VERSION,
                ),
            )
        }
        val spec = PBEKeySpec(password, salt, iterations, BackupFormat.KEY_BITS)
        return try {
            val encoded = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
                .generateSecret(spec)
                .encoded
            if (encoded.size != KEY_BYTES) {
                encoded.fill(0)
                RepositoryResult.Failure(AppError.EncryptionFailure())
            } else {
                RepositoryResult.Success(BackupKey(encoded))
            }
        } catch (failure: GeneralSecurityException) {
            RepositoryResult.Failure(AppError.EncryptionFailure(failure))
        } finally {
            spec.clearPassword()
        }
    }

    suspend fun encryptEntry(
        path: String,
        key: BackupKey,
        manifestBinding: ByteArray,
        output: OutputStream,
        writePlaintext: suspend (OutputStream) -> Unit,
    ): BackupEntryChecksum {
        val nonce = ByteArray(GCM_NONCE_BYTES).also(secureRandom::nextBytes)
        val header = entryHeader(nonce)
        val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key.secretKey(), GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAAD(header)
            updateAAD(manifestBinding)
            updateAAD(path.encodeToByteArray())
        }
        val encrypted = EncryptingEntryOutput(output, cipher, digest)
        encrypted.writeHeader(header)
        writePlaintext(encrypted)
        encrypted.finish()
        return BackupEntryChecksum(
            path = path,
            ciphertextSize = encrypted.ciphertextSize,
            ciphertextSha256 = digest.digest().toHex(),
        )
    }

    fun decryptVerifiedTo(
        path: String,
        key: BackupKey,
        manifestBinding: ByteArray,
        openInput: () -> InputStream,
        output: OutputStream,
        authenticationFailure: AppError.BackupValidationReason,
    ): RepositoryResult<Long> {
        return try {
            openInput().use { input -> decryptPass(path, key, manifestBinding, input, null) }
            val plaintextBytes = openInput().use { input ->
                decryptPass(path, key, manifestBinding, input, output)
            }
            output.flush()
            RepositoryResult.Success(plaintextBytes)
        } catch (_: AEADBadTagException) {
            RepositoryResult.Failure(AppError.BackupValidationFailure(authenticationFailure))
        } catch (_: EOFException) {
            corrupted()
        } catch (_: IllegalArgumentException) {
            corrupted()
        } catch (_: GeneralSecurityException) {
            corrupted()
        } catch (_: IOException) {
            corrupted()
        }
    }

    private fun decryptPass(
        path: String,
        key: BackupKey,
        manifestBinding: ByteArray,
        input: InputStream,
        output: OutputStream?,
    ): Long {
        val header = ByteArray(HEADER_BYTES)
        input.readFully(header)
        require(header.copyOfRange(0, ENTRY_MAGIC.size).contentEquals(ENTRY_MAGIC))
        require(header[ENTRY_MAGIC.size].toInt() == ENTRY_VERSION)
        require(header[ENTRY_MAGIC.size + 1].toInt() == GCM_NONCE_BYTES)
        val nonce = header.copyOfRange(HEADER_PREFIX_BYTES, HEADER_BYTES)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key.secretKey(), GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAAD(header)
            updateAAD(manifestBinding)
            updateAAD(path.encodeToByteArray())
        }
        var plaintextBytes = 0L
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            cipher.update(buffer, 0, count)?.let { plaintext ->
                output?.write(plaintext)
                plaintextBytes = Math.addExact(plaintextBytes, plaintext.size.toLong())
            }
        }
        cipher.doFinal()?.let { plaintext ->
            output?.write(plaintext)
            plaintextBytes = Math.addExact(plaintextBytes, plaintext.size.toLong())
        }
        return plaintextBytes
    }

    private fun entryHeader(nonce: ByteArray): ByteArray = ByteArray(HEADER_BYTES).apply {
        ENTRY_MAGIC.copyInto(this)
        this[ENTRY_MAGIC.size] = ENTRY_VERSION.toByte()
        this[ENTRY_MAGIC.size + 1] = GCM_NONCE_BYTES.toByte()
        nonce.copyInto(this, HEADER_PREFIX_BYTES)
    }

    private fun corrupted(): RepositoryResult.Failure = RepositoryResult.Failure(
        AppError.BackupValidationFailure(AppError.BackupValidationReason.CHECKSUM_MISMATCH),
    )

    private class EncryptingEntryOutput(
        private val destination: OutputStream,
        private val cipher: Cipher,
        private val digest: MessageDigest,
    ) : OutputStream() {
        var ciphertextSize: Long = 0L
            private set
        private var finished = false

        fun writeHeader(header: ByteArray) {
            writeCiphertext(header)
        }

        override fun write(value: Int) {
            write(byteArrayOf(value.toByte()))
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            check(!finished)
            if (length == 0) return
            cipher.update(buffer, offset, length)?.let(::writeCiphertext)
        }

        override fun close() {
            finish()
        }

        fun finish() {
            if (finished) return
            writeCiphertext(cipher.doFinal())
            finished = true
        }

        private fun writeCiphertext(bytes: ByteArray) {
            destination.write(bytes)
            digest.update(bytes)
            ciphertextSize = Math.addExact(ciphertextSize, bytes.size.toLong())
        }
    }

    internal class BackupKey(private val encoded: ByteArray) : AutoCloseable {
        fun secretKey(): SecretKeySpec {
            check(encoded.any { it.toInt() != 0 })
            return SecretKeySpec(encoded, AES_ALGORITHM)
        }

        override fun close() {
            encoded.fill(0)
        }

        override fun toString(): String = "BackupKey(redacted)"
    }

    private fun InputStream.readFully(destination: ByteArray) {
        var offset = 0
        while (offset < destination.size) {
            val count = read(destination, offset, destination.size - offset)
            if (count < 0) throw EOFException()
            if (count == 0) continue
            offset += count
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private companion object {
        val ENTRY_MAGIC = byteArrayOf('V'.code.toByte(), 'N'.code.toByte(), 'B'.code.toByte(), 'E'.code.toByte())
        const val ENTRY_VERSION = 1
        const val HEADER_PREFIX_BYTES = 6
        const val HEADER_BYTES = HEADER_PREFIX_BYTES + 12
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val KEY_BYTES = 32
        const val BUFFER_BYTES = 64 * 1024
        const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_ALGORITHM = "AES"
        const val SHA256_ALGORITHM = "SHA-256"
    }
}
