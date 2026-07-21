package com.vaultnote.feature.backup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.vaultnote.R
import com.vaultnote.app.MainNavigator
import com.vaultnote.app.appContainer
import com.vaultnote.databinding.FragmentBackupRestoreBinding
import kotlinx.coroutines.launch

class BackupRestoreFragment : Fragment() {
    private var binding: FragmentBackupRestoreBinding? = null
    private var confirmationVisible = false
    private val openDocument = registerForActivityResult(OpenBackupDocumentContract()) { uri ->
        viewModel.selectSource(uri)
    }
    private val viewModel: BackupRestoreViewModel by viewModels {
        BackupRestoreViewModel.Factory(requireContext().appContainer().backupRepository)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val viewBinding = FragmentBackupRestoreBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val current = requireNotNull(binding)
        current.toolbar.setNavigationOnClickListener {
            (activity as? MainNavigator)?.navigateBack()
        }
        current.passwordInput.configureBackupPasswordInput()
        current.selectBackupButton.setOnClickListener { openDocument.launch(Unit) }
        current.validateButton.setOnClickListener {
            val password = current.passwordInput.consumePasswordChars()
            viewModel.validate(password)
        }
        applyInsets(current)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(current, it) } }
                launch { viewModel.events.collect(::handleEvent) }
            }
        }
    }

    override fun onDestroyView() {
        binding?.passwordInput?.text?.clear()
        confirmationVisible = false
        binding = null
        super.onDestroyView()
    }

    private fun render(current: FragmentBackupRestoreBinding, state: BackupRestoreState) {
        val busy = state.isValidating || state.isRestoring
        current.progress.isVisible = busy
        current.progress.contentDescription = getString(
            if (state.isRestoring) R.string.backup_restore_committing else
                R.string.backup_restore_validating,
        )
        current.selectBackupButton.isEnabled = !busy && state.confirmation == null
        current.passwordContainer.isEnabled = state.hasSource && !busy && state.confirmation == null
        current.validateButton.isEnabled = state.hasSource && !busy && state.confirmation == null
        current.sourceStatus.setText(
            if (state.hasSource) R.string.backup_file_selected else R.string.no_backup_selected,
        )
        if (state.confirmation != null && !confirmationVisible) {
            showConfirmation(state.confirmation)
        }
    }

    private fun showConfirmation(confirmation: RestoreConfirmation) {
        confirmationVisible = true
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.confirm_backup_restore_title)
            .setMessage(
                getString(
                    R.string.confirm_backup_restore_message,
                    confirmation.summary.itemCount,
                    confirmation.summary.attachmentCount,
                    confirmation.copiedItemCount,
                ),
            )
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                confirmationVisible = false
                viewModel.cancelPreparedRestore()
            }
            .setPositiveButton(R.string.restore_backup) { _, _ ->
                confirmationVisible = false
                viewModel.confirmRestore()
            }
            .setOnCancelListener {
                confirmationVisible = false
                viewModel.cancelPreparedRestore()
            }
            .show()
    }

    private fun handleEvent(event: BackupRestoreEvent) {
        when (event) {
            is BackupRestoreEvent.RestoreComplete -> {
                val message = if (event.syncDelayed) {
                    R.string.backup_restore_complete_sync_delayed
                } else {
                    R.string.backup_restore_complete
                }
                showMessage(message)
            }
            is BackupRestoreEvent.ShowError -> showMessage(errorMessage(event.reason))
        }
    }

    private fun errorMessage(error: BackupUiError): Int = when (error) {
        BackupUiError.PASSWORD_MISMATCH -> R.string.backup_passwords_do_not_match
        BackupUiError.PASSWORD_INVALID -> R.string.backup_password_invalid
        BackupUiError.WRONG_PASSWORD -> R.string.backup_wrong_password
        BackupUiError.INVALID_BACKUP -> R.string.backup_invalid
        BackupUiError.INSUFFICIENT_SPACE -> R.string.backup_insufficient_storage
        BackupUiError.PERMISSION_DENIED -> R.string.backup_permission_denied
        BackupUiError.LOCKED -> R.string.vault_locked_message
        BackupUiError.UNKNOWN -> R.string.backup_operation_failed
    }

    private fun showMessage(message: Int) {
        binding?.root?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
    }

    private fun applyInsets(current: FragmentBackupRestoreBinding) {
        val toolbarTop = current.toolbar.paddingTop
        val contentStart = current.content.paddingStart
        val contentEnd = current.content.paddingEnd
        val contentBottom = current.content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(current.root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            current.toolbar.setPadding(
                current.toolbar.paddingLeft,
                toolbarTop + safe.top,
                current.toolbar.paddingRight,
                current.toolbar.paddingBottom,
            )
            current.content.updatePaddingRelative(
                start = contentStart + safe.left,
                end = contentEnd + safe.right,
                bottom = contentBottom + safe.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(current.root)
    }

    companion object {
        const val BACK_STACK_NAME = "backup_restore"
        fun newInstance(): BackupRestoreFragment = BackupRestoreFragment()
    }
}
