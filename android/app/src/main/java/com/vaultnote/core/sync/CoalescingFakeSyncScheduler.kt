package com.vaultnote.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase-one scheduler fake. It records a single outstanding request and intentionally never reads
 * or completes persistent sync operations.
 */
class CoalescingFakeSyncScheduler : SyncScheduler {
    private val lock = Any()
    private var outstanding = false
    private var acceptedCountValue = 0L
    private val mutableRequested = MutableStateFlow(false)

    val isSyncRequested: StateFlow<Boolean> = mutableRequested.asStateFlow()

    val acceptedRequestCount: Long
        get() = synchronized(lock) { acceptedCountValue }

    override fun requestSync(): SyncScheduleResult = synchronized(lock) {
        if (!outstanding) {
            outstanding = true
            acceptedCountValue += 1L
            mutableRequested.value = true
            SyncScheduleResult.Scheduled
        } else {
            SyncScheduleResult.Coalesced
        }
    }

    /**
     * Clears only the in-memory scheduling signal. It does not inspect or mutate the Room queue.
     * This is useful for tests that simulate WorkManager accepting a unique work request.
     */
    fun acknowledgeScheduleRequest(): Boolean = synchronized(lock) {
        if (!outstanding) return@synchronized false
        outstanding = false
        mutableRequested.value = false
        true
    }
}
