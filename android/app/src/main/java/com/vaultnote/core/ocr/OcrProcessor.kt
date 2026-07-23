package com.vaultnote.core.ocr

import java.io.File

enum class OcrFailureCode(val retryable: Boolean) {
    ENGINE_UNAVAILABLE(true),
    PROCESSING_FAILED(true),
    INSUFFICIENT_MEMORY(true),
    TEMPORARY_STORAGE(true),
    CORRUPTED_FILE(false),
    UNSUPPORTED_FORMAT(false),
    PDF_PAGE_LIMIT(false),
}

data class OcrInput(
    val plaintextFile: File,
    val mimeType: String,
    val pdfPageCount: Int?,
)

sealed interface OcrProcessResult {
    data class Success(val text: String) : OcrProcessResult
    data class Failure(val code: OcrFailureCode) : OcrProcessResult
}

/** A lazily invoked boundary around OCR engines; implementations must not retain plaintext. */
fun interface OcrProcessor {
    suspend fun recognize(input: OcrInput): OcrProcessResult
}
