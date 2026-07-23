package com.vaultnote.feature.importing

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PendingCameraCapture(
    val captureId: String,
    val source: ImportSource,
)

/**
 * Owns camera staging files under the app cache. Restoration accepts only a UUID and reconstructs
 * the path after canonical confinement checks; callers never persist a path or content URI.
 */
internal class CameraCaptureManager(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val applicationContext = context.applicationContext

    suspend fun createCapture(): Result<PendingCameraCapture> = withContext(ioDispatcher) {
        var reservedFile: File? = null
        try {
            val directory = File(applicationContext.cacheDir, CAPTURE_DIRECTORY)
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Could not create the camera capture directory")
            }
            cleanupStaleCaptures(directory)
            val captureId = UUID.randomUUID().toString()
            val file = File(directory, "capture-$captureId.jpg")
            if (!file.createNewFile()) throw IOException("Could not reserve a camera capture file")
            reservedFile = file
            val uri = try {
                FileProvider.getUriForFile(
                    applicationContext,
                    "${applicationContext.packageName}.files",
                    file,
                )
            } catch (failure: RuntimeException) {
                file.delete()
                throw failure
            }
            Result.success(
                PendingCameraCapture(
                    captureId = captureId,
                    source = ImportSource(
                        uri = uri,
                        kind = ImportSourceKind.CAMERA_CAPTURE,
                        temporaryFile = file,
                        captureId = captureId,
                    ),
                ),
            )
        } catch (cancellation: CancellationException) {
            reservedFile?.delete()
            throw cancellation
        } catch (failure: IOException) {
            reservedFile?.delete()
            Result.failure(failure)
        } catch (failure: SecurityException) {
            reservedFile?.delete()
            Result.failure(failure)
        } catch (failure: IllegalArgumentException) {
            reservedFile?.delete()
            Result.failure(failure)
        } catch (failure: IllegalStateException) {
            reservedFile?.delete()
            Result.failure(failure)
        }
    }

    suspend fun restoreCapture(captureId: String): PendingCameraCapture? = withContext(ioDispatcher) {
        if (!CAPTURE_ID.matches(captureId)) return@withContext null
        try {
            val directory = File(applicationContext.cacheDir, CAPTURE_DIRECTORY).canonicalFile
            val file = File(directory, "capture-$captureId.jpg").canonicalFile
            if (file.parentFile != directory || !file.isFile) return@withContext null
            val uri = FileProvider.getUriForFile(
                applicationContext,
                "${applicationContext.packageName}.files",
                file,
            )
            PendingCameraCapture(
                captureId = captureId,
                source = ImportSource(
                    uri = uri,
                    kind = ImportSourceKind.CAMERA_CAPTURE,
                    temporaryFile = file,
                    captureId = captureId,
                ),
            )
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    suspend fun deleteCapture(capture: PendingCameraCapture?) = withContext(ioDispatcher) {
        val file = capture?.source?.temporaryFile ?: return@withContext
        if (!CAPTURE_ID.matches(capture.captureId)) return@withContext
        try {
            val directory = File(applicationContext.cacheDir, CAPTURE_DIRECTORY).canonicalFile
            val expected = File(directory, "capture-${capture.captureId}.jpg").canonicalFile
            if (file.canonicalFile == expected && expected.parentFile == directory) {
                file.delete()
            }
        } catch (_: IOException) {
            Unit
        } catch (_: SecurityException) {
            Unit
        }
    }

    private fun cleanupStaleCaptures(directory: File) {
        val cutoff = System.currentTimeMillis() - STALE_CAPTURE_MILLIS
        directory.listFiles()
            ?.asSequence()
            ?.take(MAX_STALE_FILES_PER_RUN)
            ?.filter { file ->
                file.isFile &&
                    CAPTURE_NAME.matches(file.name) &&
                    file.lastModified() in 1 until cutoff
            }
            ?.forEach(File::delete)
    }

    companion object {
        const val CAPTURE_DIRECTORY: String = "camera-captures"
        private const val STALE_CAPTURE_MILLIS = 24L * 60L * 60L * 1_000L
        private const val MAX_STALE_FILES_PER_RUN = 128
        private val CAPTURE_ID = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        )
        private val CAPTURE_NAME = Regex(
            "capture-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg",
        )
    }
}
