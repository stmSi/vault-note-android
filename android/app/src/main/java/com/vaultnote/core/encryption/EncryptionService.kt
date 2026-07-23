package com.vaultnote.core.encryption

import android.os.StatFs
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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.util.UUID
import java.util.concurrent.CancellationException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

interface EncryptionService {
    suspend fun encryptFileAtomically(
        plaintext: File,
        destination: File,
        context: EncryptionContext,
        replaceExisting: Boolean,
    ): RepositoryResult<EncryptionEnvelopeInfo>

    suspend fun inspectAndVerify(
        encryptedFile: File,
        context: EncryptionContext,
    ): RepositoryResult<EncryptionEnvelopeInfo>

    /** Authenticates the complete envelope before writing any plaintext to [output]. */
    suspend fun decryptVerifiedTo(
        encryptedFile: File,
        context: EncryptionContext,
        output: OutputStream,
    ): RepositoryResult<EncryptionEnvelopeInfo>

    suspend fun hasEnvelope(file: File): RepositoryResult<Boolean>
}

/**
 * Versioned AES-256-GCM file encryption.
 *
 * The binary header, nonce, purpose, and record identity are authenticated as AAD. Decryption uses
 * the same open file descriptor for a verification pass and a second streaming pass, so corrupted
 * input cannot release unauthenticated plaintext. Each encryption asks the cipher/Keystore provider
 * to generate a fresh random nonce; callers cannot supply or reuse one.
 */
