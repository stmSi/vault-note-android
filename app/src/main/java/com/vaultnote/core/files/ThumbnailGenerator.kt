package com.vaultnote.core.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.DefaultDispatcherProvider
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.RepositoryResult
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

interface ThumbnailGenerator {
    suspend fun generate(
        source: File,
        mimeType: String,
        destination: File,
    ): RepositoryResult<ThumbnailMetadata?>
}

class AndroidThumbnailGenerator(
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
    maximumParallelism: Int = DEFAULT_MAXIMUM_PARALLELISM,
) : ThumbnailGenerator {
    private val semaphore = Semaphore(maximumParallelism.coerceAtLeast(1))

    override suspend fun generate(
        source: File,
        mimeType: String,
        destination: File,
    ): RepositoryResult<ThumbnailMetadata?> = semaphore.withPermit {
        withContext(dispatchers.io) {
            coroutineContext.ensureActive()
            if (destination.exists()) {
                return@withContext RepositoryResult.Failure(
                    AppError.InvalidInput(field = "thumbnail", reason = "already_exists"),
                )
            }
            val bitmapResult = when {
                mimeType.startsWith("image/") -> decodeSampledImage(source)
                mimeType == "application/pdf" -> renderPdfFirstPage(source)
                else -> RepositoryResult.Success(null)
            }
            when (bitmapResult) {
                is RepositoryResult.Failure -> bitmapResult
                is RepositoryResult.Success -> {
                    val bitmap = bitmapResult.value ?: return@withContext RepositoryResult.Success(null)
                    try {
                        coroutineContext.ensureActive()
                        writeBitmapAtomically(bitmap, destination)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun decodeSampledImage(source: File): RepositoryResult<Bitmap?> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return RepositoryResult.Failure(AppError.CorruptedFile)
        }
        if (bounds.outWidth > MAX_SOURCE_DIMENSION || bounds.outHeight > MAX_SOURCE_DIMENSION) {
            return RepositoryResult.Failure(AppError.UnsupportedFile)
        }
        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > DECODE_TARGET_DIMENSION ||
            bounds.outHeight / sampleSize > DECODE_TARGET_DIMENSION ||
            (bounds.outWidth.toLong() / sampleSize) * (bounds.outHeight.toLong() / sampleSize) >
            MAX_DECODED_PIXELS
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(source.path, options)
            ?: return RepositoryResult.Failure(AppError.CorruptedFile)
        val oriented = applyExifOrientation(decoded, readExifOrientation(source))
        return RepositoryResult.Success(scaleToThumbnail(oriented))
    }

    private fun renderPdfFirstPage(source: File): RepositoryResult<Bitmap?> {
        return try {
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount < 1) return RepositoryResult.Failure(AppError.CorruptedFile)
                    renderer.openPage(0).use { page ->
                        val scale = minOf(
                            1.0,
                            THUMBNAIL_MAX_DIMENSION.toDouble() / maxOf(page.width, page.height).toDouble(),
                        )
                        val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                        val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        RepositoryResult.Success(bitmap)
                    }
                }
            }
        } catch (_: SecurityException) {
            RepositoryResult.Failure(AppError.PermissionDenied)
        } catch (_: IOException) {
            RepositoryResult.Failure(AppError.CorruptedFile)
        } catch (_: IllegalStateException) {
            RepositoryResult.Failure(AppError.CorruptedFile)
        }
    }

    private fun scaleToThumbnail(decoded: Bitmap): Bitmap {
        val largestDimension = maxOf(decoded.width, decoded.height)
        if (largestDimension <= THUMBNAIL_MAX_DIMENSION) return decoded
        val scale = THUMBNAIL_MAX_DIMENSION.toDouble() / largestDimension.toDouble()
        val width = (decoded.width * scale).roundToInt().coerceAtLeast(1)
        val height = (decoded.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = decoded.scale(width, height, filter = true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun writeBitmapAtomically(
        bitmap: Bitmap,
        destination: File,
    ): RepositoryResult<ThumbnailMetadata?> {
        val parent = destination.parentFile
            ?: return RepositoryResult.Failure(AppError.InvalidInput("thumbnail", "invalid_destination"))
        val temporary = File(parent, ".pending-thumbnail-${UUID.randomUUID()}.tmp")
        return try {
            FileOutputStream(temporary).use { fileOutput ->
                val output = BufferedOutputStream(fileOutput)
                @Suppress("DEPRECATION")
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    Bitmap.CompressFormat.WEBP
                }
                if (!bitmap.compress(format, THUMBNAIL_QUALITY, output)) {
                    return failureAndDelete(temporary, AppError.CorruptedFile)
                }
                output.flush()
                fileOutput.fd.sync()
            }
            moveAtomically(temporary, destination)
            RepositoryResult.Success(
                ThumbnailMetadata(bitmap.width, bitmap.height, THUMBNAIL_MIME_TYPE),
            )
        } catch (cancelled: CancellationException) {
            temporary.delete()
            throw cancelled
        } catch (_: SecurityException) {
            failureAndDelete(temporary, AppError.PermissionDenied)
        } catch (_: IOException) {
            failureAndDelete(temporary, AppError.InsufficientStorage())
        }
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            if (!source.renameTo(destination)) throw IOException("Atomic rename failed")
        }
    }

    private fun failureAndDelete(file: File, error: AppError): RepositoryResult.Failure {
        file.delete()
        return RepositoryResult.Failure(error)
    }

    companion object {
        private const val DEFAULT_MAXIMUM_PARALLELISM = 2
        private const val THUMBNAIL_MAX_DIMENSION = 512
        private const val DECODE_TARGET_DIMENSION = THUMBNAIL_MAX_DIMENSION * 2
        private const val MAX_SOURCE_DIMENSION = 100_000
        private const val MAX_DECODED_PIXELS = 2_000_000L
        private const val THUMBNAIL_QUALITY = 82
        private const val THUMBNAIL_MIME_TYPE = "image/webp"
    }
}
