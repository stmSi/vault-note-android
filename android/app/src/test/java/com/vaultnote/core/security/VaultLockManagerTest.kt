package com.vaultnote.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultLockManagerTest {
    @Test
    fun `first enabled policy is fail closed until authentication`() {
        val manager = VaultLockManager(ElapsedRealtimeProvider { 0L })

        manager.applyPolicy(LockPolicy(true, 0L, true))

        assertTrue(manager.state.value.isLocked)
        assertFalse(manager.isContentAccessAllowed())
        manager.unlock()
        assertTrue(manager.isContentAccessAllowed())
    }

    @Test
    fun `disabled policy opens without authentication`() {
        val manager = VaultLockManager(ElapsedRealtimeProvider { 0L })

        manager.applyPolicy(LockPolicy.DEFAULT)

        assertFalse(manager.state.value.isLocked)
        assertTrue(manager.isContentAccessAllowed())
    }

    @Test
    fun `immediate background policy locks`() {
        val manager = VaultLockManager(ElapsedRealtimeProvider { 50L })
        manager.applyPolicy(LockPolicy(true, 0L, true))
        manager.unlock()

        manager.onBackground()

        assertTrue(manager.state.value.isLocked)
    }

    @Test
    fun `configured timeout preserves and then expires session`() {
        var elapsed = 1_000L
        val manager = VaultLockManager(ElapsedRealtimeProvider { elapsed })
        manager.applyPolicy(LockPolicy(true, 30_000L, true))
        manager.unlock()
        manager.onBackground()
        elapsed += 29_999L
        manager.onForeground()
        assertFalse(manager.state.value.isLocked)

        manager.onBackground()
        elapsed += 30_000L
        manager.onForeground()

        assertTrue(manager.state.value.isLocked)
    }

    @Test
    fun `rotation style foreground callback does not create a lock timer`() {
        val manager = VaultLockManager(ElapsedRealtimeProvider { 100_000L })
        manager.applyPolicy(LockPolicy(true, 0L, true))
        manager.unlock()

        manager.onForeground()

        assertFalse(manager.state.value.isLocked)
    }
}
