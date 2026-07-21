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
            val prompt = getOrCreateAuthenticator()
            if (prompt.isAvailable()) {
                prompt.authenticate()
            } else {
                showMessage(R.string.unlock_unavailable)
            }
        }
        applyInsets(currentBinding)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                requireContext().appContainer().lockManager.state.collect { state ->
                    currentBinding.loadingIndicator.isVisible = !state.isPolicyLoaded
                    currentBinding.unlockButton.isVisible = state.isPolicyLoaded && state.isLocked
                    currentBinding.message.isVisible = state.isPolicyLoaded && state.isLocked
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

    private fun getOrCreateAuthenticator(): VaultAuthenticator = authenticator
        ?: AndroidVaultAuthenticator(
            fragment = this,
            onSuccess = { requireContext().appContainer().lockManager.unlock() },
            onError = { cancelled ->
                if (!cancelled) showMessage(R.string.unlock_failed)
            },
        ).also { authenticator = it }

    companion object {
        fun newInstance(): LockFragment = LockFragment()
    }
}
