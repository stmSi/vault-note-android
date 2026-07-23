package com.vaultnote.core.common

/**
 * Typed failures that may cross a repository boundary.
 *
 * Error instances deliberately contain no note text, filenames, paths, credentials, or raw
 * server responses. A diagnostic cause may be retained in memory for debug tooling, but callers
 * must not serialize or log it in release builds.
 */
sealed interface AppError {
    val isRetryable: Boolean

    data object NetworkUnavailable : AppError {
        override val isRetryable: Boolean = true
    }

    data object AuthenticationExpired : AppError {
        override val isRetryable: Boolean = false
    }

    data object PermissionDenied : AppError {
        override val isRetryable: Boolean = false
    }

    data class FileTooLarge(val maximumBytes: Long) : AppError {
        override val isRetryable: Boolean = false
    }

    data object UnsupportedFile : AppError {
        override val isRetryable: Boolean = false
    }

    data object CorruptedFile : AppError {
        override val isRetryable: Boolean = false
    }

    data class EncryptionFailure(val diagnosticCause: Throwable? = null) : AppError {
        override val isRetryable: Boolean = false
    }

    data class DecryptionFailure(val diagnosticCause: Throwable? = null) : AppError {
        override val isRetryable: Boolean = false
    }

    data class InsufficientStorage(val requiredBytes: Long? = null) : AppError {
        override val isRetryable: Boolean = false
    }

    data class DatabaseFailure(
        val operation: String,
        val diagnosticCause: Throwable? = null,
    ) : AppError {
        override val isRetryable: Boolean =
            diagnosticCause?.isRetryableLocalDatabaseFailure() == true
    }

    data class SynchronizationConflict(val itemId: String) : AppError {
        override val isRetryable: Boolean = false
    }

    data object RemoteQuotaExceeded : AppError {
        override val isRetryable: Boolean = false
    }

    data class BackupValidationFailure(val reason: BackupValidationReason) : AppError {
        override val isRetryable: Boolean = false
    }

    data class ItemNotFound(val itemId: String) : AppError {
        override val isRetryable: Boolean = false
    }

    data class InvalidInput(val field: String, val reason: String) : AppError {
        override val isRetryable: Boolean = false
    }

    data class InvalidItemState(val itemId: String, val state: String) : AppError {
        override val isRetryable: Boolean = false
    }

    /** The local write is durable; only dispatching the queued sync work failed. */
    data class SyncSchedulingFailure(val reason: String) : AppError {
        override val isRetryable: Boolean = true
    }

    enum class BackupValidationReason {
        UNSUPPORTED_VERSION,
        INVALID_MANIFEST,
        CHECKSUM_MISMATCH,
        WRONG_KEY,
        UNSAFE_ARCHIVE_ENTRY,
        INSUFFICIENT_SPACE,
        DUPLICATE_ENTRY,
        MISSING_ENTRY,
        LIMIT_EXCEEDED,
        INVALID_DATA,
    }
}
