package com.vaultnote.core.sync

/**
 * Coalesces durable synchronization requests. Persisting queue entries is the repository's job;
 * an implementation must never treat scheduling as proof that an operation completed.
 */
fun interface SyncScheduler {
    fun requestSync(): SyncScheduleResult
}

sealed interface SyncScheduleResult {
    data object Scheduled : SyncScheduleResult
    data object Coalesced : SyncScheduleResult

    /** A non-sensitive reason suitable for diagnostics and a generic user-facing message. */
    data class Rejected(val reason: String) : SyncScheduleResult
}
