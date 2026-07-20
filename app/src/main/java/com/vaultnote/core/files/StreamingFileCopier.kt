package com.vaultnote.core.files

import android.os.StatFs
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.RepositoryResult
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun interface AvailableSpaceProvider {
    fun availableBytes(directory: File): Long
}

internal data class StreamCopyResult(
    val byteCount: Long,
    val sha256: String,
)

internal class StreamingFileCopier(
    private val availableSpaceProvider: AvailableSpaceProvider =
        AvailableSpaceProvider { directory -> StatFs(directory.path).availableBytes },
    private val maximumBytes: Long = MAX_ATTACHMENT_BYTES,
    private val freeSpaceReserveBytes: Long = MINIMUM_FREE_SPACE_RESERVE_BYTES,
) {
    suspend fun copy(
        input: InputStream,
        temporaryFile: File,
        storageDirectory: File,
        declaredSize: Long?,
    ): RepositoryResult<StreamCopyResult> {
        return try {
            if (declaredSize != null && declaredSize > maximumBytes) {
                return RepositoryResult.Failure(AppError.FileTooLarge(maximumBytes))
            }
            val initialPayloadBytes = declaredSize?.coerceAtLeast(0L) ?: COPY_BUFFER_BYTES.toLong()
            val initialRequiredBytes = safeAdd(initialPayloadBytes, freeSpaceReserveBytes)
            if (availableSpaceProvider.availableBytes(storageDirectory) < initialRequiredBytes) {
                return RepositoryResult.Failure(AppError.InsufficientStorage(initialRequiredBytes))
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            var bytesAtLastSpaceCheck = 0L
            FileOutputStream(temporaryFile).use { fileOutput ->
                val output = BufferedOutputStream(fileOutput, COPY_BUFFER_BYTES)
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val readCount = input.read(buffer)
                    val count = if (readCount == 0) {
                        val singleByte = input.read()
                        if (singleByte < 0) break
                        buffer[0] = singleByte.toByte()
                        1
                    } else {
                        readCount
                    }
                    if (count < 0) break
                    if (totalBytes > maximumBytes - count) {
                        return failureAndDelete(
                            temporaryFile,
                            AppError.FileTooLarge(maximumBytes),
                        )
                    }
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    totalBytes += count
                    if (totalBytes - bytesAtLastSpaceCheck >= SPACE_CHECK_INTERVAL_BYTES) {
                        bytesAtLastSpaceCheck = totalBytes
                        if (
                            availableSpaceProvider.availableBytes(storageDirectory) <
                            freeSpaceReserveBytes
                        ) {
                            return failureAndDelete(
                                temporaryFile,
                                AppError.InsufficientStorage(freeSpaceReserveBytes),
                            )
                        }
                    }
                }
                if (availableSpaceProvider.availableBytes(storageDirectory) < freeSpaceReserveBytes) {
                    return failureAndDelete(
                        temporaryFile,
                        AppError.InsufficientStorage(freeSpaceReserveBytes),
                    )
                }
                output.flush()
                fileOutput.fd.sync()
            }
            RepositoryResult.Success(
                StreamCopyResult(
                    byteCount = totalBytes,
                    sha256 = digest.digest().toLowerHex(),
                ),
            )
        } catch (cancelled: CancellationException) {
            temporaryFile.delete()
            throw cancelled
        } catch (_: SecurityException) {
            failureAndDelete(temporaryFile, AppError.PermissionDenied)
        } catch (_: IOException) {
            failureAndDelete(temporaryFile, AppError.CorruptedFile)
        }
    }

    private fun failureAndDelete(file: File, error: AppError): RepositoryResult.Failure {
        file.delete()
        return RepositoryResult.Failure(error)
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun ByteArray.toLowerHex(): String {
        val result = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            result[index * 2] = HEX_DIGITS[value ushr 4]
            result[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }
        return result.concatToString()
    }

    companion object {
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val SPACE_CHECK_INTERVAL_BYTES = 1024L * 1024L
        private const val HEX_DIGITS = "0123456789abcdef"
    }
}
