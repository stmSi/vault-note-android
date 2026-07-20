package com.vaultnote.core.common

/** A repository result with an optional non-fatal warning for a committed local operation. */
sealed interface RepositoryResult<out T> {
    data class Success<T>(
        val value: T,
        val warning: AppError? = null,
    ) : RepositoryResult<T>

    data class Failure(val error: AppError) : RepositoryResult<Nothing>
}

val RepositoryResult<*>.isSuccess: Boolean
    get() = this is RepositoryResult.Success

fun <T> RepositoryResult<T>.getOrNull(): T? =
    (this as? RepositoryResult.Success)?.value

inline fun <T, R> RepositoryResult<T>.fold(
    onSuccess: (value: T, warning: AppError?) -> R,
    onFailure: (error: AppError) -> R,
): R = when (this) {
    is RepositoryResult.Success -> onSuccess(value, warning)
    is RepositoryResult.Failure -> onFailure(error)
}
