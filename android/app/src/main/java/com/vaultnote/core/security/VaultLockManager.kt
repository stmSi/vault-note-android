package com.vaultnote.core.security

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

fun interface ElapsedRealtimeProvider {
    fun nowMillis(): Long
}

data class VaultLockState(
    val isPolicyLoaded: Boolean,
    val policy: LockPolicy,
    val isLocked: Boolean,
)

/** Holds only ephemeral authentication state; no note, file, credential, or key data is retained. */
class VaultLockManager(
    private val elapsedRealtime: ElapsedRealtimeProvider =
        ElapsedRealtimeProvider(SystemClock::elapsedRealtime),
) {
    private val mutableState = MutableStateFlow(
        VaultLockState(
            isPolicyLoaded = false,
            policy = LockPolicy.FAIL_CLOSED,
            isLocked = true,
        ),
    )
    private var backgroundedAt: Long? = null

    val state: StateFlow<VaultLockState> = mutableState.asStateFlow()

    @Synchronized
    fun applyPolicy(policy: LockPolicy) {
        val previous = mutableState.value
        val locked = when {
            !policy.isLockEnabled -> false
            !previous.isPolicyLoaded -> true
            !previous.policy.isLockEnabled -> previous.isLocked
            else -> previous.isLocked
        }
        mutableState.value = VaultLockState(true, policy, locked)
    }

    @Synchronized
    fun unlock() {
        val current = mutableState.value
        if (!current.isPolicyLoaded) return
        backgroundedAt = null
        mutableState.value = current.copy(isLocked = false)
    }

    @Synchronized
    fun lockNow() {
        val current = mutableState.value
        if (!current.isPolicyLoaded || !current.policy.isLockEnabled) return
        backgroundedAt = null
        mutableState.value = current.copy(isLocked = true)
    }

    @Synchronized
    fun onBackground() {
        val current = mutableState.value
        if (!current.isPolicyLoaded || !current.policy.isLockEnabled) return
        backgroundedAt = elapsedRealtime.nowMillis()
        if (current.policy.backgroundTimeoutMillis == 0L) {
            mutableState.value = current.copy(isLocked = true)
        }
    }

    @Synchronized
    fun onForeground() {
        val current = mutableState.value
        val started = backgroundedAt ?: return
        backgroundedAt = null
        if (!current.isPolicyLoaded || !current.policy.isLockEnabled) return
        val elapsed = (elapsedRealtime.nowMillis() - started).coerceAtLeast(0L)
        if (elapsed >= current.policy.backgroundTimeoutMillis) {
            mutableState.value = current.copy(isLocked = true)
        }
    }

    fun isContentAccessAllowed(): Boolean {
        val current = mutableState.value
        return current.isPolicyLoaded && !current.isLocked
    }
}
