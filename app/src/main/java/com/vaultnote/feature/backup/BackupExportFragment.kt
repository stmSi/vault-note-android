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
import com.google.android.material.snackbar.Snackbar
import com.vaultnote.R
import com.vaultnote.app.MainNavigator
import com.vaultnote.app.appContainer
import com.vaultnote.databinding.FragmentBackupExportBinding
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

class BackupExportFragment : Fragment() {
    private var binding: FragmentBackupExportBinding? = null
    private val createDocument = registerForActivityResult(CreateBackupDocumentContract()) { uri ->
        viewModel.completeDestination(uri)
    }
    private val viewModel: BackupExportViewModel by viewModels {
        BackupExportViewModel.Factory(requireContext().appContainer().backupRepository)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val viewBinding = FragmentBackupExportBinding.inflate(inflater, container, false)
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
        current.confirmationInput.configureBackupPasswordInput()
        current.exportButton.setOnClickListener { requestExport(current) }
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
        binding?.confirmationInput?.text?.clear()
        binding = null
        super.onDestroyView()
    }

    private fun requestExport(current: FragmentBackupExportBinding) {
        val password = current.passwordInput.consumePasswordChars()
        val confirmation = current.confirmationInput.consumePasswordChars()
        viewModel.requestExport(password, confirmation)
    }

    private fun render(current: FragmentBackupExportBinding, state: BackupExportState) {
        val busy = state.isWaitingForDestination || state.isExporting
        current.progress.isVisible = state.isExporting
        current.passwordContainer.isEnabled = !busy
        current.confirmationContainer.isEnabled = !busy
        current.exportButton.isEnabled = !busy
    }

    private fun handleEvent(event: BackupExportEvent) {
        when (event) {
            BackupExportEvent.ChooseDestination -> createDocument.launch(backupFilename())
            BackupExportEvent.ExportComplete -> showMessage(R.string.backup_export_complete)
            is BackupExportEvent.ShowError -> showMessage(errorMessage(event.reason))
        }
    }

    private fun backupFilename(): String = "VaultNote-backup-${LocalDateTime.now().format(FILENAME_TIME)}.vnb"

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

    private fun applyInsets(current: FragmentBackupExportBinding) {
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
        const val BACK_STACK_NAME = "backup_export"
        private val FILENAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        fun newInstance(): BackupExportFragment = BackupExportFragment()
    }
}
