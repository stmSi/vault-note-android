package com.vaultnote.core.files

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface PdfMetadataProbe {
    data class Readable(val pageCount: Int) : PdfMetadataProbe
    data object PasswordProtected : PdfMetadataProbe
    data object Unreadable : PdfMetadataProbe
}

/**
 * Reads only non-sensitive PDF metadata. A password is never requested, retained, or guessed.
 *
 * Android reports an encrypted PDF through [SecurityException] when no password is supplied.
 * That is a supported opaque attachment, not a storage permission failure.
 */
internal fun probePdfMetadata(descriptor: ParcelFileDescriptor): PdfMetadataProbe =
    classifyPdfMetadata {
        PdfRenderer(descriptor).use { renderer -> renderer.pageCount }
    }

internal fun classifyPdfMetadata(readPageCount: () -> Int): PdfMetadataProbe = try {
    val pageCount = readPageCount()
    if (pageCount > 0) PdfMetadataProbe.Readable(pageCount) else PdfMetadataProbe.Unreadable
} catch (_: SecurityException) {
    PdfMetadataProbe.PasswordProtected
} catch (_: IOException) {
    PdfMetadataProbe.Unreadable
} catch (_: IllegalArgumentException) {
    PdfMetadataProbe.Unreadable
} catch (_: IllegalStateException) {
    PdfMetadataProbe.Unreadable
}

/**
 * Detects the standard PDF encryption dictionary marker without parsing or decrypting content.
 *
 * The marker is only a fallback for platform renderers that surface encrypted documents as a
 * generic I/O failure. It is not used to establish file integrity or make security decisions.
 */
internal suspend fun containsPdfEncryptionDictionary(file: File): Boolean {
    val marker = PDF_ENCRYPT_MARKER
    var matchedBytes = 0
    var awaitingBoundary = false
    file.inputStream().buffered(PDF_SCAN_BUFFER_BYTES).use { input ->
        val buffer = ByteArray(PDF_SCAN_BUFFER_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) return awaitingBoundary
            for (index in 0 until count) {
                val value = buffer[index]
                if (awaitingBoundary) {
                    if (value.isPdfDelimiter()) return true
                    awaitingBoundary = false
                }
                matchedBytes = when {
                    value == marker[matchedBytes] -> matchedBytes + 1
                    value == marker[0] -> 1
                    else -> 0
                }
                if (matchedBytes == marker.size) {
                    matchedBytes = 0
                    awaitingBoundary = true
                }
            }
        }
    }
}

private fun Byte.isPdfDelimiter(): Boolean = when (toInt() and 0xff) {
    0x00, 0x09, 0x0a, 0x0c, 0x0d, 0x20,
    '('.code, ')'.code, '<'.code, '>'.code, '['.code, ']'.code,
    '{'.code, '}'.code, '/'.code, '%'.code,
    -> true
    else -> false
}

private val PDF_ENCRYPT_MARKER = "/Encrypt".encodeToByteArray()
private const val PDF_SCAN_BUFFER_BYTES = 64 * 1024
