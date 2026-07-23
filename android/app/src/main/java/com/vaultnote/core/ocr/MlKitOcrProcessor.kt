package com.vaultnote.core.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.google.mlkit.common.MlKit
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.vaultnote.core.files.applyExifOrientation
import com.vaultnote.core.files.readExifOrientation
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Offline Latin-script OCR backed by the bundled ML Kit model.
 *
 * The recognizer is created only on the first OCR request. Images are sampled and PDFs are
 * rendered one page at a time, keeping full attachment bytes and full-resolution bitmaps out of
 * memory. The caller owns and removes [OcrInput.plaintextFile].
 */
class MlKitOcrProcessor(context: Context) : OcrProcessor {
    private val applicationContext = context.applicationContext
    private val recognizer: TextRecognizer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MlKit.initialize(applicationContext)
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun recognize(input: OcrInput): OcrProcessResult = try {
        when {
            input.mimeType.startsWith("image/") -> recognizeImage(input.plaintextFile)
            input.mimeType == PDF_MIME_TYPE -> recognizePdf(input)
            else -> OcrProcessResult.Failure(OcrFailureCode.UNSUPPORTED_FORMAT)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: OutOfMemoryError) {
        OcrProcessResult.Failure(OcrFailureCode.INSUFFICIENT_MEMORY)
    } catch (failure: MlKitException) {
        OcrProcessResult.Failure(
            if (failure.errorCode == MlKitException.UNAVAILABLE) {
                OcrFailureCode.ENGINE_UNAVAILABLE
            } else {
                OcrFailureCode.PROCESSING_FAILED
            },
        )
    } catch (_: IOException) {
        OcrProcessResult.Failure(OcrFailureCode.CORRUPTED_FILE)
    } catch (_: IllegalArgumentException) {
        OcrProcessResult.Failure(OcrFailureCode.CORRUPTED_FILE)
    } catch (_: IllegalStateException) {
        OcrProcessResult.Failure(OcrFailureCode.PROCESSING_FAILED)
    }

    private suspend fun recognizeImage(file: java.io.File): OcrProcessResult {
        val bitmap = decodeSampled(file)
            ?: return OcrProcessResult.Failure(OcrFailureCode.CORRUPTED_FILE)
        return recognizeBitmap(bitmap)
    }

    private suspend fun recognizePdf(input: OcrInput): OcrProcessResult {
        if ((input.pdfPageCount ?: 0) > MAX_PDF_PAGES) {
            return OcrProcessResult.Failure(OcrFailureCode.PDF_PAGE_LIMIT)
        }
        val output = StringBuilder()
        ParcelFileDescriptor.open(input.plaintextFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount > MAX_PDF_PAGES) {
                    return OcrProcessResult.Failure(OcrFailureCode.PDF_PAGE_LIMIT)
                }
                for (pageIndex in 0 until renderer.pageCount) {
                    currentCoroutineContext().ensureActive()
                    renderer.openPage(pageIndex).use { page ->
                        val scale = minOf(
                            1f,
                            MAX_BITMAP_DIMENSION.toFloat() / maxOf(page.width, page.height),
                        )
                        val width = (page.width * scale).toInt().coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        appendBounded(output, recognizeBitmapText(bitmap))
                    }
                    if (output.length >= MAX_TEXT_CHARACTERS) break
                }
            }
        }
        return OcrProcessResult.Success(cleanText(output.toString()))
    }

    private suspend fun recognizeBitmap(bitmap: Bitmap): OcrProcessResult =
        OcrProcessResult.Success(cleanText(recognizeBitmapText(bitmap)))

    private suspend fun recognizeBitmapText(bitmap: Bitmap): String =
        suspendCancellableCoroutine { continuation ->
            val task = recognizer.process(InputImage.fromBitmap(bitmap, 0))
            task.addOnCompleteListener { completed ->
                bitmap.recycle()
                if (!continuation.isActive) return@addOnCompleteListener
                val exception = completed.exception
                if (exception != null) {
                    continuation.resumeWith(Result.failure(exception))
                } else {
                    continuation.resume(completed.result?.text.orEmpty())
                }
            }
        }

    private fun decodeSampled(file: java.io.File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (
            bounds.outWidth / sample > MAX_BITMAP_DIMENSION ||
            bounds.outHeight / sample > MAX_BITMAP_DIMENSION ||
            (bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) >
            MAX_BITMAP_PIXELS
        ) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply {
                inJustDecodeBounds = false
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        return applyExifOrientation(decoded, readExifOrientation(file))
    }

    private fun appendBounded(output: StringBuilder, text: String) {
        if (text.isBlank() || output.length >= MAX_TEXT_CHARACTERS) return
        if (output.isNotEmpty()) output.append('\n')
        val remaining = MAX_TEXT_CHARACTERS - output.length
        output.append(text, 0, minOf(text.length, remaining))
    }

    private fun cleanText(raw: String): String = buildString(minOf(raw.length, MAX_TEXT_CHARACTERS)) {
        for (character in raw) {
            if (length >= MAX_TEXT_CHARACTERS) break
            if (character == '\n' || character == '\t' || !character.isISOControl()) append(character)
        }
    }.trim()

    private companion object {
        const val PDF_MIME_TYPE = "application/pdf"
        const val MAX_BITMAP_DIMENSION = 2_048
        const val MAX_BITMAP_PIXELS = 4_194_304L
        const val MAX_PDF_PAGES = 50
        const val MAX_TEXT_CHARACTERS = 200_000
    }
}
