package com.vaultnote.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.vaultnote.R
import com.vaultnote.app.appContainer
import com.vaultnote.app.MainNavigator
import com.vaultnote.core.security.LockPolicy
import com.vaultnote.databinding.FragmentSecuritySettingsBinding
import com.vaultnote.feature.lock.AndroidVaultAuthenticator
import com.vaultnote.feature.lock.VaultAuthenticator
import kotlinx.coroutines.launch

class SecuritySettingsFragment : Fragment() {
    private var binding: FragmentSecuritySettingsBinding? = null
    private var isRendering = false
    private lateinit var authenticator: VaultAuthenticator
    private val viewModel: SecuritySettingsViewModel by viewModels {
        val container = requireContext().appContainer()
        SecuritySettingsViewModel.Factory(container.lockPolicyRepository, container.lockManager)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val viewBinding = FragmentSecuritySettingsBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val currentBinding = requireNotNull(binding)
        authenticator = AndroidVaultAuthenticator(
            fragment = this,
            onSuccess = viewModel::confirmLockEnabled,
            onError = { cancelled -> if (!cancelled) showMessage(R.string.unlock_failed) },
        )
        val timeoutLabels = timeoutOptions().map(TimeoutOption::label)
        currentBinding.timeoutInput.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, timeoutLabels),
        )
        currentBinding.lockSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!isRendering) viewModel.requestLockEnabled(enabled)
        }
        currentBinding.screenshotSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!isRendering) viewModel.setScreenshotBlocking(enabled)
        }
        currentBinding.timeoutInput.setOnItemClickListener { _, _, position, _ ->
            if (!isRendering) timeoutOptions().getOrNull(position)?.let {
                viewModel.setBackgroundTimeout(it.millis)
            }
        }
        currentBinding.retryButton.setOnClickListener { viewModel.retry() }
        currentBinding.syncStatusButton.setOnClickListener {
            (activity as? MainNavigator)?.openSyncStatus()
        }
        currentBinding.conflictsButton.setOnClickListener {
            (activity as? MainNavigator)?.openConflicts()
        }
        currentBinding.backupExportButton.setOnClickListener {
            (activity as? MainNavigator)?.openBackupExport()
        }
        currentBinding.backupRestoreButton.setOnClickListener {
            (activity as? MainNavigator)?.openBackupRestore()
        }
        applyInsets(currentBinding)
        collectState(currentBinding)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun collectState(currentBinding: FragmentSecuritySettingsBinding) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(currentBinding, it) } }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            SecuritySettingsEvent.RequestAuthentication -> {
                                if (authenticator.isAvailable()) {
                                    authenticator.authenticate()
                                } else {
                                    showMessage(R.string.unlock_unavailable)
                                    render(currentBinding, viewModel.state.value)
                                }
                            }
                            SecuritySettingsEvent.ShowSaveError ->
                                showMessage(R.string.security_settings_failed)
                        }
                    }
                }
            }
        }
    }

    private fun render(
        currentBinding: FragmentSecuritySettingsBinding,
        state: SecuritySettingsState,
    ) {
        currentBinding.loadingIndicator.isVisible = state is SecuritySettingsState.Loading
        currentBinding.content.isVisible = state is SecuritySettingsState.Content
        currentBinding.errorState.isVisible = state is SecuritySettingsState.Error
        if (state !is SecuritySettingsState.Content) return
        isRendering = true
        try {
            currentBinding.lockSwitch.isChecked = state.policy.isLockEnabled
            currentBinding.screenshotSwitch.isChecked = state.policy.blockScreenshots
            val option = timeoutOptions().first { it.millis == state.policy.backgroundTimeoutMillis }
            currentBinding.timeoutInput.setText(option.label, false)
        } finally {
            isRendering = false
        }
        currentBinding.savingIndicator.isVisible = state.isSaving
        currentBinding.lockSwitch.isEnabled = !state.isSaving
        currentBinding.screenshotSwitch.isEnabled = !state.isSaving
        currentBinding.timeoutInput.isEnabled = !state.isSaving && state.policy.isLockEnabled
        currentBinding.timeoutContainer.isEnabled = !state.isSaving && state.policy.isLockEnabled
        currentBinding.backupExportButton.isEnabled = !state.isSaving
        currentBinding.backupRestoreButton.isEnabled = !state.isSaving
        currentBinding.syncStatusButton.isEnabled = !state.isSaving
        currentBinding.conflictsButton.isEnabled = !state.isSaving
    }

    private fun timeoutOptions(): List<TimeoutOption> = listOf(
        TimeoutOption(0L, getString(R.string.timeout_immediately)),
        TimeoutOption(30_000L, getString(R.string.timeout_30_seconds)),
        TimeoutOption(60_000L, getString(R.string.timeout_1_minute)),
        TimeoutOption(300_000L, getString(R.string.timeout_5_minutes)),
    )

    private fun applyInsets(currentBinding: FragmentSecuritySettingsBinding) {
        val rootStart = currentBinding.content.paddingStart
        val rootEnd = currentBinding.content.paddingEnd
        val rootTop = currentBinding.content.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(currentBinding.root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val isRtl = currentBinding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL
            currentBinding.content.updatePaddingRelative(
                start = rootStart + if (isRtl) safe.right else safe.left,
                top = rootTop + safe.top,
                end = rootEnd + if (isRtl) safe.left else safe.right,
            )
            insets
        }
        ViewCompat.requestApplyInsets(currentBinding.root)
    }

    private fun showMessage(message: Int) {
        binding?.root?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
    }

    private data class TimeoutOption(val millis: Long, val label: String)

    companion object {
        const val BACK_STACK_NAME = "security_settings"
        fun newInstance(): SecuritySettingsFragment = SecuritySettingsFragment()
    }
}
