package com.vaultnote.core.files

import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.RepositoryResult
import java.util.Locale

/**
 * Validates user-visible attachment names without changing the stored file or its format.
 *
 * A rename without an extension keeps the current extension. An explicitly supplied extension
 * must describe the already-validated attachment format, preventing a misleading executable or
 * document suffix from being assigned to different content.
 */
object AttachmentFilenamePolicy {
    fun rename(
        requestedName: String,
        currentName: String,
        format: AttachmentFormat,
    ): RepositoryResult<String> {
        val sanitized = when (val result = FilenameSanitizer.sanitize(requestedName)) {
            is RepositoryResult.Failure -> return result
            is RepositoryResult.Success -> result.value
        }
        val suppliedExtension = sanitized.portableExtension()
        if (suppliedExtension != null) {
            return if (suppliedExtension.lowercase(Locale.ROOT) in format.extensions) {
                RepositoryResult.Success(sanitized)
            } else {
                extensionMismatch()
            }
        }

        val currentExtension = currentName.portableExtension()
            ?.lowercase(Locale.ROOT)
            ?.takeIf(format.extensions::contains)
            ?: format.extensions.first()
        return FilenameSanitizer.sanitize("$sanitized.$currentExtension")
    }

    fun renameForMimeType(
        requestedName: String,
        currentName: String,
        mimeType: String,
    ): RepositoryResult<String> {
        val format = AttachmentFormat.entries.firstOrNull { candidate ->
            candidate.canonicalMimeType.equals(mimeType, ignoreCase = true) ||
                candidate.acceptedMimeTypes.any { it.equals(mimeType, ignoreCase = true) }
        } ?: return RepositoryResult.Failure(AppError.UnsupportedFile)
        return rename(requestedName, currentName, format)
    }

    private fun String.portableExtension(): String? {
        val dot = lastIndexOf('.')
        return if (dot in 1 until lastIndex) substring(dot + 1) else null
    }

    private fun extensionMismatch(): RepositoryResult.Failure = RepositoryResult.Failure(
        AppError.InvalidInput(field = "filename", reason = "extension_mismatch"),
    )
}
