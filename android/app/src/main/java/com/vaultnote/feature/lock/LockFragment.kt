package com.vaultnote.feature.lock

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.vaultnote.R
import com.vaultnote.app.appContainer
import com.vaultnote.databinding.FragmentLockBinding
import kotlinx.coroutines.launch

class LockFragment : Fragment() {
    private var binding: FragmentLockBinding? = null
    private var authenticator: VaultAuthenticator? = null
    private var authenticatorGeneration = 0L
    private val promptSession: LockPromptSessionViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val viewBinding = FragmentLockBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val currentBinding = requireNotNull(binding)
        currentBinding.unlockButton.setOnClickListener {
            requestAuthentication(isManualRequest = true)
        }
        applyInsets(currentBinding)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                requireContext().appContainer().lockManager.state.collect { state ->
                    currentBinding.loadingIndicator.isVisible = !state.isPolicyLoaded
                    currentBinding.unlockButton.isVisible = state.isPolicyLoaded && state.isLocked
                    currentBinding.message.isVisible = state.isPolicyLoaded && state.isLocked
                    if (state.isPolicyLoaded && !state.isLocked) {
                        promptSession.onVaultUnlocked()
                        invalidateAuthenticator()
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                requireContext().appContainer().lockManager.state.collect { state ->
                    if (state.isPolicyLoaded && state.isLocked) {
                        // Recreate the BiometricPrompt wrapper after rotation so AndroidX can
                        // reconnect the callback without launching a second system prompt.
                        getOrCreateAuthenticator()
                    }
                    if (promptSession.claimAutomaticAttempt(state)) {
                        requestAuthentication(isManualRequest = false)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun applyInsets(currentBinding: FragmentLockBinding) {
        val top = currentBinding.root.paddingTop
        val bottom = currentBinding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(currentBinding.root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            currentBinding.root.updatePadding(top = top + safe.top, bottom = bottom + safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(currentBinding.root)
    }

    private fun showMessage(message: Int) {
        binding?.root?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
    }

    private fun requestAuthentication(isManualRequest: Boolean) {
        val prompt = if (isManualRequest) {
            createAuthenticator()
        } else {
            getOrCreateAuthenticator()
        }
        if (!prompt.isAvailable()) {
            promptSession.onPromptFinished()
            invalidateAuthenticator(prompt)
            showMessage(R.string.unlock_unavailable)
            return
        }
        val promptClaimed = if (isManualRequest) {
            promptSession.beginManualPrompt()
            true
        } else {
            promptSession.beginPrompt()
        }
        if (!promptClaimed) return
        try {
            prompt.authenticate()
        } catch (_: IllegalStateException) {
            promptSession.onPromptFinished()
            invalidateAuthenticator(prompt)
            showMessage(R.string.unlock_failed)
        } catch (_: SecurityException) {
            promptSession.onPromptFinished()
            invalidateAuthenticator(prompt)
            showMessage(R.string.unlock_failed)
        }
    }

    private fun getOrCreateAuthenticator(): VaultAuthenticator = authenticator
        ?: createAuthenticator()

    private fun createAuthenticator(): VaultAuthenticator {
        authenticatorGeneration += 1L
        val generation = authenticatorGeneration
        return AndroidVaultAuthenticator(
            fragment = this,
            onSuccess = {
                if (generation != authenticatorGeneration) return@AndroidVaultAuthenticator
                promptSession.onPromptFinished()
                invalidateAuthenticator()
                context?.appContainer()?.lockManager?.unlock()
            },
            onError = { cancelled ->
                if (generation != authenticatorGeneration) return@AndroidVaultAuthenticator
                promptSession.onPromptFinished()
                invalidateAuthenticator()
                if (!cancelled) showMessage(R.string.unlock_failed)
            },
        ).also { authenticator = it }
    }

    private fun invalidateAuthenticator(expected: VaultAuthenticator? = null) {
        if (expected != null && authenticator !== expected) return
        authenticator = null
        authenticatorGeneration += 1L
    }

    companion object {
        fun newInstance(): LockFragment = LockFragment()
    }
}
