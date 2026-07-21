package com.vaultnote.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExponentialBackoffPolicyTest {
    private val policy = ExponentialBackoffPolicy(
        initialDelayMillis = 1_000L,
        maximumDelayMillis = 8_000L,
        maximumAttempts = 4,
    )

    @Test
    fun `transient failures back off exponentially and cap`() {
        assertEquals(
            RetryDecision.RetryAfter(1_000L),
            policy.decision(0, RemoteErrorCode.NETWORK_UNAVAILABLE),
        )
        assertEquals(
            RetryDecision.RetryAfter(8_000L),
            policy.decision(3, RemoteErrorCode.SERVER_UNAVAILABLE),
        )
    }

    @Test
    fun `permanent errors and exhausted attempts do not retry`() {
        assertTrue(policy.decision(0, RemoteErrorCode.INVALID_REQUEST) is RetryDecision.Permanent)
        assertTrue(policy.decision(4, RemoteErrorCode.NETWORK_UNAVAILABLE) is RetryDecision.Permanent)
    }
}
