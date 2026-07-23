package com.vaultnote.core.security

data class LockPolicy(
    val isLockEnabled: Boolean,
    val backgroundTimeoutMillis: Long,
    val blockScreenshots: Boolean,
) {
    init {
        require(backgroundTimeoutMillis in SUPPORTED_TIMEOUTS)
    }

    companion object {
        val SUPPORTED_TIMEOUTS: Set<Long> = linkedSetOf(0L, 30_000L, 60_000L, 300_000L)
        val DEFAULT: LockPolicy = LockPolicy(
            isLockEnabled = false,
            backgroundTimeoutMillis = 0L,
            blockScreenshots = true,
        )
        val FAIL_CLOSED: LockPolicy = LockPolicy(
            isLockEnabled = true,
            backgroundTimeoutMillis = 0L,
            blockScreenshots = true,
        )
    }
}
