package com.vaultnote.feature.lock

import com.vaultnote.core.security.LockPolicy
import com.vaultnote.core.security.VaultLockState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockPromptSessionViewModelTest {
    private val locked = VaultLockState(
        isPolicyLoaded = true,
        policy = LockPolicy.FAIL_CLOSED,
        isLocked = true,
    )

    @Test
    fun `automatic prompt is claimed once for a locked session`() {
        val session = LockPromptSessionViewModel()

        assertTrue(session.claimAutomaticAttempt(locked))
        assertFalse(session.claimAutomaticAttempt(locked))
    }

    @Test
    fun `unlock permits an automatic prompt for the next lock`() {
        val session = LockPromptSessionViewModel()
        session.claimAutomaticAttempt(locked)

        session.onVaultUnlocked()

        assertTrue(session.claimAutomaticAttempt(locked))
    }

    @Test
    fun `only one biometric prompt may own the session`() {
        val session = LockPromptSessionViewModel()

        assertTrue(session.beginPrompt())
        assertFalse(session.beginPrompt())
        session.onPromptFinished()
        assertTrue(session.beginPrompt())
    }
}
