package com.vaultnote.core.files

import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.RepositoryResult
import java.text.Normalizer
import java.util.Locale

object FilenameSanitizer {
    private const val MAX_CODE_POINTS = 180
    private val windowsReservedNames = buildSet {
        addAll(listOf("con", "prn", "aux", "nul"))
        (1..9).forEach { number ->
            add("com$number")
            add("lpt$number")
        }
    }

    fun sanitize(untrustedName: String): RepositoryResult<String> {
        if (untrustedName.isBlank() || containsMalformedUnicode(untrustedName)) {
            return invalidFilename("malformed")
        }
        if (
            untrustedName.indexOf('/') >= 0 ||
            untrustedName.indexOf('\\') >= 0 ||
            untrustedName.indexOf('\u0000') >= 0
        ) {
            return invalidFilename("unsafe_path")
        }

        val normalized = Normalizer.normalize(untrustedName, Normalizer.Form.NFC)
        val cleaned = buildString(normalized.length) {
            normalized.codePoints().forEach { codePoint ->
                when {
                    isInvisibleOrControl(codePoint) -> Unit
                    isPortableReservedCharacter(codePoint) -> append('_')
                    else -> appendCodePoint(codePoint)
                }
            }
        }.trim().trimEnd('.', ' ')

        if (cleaned.isBlank() || cleaned == "." || cleaned == "..") {
            return invalidFilename("empty_after_sanitizing")
        }

        val safeName = truncatePreservingExtension(cleaned, MAX_CODE_POINTS)
        if (safeName.isBlank() || safeName == "." || safeName == "..") {
            return invalidFilename("empty_after_sanitizing")
        }

        val baseName = safeName.substringBeforeLast('.', safeName).lowercase(Locale.ROOT)
        val portableName = if (baseName in windowsReservedNames) "_$safeName" else safeName
        return RepositoryResult.Success(portableName)
    }

    private fun containsMalformedUnicode(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return true
                index += 2
            } else {
                if (Character.isLowSurrogate(character)) return true
                index += 1
            }
        }
        return false
    }

    private fun isInvisibleOrControl(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
        Character.SURROGATE.toInt(),
        Character.UNASSIGNED.toInt(),
        -> true
        else -> false
    }

    private fun isPortableReservedCharacter(codePoint: Int): Boolean = when (codePoint) {
        ':'.code, '*'.code, '?'.code, '"'.code, '<'.code, '>'.code, '|'.code -> true
        else -> false
    }

    private fun truncatePreservingExtension(value: String, maximumCodePoints: Int): String {
        if (value.codePointCount(0, value.length) <= maximumCodePoints) return value
        val lastDot = value.lastIndexOf('.')
        val extension = if (lastDot in 1 until value.lastIndex) value.substring(lastDot) else ""
        val extensionPoints = extension.codePointCount(0, extension.length)
        if (extensionPoints >= maximumCodePoints - 1) {
            return value.substring(0, value.offsetByCodePoints(0, maximumCodePoints)).trimEnd('.', ' ')
        }
        val base = if (extension.isEmpty()) value else value.substring(0, lastDot)
        val allowedBasePoints = maximumCodePoints - extensionPoints
        val truncatedBase = base.substring(0, base.offsetByCodePoints(0, allowedBasePoints)).trimEnd('.', ' ')
        return truncatedBase + extension
    }

    private fun invalidFilename(reason: String): RepositoryResult.Failure =
        RepositoryResult.Failure(AppError.InvalidInput(field = "filename", reason = reason))
}
