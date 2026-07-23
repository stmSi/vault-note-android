package com.vaultnote.core.files

import java.text.Normalizer

data class AttachmentFilenameSearchPatterns(
    val contiguous: String,
    val subsequence: String,
)

/** Builds bounded, escaped SQLite LIKE patterns for attachment display names only. */
object AttachmentFilenameSearch {
    const val MAX_QUERY_CODE_POINTS: Int = 200

    fun compile(input: String): AttachmentFilenameSearchPatterns? {
        val normalized = Normalizer.normalize(input.trim(), Normalizer.Form.NFC)
            .takeCodePoints(MAX_QUERY_CODE_POINTS)
        if (normalized.isEmpty()) return null
        val contiguous = buildString(normalized.length + 2) {
            append('%')
            normalized.codePoints().forEach { appendEscaped(it) }
            append('%')
        }
        val subsequence = buildString(normalized.length * 2 + 1) {
            append('%')
            normalized.codePoints().forEach { codePoint ->
                appendEscaped(codePoint)
                append('%')
            }
        }
        return AttachmentFilenameSearchPatterns(contiguous, subsequence)
    }

    fun boundInput(input: String): String = input.takeCodePoints(MAX_QUERY_CODE_POINTS)

    private fun String.takeCodePoints(maximum: Int): String = if (
        codePointCount(0, length) <= maximum
    ) {
        this
    } else {
        substring(0, offsetByCodePoints(0, maximum))
    }

    private fun StringBuilder.appendEscaped(codePoint: Int) {
        if (codePoint == '%'.code || codePoint == '_'.code || codePoint == '\\'.code) {
            append('\\')
        }
        appendCodePoint(codePoint)
    }
}
