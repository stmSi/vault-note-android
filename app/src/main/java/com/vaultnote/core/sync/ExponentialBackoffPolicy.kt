package com.vaultnote.core.sync

import kotlin.math.min

class ExponentialBackoffPolicy(
    private val initialDelayMillis: Long = 30_000L,
    private val maximumDelayMillis: Long = 6L * 60L * 60L * 1_000L,
    private val maximumAttempts: Int = 10,
) {
    init {
        require(initialDelayMillis > 0L)
        require(maximumDelayMillis >= initialDelayMillis)
        require(maximumAttempts > 0)
    }

    fun decision(completedAttempts: Int, error: RemoteErrorCode): RetryDecision {
        if (!error.retryable || completedAttempts >= maximumAttempts) {
            return RetryDecision.Permanent
        }
        val exponent = completedAttempts.coerceIn(0, 30)
        val multiplier = 1L shl exponent
        val delay = if (initialDelayMillis > Long.MAX_VALUE / multiplier) {
            maximumDelayMillis
        } else {
            min(initialDelayMillis * multiplier, maximumDelayMillis)
        }
        return RetryDecision.RetryAfter(delay)
    }
}

sealed interface RetryDecision {
    data class RetryAfter(val delayMillis: Long) : RetryDecision
    data object Permanent : RetryDecision
}