class AesGcmEncryptionService(
    private val keyProvider: EncryptionKeyProvider,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
    private val minimumFreeSpaceReserveBytes: Long = DEFAULT_FREE_SPACE_RESERVE_BYTES,
    private val availableSpaceBytes: (File) -> Long = { directory ->
        StatFs(directory.path).availableBytes
    },
) : EncryptionService {
    override suspend fun encryptFileAtomically(
        plaintext: File,
        destination: File,
        context: EncryptionContext,
        replaceExisting: Boolean,
    ): RepositoryResult<EncryptionEnvelopeInfo> = withContext(dispatchers.io) {
        val contextBytes = when (val encoded = encodeContext(context)) {
            is RepositoryResult.Success -> encoded.value
            is RepositoryResult.Failure -> return@withContext encoded
        }
        if (!plaintext.isFile) return@withContext RepositoryResult.Failure(AppError.CorruptedFile)
        val plaintextLength = plaintext.length()
        if (plaintextLength !in 0..MAX_PLAINTEXT_BYTES) {
            return@withContext RepositoryResult.Failure(AppError.FileTooLarge(MAX_PLAINTEXT_BYTES))
        }
        val parent = destination.parentFile
            ?: return@withContext RepositoryResult.Failure(AppError.EncryptionFailure())
        if (!parent.isDirectory && !parent.mkdirs()) {
            return@withContext RepositoryResult.Failure(AppError.InsufficientStorage())
        }
        if (!replaceExisting && destination.exists()) {
            return@withContext RepositoryResult.Failure(
                AppError.InvalidInput("encrypted_destination", "already_exists"),
            )
        }
        val requiredBytes = safeAdd(
            safeAdd(plaintextLength, MAX_HEADER_BYTES.toLong() + GCM_TAG_BYTES),
            minimumFreeSpaceReserveBytes,
        )
        if (availableSpaceBytes(parent) < requiredBytes) {
            return@withContext RepositoryResult.Failure(AppError.InsufficientStorage(requiredBytes))
        }

        val temporary = File(parent, "$PENDING_PREFIX${UUID.randomUUID()}.tmp")
        try {
            currentCoroutineContext().ensureActive()
            val keyVersion = keyProvider.currentKeyVersion
            val key = keyProvider.getOrCreateCurrentKey()
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val nonce = cipher.iv
            if (nonce.size != GCM_NONCE_BYTES) throw GeneralSecurityException("Unexpected GCM nonce")
            val header = encodeHeader(keyVersion, nonce, plaintextLength)
            cipher.updateAAD(header)
            cipher.updateAAD(contextBytes)

            FileOutputStream(temporary).use { fileOutput ->
                val output = BufferedOutputStream(fileOutput, BUFFER_BYTES)
                output.write(header)
                plaintext.inputStream().buffered(BUFFER_BYTES).use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        cipher.update(buffer, 0, count)?.let(output::write)
                    }
                    output.write(cipher.doFinal())
                }
                output.flush()
                fileOutput.fd.sync()
            }
            moveAtomically(temporary, destination, replaceExisting)
            RepositoryResult.Success(
                EncryptionEnvelopeInfo(
                    formatVersion = CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION,
                    keyVersion = keyVersion,
                    plaintextLength = plaintextLength,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            RepositoryResult.Failure(AppError.EncryptionFailure(failure))
        } catch (failure: GeneralSecurityException) {
            RepositoryResult.Failure(AppError.EncryptionFailure(failure))
        } catch (failure: SecurityException) {
            RepositoryResult.Failure(AppError.EncryptionFailure(failure))
        } finally {
            temporary.delete()
        }
    }

    override suspend fun inspectAndVerify(
        encryptedFile: File,
        context: EncryptionContext,
    ): RepositoryResult<EncryptionEnvelopeInfo> = withContext(dispatchers.io) {
        verifyOnIo(encryptedFile, context)
    }

    override suspend fun decryptVerifiedTo(
        encryptedFile: File,
        context: EncryptionContext,
        output: OutputStream,
    ): RepositoryResult<EncryptionEnvelopeInfo> = withContext(dispatchers.io) {
        val contextBytes = when (val encoded = encodeContext(context)) {
            is RepositoryResult.Success -> encoded.value
            is RepositoryResult.Failure -> return@withContext encoded
        }
        try {
            RandomAccessFile(encryptedFile, "r").use { input ->
                val envelope = readEnvelope(input, encryptedFile.length())
                val key = keyProvider.getKey(envelope.info.keyVersion)
                    ?: return@withContext RepositoryResult.Failure(AppError.DecryptionFailure())
                decryptPass(input, envelope, key, contextBytes, output = null)
                currentCoroutineContext().ensureActive()
                decryptPass(input, envelope, key, contextBytes, output = output)
                output.flush()
                RepositoryResult.Success(envelope.info)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AEADBadTagException) {
            RepositoryResult.Failure(AppError.DecryptionFailure(failure))
        } catch (failure: IOException) {
            RepositoryResult.Failure(AppError.DecryptionFailure(failure))
        } catch (failure: GeneralSecurityException) {
            RepositoryResult.Failure(AppError.DecryptionFailure(failure))
        } catch (failure: SecurityException) {
            RepositoryResult.Failure(AppError.DecryptionFailure(failure))
        }
    }

    override suspend fun hasEnvelope(file: File): RepositoryResult<Boolean> = withContext(dispatchers.io) {
        try {
            if (!file.isFile || file.length() < MAGIC.size) {
                RepositoryResult.Success(false)
            } else {
                RandomAccessFile(file, "r").use { input ->
                    val magic = ByteArray(MAGIC.size)
                    input.readFully(magic)
                    RepositoryResult.Success(magic.contentEquals(MAGIC))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            RepositoryResult.Failure(AppError.CorruptedFile)
        } catch (failure: SecurityException) {
            RepositoryResult.Failure(AppError.PermissionDenied)
        }
    }

    private suspend fun verifyOnIo(
        encryptedFile: File,
        context: EncryptionContext,
    ): RepositoryResult<EncryptionEnvelopeInfo> {
        val contextBytes = when (val encoded = encodeContext(context)) {
            is RepositoryResult.Success -> encoded.value
            is RepositoryResult.Failure -> return encoded
        }
        return try {
            RandomAccessFile(encryptedFile, "r").use { input ->
                val envelope = readEnvelope(input, encryptedFile.length())
                val key = keyProvider.getKey(envelope.info.keyVersion)
                    ?: return RepositoryResult.Failure(AppError.DecryptionFailure())
                decryptPass(input, envelope, key, contextBytes, output = null)
                RepositoryResult.Success(envelope.info)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AEADBadTagException) {
            RepositoryResult.Failure(AppError.DecryptionFailure(failure))
        } catch (failure: IOException) {
            RepositoryResult.Failure(AppError.DecryptionFailure(failure))
        } catch (failure: GeneralSecurityException) {
            RepositoryResult.Failure(AppError.DecryptionFailure(failure))
        } catch (failure: SecurityException) {
            RepositoryResult.Failure(AppError.DecryptionFailure(failure))
        }
    }

    private suspend fun decryptPass(
        input: RandomAccessFile,
        envelope: ParsedEnvelope,
        key: SecretKey,
        contextBytes: ByteArray,
        output: OutputStream?,
    ) {
        input.seek(envelope.header.size.toLong())
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_BITS, envelope.nonce),
        )
        cipher.updateAAD(envelope.header)
        cipher.updateAAD(contextBytes)
        var remaining = envelope.ciphertextLength
        var plaintextBytes = 0L
        val buffer = ByteArray(BUFFER_BYTES)
        while (remaining > 0L) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw EOFException("Truncated encrypted payload")
            if (count == 0) continue
            remaining -= count
            cipher.update(buffer, 0, count)?.let { decrypted ->
                plaintextBytes += decrypted.size
                output?.write(decrypted)
            }
        }
        cipher.doFinal().let { decrypted ->
            plaintextBytes += decrypted.size
            output?.write(decrypted)
        }
        if (plaintextBytes != envelope.info.plaintextLength) {
            throw GeneralSecurityException("Plaintext length mismatch")
        }
    }

    private fun readEnvelope(input: RandomAccessFile, fileLength: Long): ParsedEnvelope {
        if (fileLength < MIN_ENVELOPE_BYTES) throw EOFException("Truncated encryption envelope")
        val fixed = ByteArray(FIXED_HEADER_BYTES)
        input.readFully(fixed)
        val fixedBuffer = ByteBuffer.wrap(fixed).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also(fixedBuffer::get)
        if (!magic.contentEquals(MAGIC)) throw GeneralSecurityException("Invalid envelope magic")
        val formatVersion = fixedBuffer.get().toInt() and 0xff
        if (formatVersion != CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION) {
            throw GeneralSecurityException("Unsupported envelope version")
        }
        val keyVersion = fixedBuffer.int
        if (keyVersion <= 0) throw GeneralSecurityException("Invalid key version")
        val nonceLength = fixedBuffer.get().toInt() and 0xff
        if (nonceLength != GCM_NONCE_BYTES) throw GeneralSecurityException("Invalid nonce length")
        val plaintextLength = fixedBuffer.long
        if (plaintextLength !in 0..MAX_PLAINTEXT_BYTES) {
            throw GeneralSecurityException("Invalid plaintext length")
        }
        val nonce = ByteArray(nonceLength)
        input.readFully(nonce)
        val header = fixed + nonce
        val ciphertextLength = plaintextLength + GCM_TAG_BYTES
        if (fileLength != header.size.toLong() + ciphertextLength) {
            throw GeneralSecurityException("Invalid encrypted file length")
        }
        return ParsedEnvelope(
            header = header,
            nonce = nonce,
            ciphertextLength = ciphertextLength,
            info = EncryptionEnvelopeInfo(formatVersion, keyVersion, plaintextLength),
        )
    }

    private fun encodeHeader(keyVersion: Int, nonce: ByteArray, plaintextLength: Long): ByteArray =
        ByteBuffer.allocate(FIXED_HEADER_BYTES + nonce.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC)
            .put(CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION.toByte())
            .putInt(keyVersion)
            .put(nonce.size.toByte())
            .putLong(plaintextLength)
            .put(nonce)
            .array()

    private fun encodeContext(context: EncryptionContext): RepositoryResult<ByteArray> {
        val idBytes = context.recordId.toByteArray(StandardCharsets.UTF_8)
        if (
            context.recordId.isBlank() ||
            idBytes.size > MAX_CONTEXT_ID_BYTES ||
            !SAFE_RECORD_ID.matches(context.recordId)
        ) {
            return RepositoryResult.Failure(
                AppError.InvalidInput("encryption_context", "invalid_record_id"),
            )
        }
        return RepositoryResult.Success(
            ByteBuffer.allocate(CONTEXT_PREFIX.size + 1 + 2 + idBytes.size)
                .order(ByteOrder.BIG_ENDIAN)
                .put(CONTEXT_PREFIX)
                .put(context.purpose.wireCode)
                .putShort(idBytes.size.toShort())
                .put(idBytes)
                .array(),
        )
    }

    private fun moveAtomically(source: File, destination: File, replaceExisting: Boolean) {
        val options = if (replaceExisting) {
            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
        try {
            Files.move(source.toPath(), destination.toPath(), *options)
        } catch (failure: AtomicMoveNotSupportedException) {
            throw IOException("Atomic encrypted-file move is unavailable", failure)
        } catch (failure: UnsupportedOperationException) {
            throw IOException("Atomic encrypted-file move is unavailable", failure)
        }
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private data class ParsedEnvelope(
        val header: ByteArray,
        val nonce: ByteArray,
        val ciphertextLength: Long,
        val info: EncryptionEnvelopeInfo,
    )

    private companion object {
        val MAGIC: ByteArray = byteArrayOf(0x56, 0x4E, 0x45, 0x31) // VNE1
        val CONTEXT_PREFIX: ByteArray = byteArrayOf(0x56, 0x4E, 0x43, 0x31) // VNC1
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BYTES = 16L
        const val GCM_TAG_BITS = 128
        const val FIXED_HEADER_BYTES = 4 + 1 + 4 + 1 + 8
        const val MAX_HEADER_BYTES = FIXED_HEADER_BYTES + GCM_NONCE_BYTES
        const val MIN_ENVELOPE_BYTES = MAX_HEADER_BYTES.toLong() + GCM_TAG_BYTES
        const val BUFFER_BYTES = 64 * 1024
        const val MAX_CONTEXT_ID_BYTES = 128
        const val MAX_PLAINTEXT_BYTES = 100L * 1024L * 1024L
        const val DEFAULT_FREE_SPACE_RESERVE_BYTES = 32L * 1024L * 1024L
        const val PENDING_PREFIX = ".pending-encryption-"
        val SAFE_RECORD_ID = Regex("[A-Za-z0-9_-]+")
    }
}
