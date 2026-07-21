package com.vaultnote.feature.lock

import androidx.lifecycle.ViewModel
import com.vaultnote.core.security.VaultLockState

/** Retains prompt ownership across rotation without persisting authentication state to disk. */
internal class LockPromptSessionViewModel : ViewModel() {
    private var automaticAttemptedForCurrentLock = false
    private var promptShowing = false

    fun claimAutomaticAttempt(state: VaultLockState): Boolean {
        if (!state.isPolicyLoaded || !state.isLocked || automaticAttemptedForCurrentLock) {
            return false
        }
        automaticAttemptedForCurrentLock = true
        return true
    }

    fun beginPrompt(): Boolean {
        if (promptShowing) return false
        promptShowing = true
        return true
    }

    fun onPromptFinished() {
        promptShowing = false
    }

    fun onVaultUnlocked() {
        automaticAttemptedForCurrentLock = false
        promptShowing = false
    }
}
