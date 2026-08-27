package com.vaultnote.feature.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
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
import com.vaultnote.BuildConfig
import com.vaultnote.R
import com.vaultnote.app.MainNavigator
import com.vaultnote.app.appContainer
import com.vaultnote.core.security.LockPolicy
import com.vaultnote.core.theme.VaultThemePreferences
import com.vaultnote.core.theme.VaultThemes
import com.vaultnote.databinding.FragmentSecuritySettingsBinding
import com.vaultnote.feature.lock.AndroidVaultAuthenticator
import com.vaultnote.feature.lock.VaultAuthenticator
import kotlinx.coroutines.launch

class SecuritySettingsFragment : Fragment() {
    private var binding: FragmentSecuritySettingsBinding? = null
    private var isRendering = false
    private var externalHandoffActive = false
    private var installAfterPermission = false
    private lateinit var authenticator: VaultAuthenticator
    private val viewModel: SecuritySettingsViewModel by viewModels {
        val container = requireContext().appContainer()
        SecuritySettingsViewModel.Factory(container.lockPolicyRepository, container.lockManager)
    }
    private val appUpdateViewModel: AppUpdateViewModel by viewModels {
        val container = requireContext().appContainer()
        AppUpdateViewModel.Factory(container.appUpdateRepository, container.appUpdateScheduler)
    }
    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        finishExternalHandoff()
        if (installAfterPermission && canRequestPackageInstalls()) {
            installAfterPermission = false
            appUpdateViewModel.prepareInstall()
        } else {
            installAfterPermission = false
            showMessage(R.string.update_install_permission_required)
        }
    }
    private val installerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        finishExternalHandoff()
    }
    private val webLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        finishExternalHandoff()
    }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) showMessage(R.string.update_notification_permission_denied)
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
        configureThemes(currentBinding)
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
        currentBinding.automaticUpdateChecksSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!isRendering) {
                appUpdateViewModel.setAutomaticChecksEnabled(enabled)
                if (enabled) requestUpdateNotificationPermission()
            }
        }
        currentBinding.checkUpdateButton.setOnClickListener {
            appUpdateViewModel.checkForUpdate()
        }
        currentBinding.installUpdateButton.setOnClickListener { requestUpdateInstall() }
        currentBinding.githubRepositoryButton.setOnClickListener { openWebPage(GITHUB_REPOSITORY_URL) }
        currentBinding.githubReleasesButton.setOnClickListener { openWebPage(GITHUB_RELEASES_URL) }
        currentBinding.currentVersion.text = getString(
            R.string.current_app_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )
        applyInsets(currentBinding)
        collectState(currentBinding)
        collectUpdateState(currentBinding)
    }

    override fun onDestroyView() {
        finishExternalHandoff()
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

    private fun collectUpdateState(currentBinding: FragmentSecuritySettingsBinding) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { appUpdateViewModel.state.collect { renderUpdate(currentBinding, it) } }
                launch {
                    appUpdateViewModel.events.collect { event ->
                        when (event) {
                            is AppUpdateSettingsEvent.ApkReady -> launchPackageInstaller(event.file)
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

    private fun renderUpdate(
        currentBinding: FragmentSecuritySettingsBinding,
        state: AppUpdateSettingsState,
    ) {
        isRendering = true
        try {
            currentBinding.automaticUpdateChecksSwitch.isChecked = state.automaticChecksEnabled
        } finally {
            isRendering = false
        }
        val busy = state.status is AppUpdateUiStatus.Checking ||
            state.status is AppUpdateUiStatus.Downloading
        currentBinding.checkUpdateButton.isEnabled = !busy
        currentBinding.installUpdateButton.isVisible = state.availableUpdate != null
        currentBinding.installUpdateButton.isEnabled = !busy
        currentBinding.installUpdateButton.setText(
            if (state.status is AppUpdateUiStatus.Downloading) {
                R.string.update_downloading_button
            } else {
                R.string.download_and_install_update
            },
        )
        currentBinding.updateStatus.text = when (val status = state.status) {
            AppUpdateUiStatus.Idle -> getString(R.string.update_status_not_checked)
            AppUpdateUiStatus.Checking -> getString(R.string.update_status_checking)
            AppUpdateUiStatus.UpToDate -> getString(R.string.update_status_current)
            is AppUpdateUiStatus.Available ->
                getString(R.string.update_status_available, status.versionName)
            is AppUpdateUiStatus.Downloading ->
                getString(R.string.update_status_downloading, status.percent)
            is AppUpdateUiStatus.Incompatible -> getString(
                when (status.reason) {
                    com.vaultnote.core.update.AppUpdateIncompatibility.PACKAGE,
                    com.vaultnote.core.update.AppUpdateIncompatibility.CHANNEL,
                    -> R.string.update_status_different_channel
                    com.vaultnote.core.update.AppUpdateIncompatibility.SIGNING_CERTIFICATE ->
                        R.string.update_status_different_signature
                },
            )
            is AppUpdateUiStatus.Failed -> getString(
                when (status.failure) {
                    com.vaultnote.core.update.AppUpdateFailure.NETWORK ->
                        R.string.update_error_network
                    com.vaultnote.core.update.AppUpdateFailure.SERVER ->
                        R.string.update_error_server
                    com.vaultnote.core.update.AppUpdateFailure.BACKGROUND_SCHEDULING ->
                        R.string.update_error_background_schedule
                    com.vaultnote.core.update.AppUpdateFailure.INSUFFICIENT_STORAGE ->
                        R.string.update_error_storage
                    com.vaultnote.core.update.AppUpdateFailure.CHECKSUM_MISMATCH ->
                        R.string.update_error_checksum
                    com.vaultnote.core.update.AppUpdateFailure.INVALID_APK,
                    com.vaultnote.core.update.AppUpdateFailure.INVALID_METADATA,
                    -> R.string.update_error_invalid
                },
            )
        }
    }

    private fun requestUpdateInstall() {
        if (canRequestPackageInstalls()) {
            appUpdateViewModel.prepareInstall()
            return
        }
        installAfterPermission = true
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${requireContext().packageName}".toUri(),
        )
        if (!beginExternalHandoff()) {
            installAfterPermission = false
            return
        }
        try {
            unknownSourcesLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            finishExternalHandoff()
            installAfterPermission = false
            showMessage(R.string.update_install_permission_unavailable)
        }
    }

    private fun canRequestPackageInstalls(): Boolean =
        requireContext().packageManager.canRequestPackageInstalls()

    private fun requestUpdateNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun launchPackageInstaller(file: java.io.File) {
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${BuildConfig.APPLICATION_ID}.files",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!beginExternalHandoff()) return
        try {
            installerLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            finishExternalHandoff()
            showMessage(R.string.update_installer_unavailable)
        } catch (_: SecurityException) {
            finishExternalHandoff()
            showMessage(R.string.update_installer_unavailable)
        }
    }

    private fun openWebPage(url: String) {
        if (!beginExternalHandoff()) return
        try {
            webLauncher.launch(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            finishExternalHandoff()
            showMessage(R.string.web_browser_unavailable)
        }
    }

    private fun beginExternalHandoff(): Boolean {
        if (externalHandoffActive) return false
        val navigator = activity as? MainNavigator ?: return false
        if (!navigator.beginSecureExternalHandoff()) return false
        externalHandoffActive = true
        return true
    }

    private fun finishExternalHandoff() {
        if (!externalHandoffActive) return
        externalHandoffActive = false
        (activity as? MainNavigator)?.endSecureExternalHandoff()
    }

    private fun timeoutOptions(): List<TimeoutOption> = listOf(
        TimeoutOption(0L, getString(R.string.timeout_immediately)),
        TimeoutOption(30_000L, getString(R.string.timeout_30_seconds)),
        TimeoutOption(60_000L, getString(R.string.timeout_1_minute)),
        TimeoutOption(300_000L, getString(R.string.timeout_5_minutes)),
    )

    private fun configureThemes(currentBinding: FragmentSecuritySettingsBinding) {
        val preferences = VaultThemePreferences(requireContext())
        val themes = VaultThemes.selectable
        currentBinding.themeInput.setAdapter(VaultThemeAdapter(requireContext(), themes))
        val selected = preferences.selectedTheme()
        currentBinding.themeInput.setText(getString(selected.labelResource), false)
        selected.applyBackground(currentBinding.themePreview, cornerRadiusDp = 12f)
        currentBinding.themeInput.setOnItemClickListener { _, _, position, _ ->
            val theme = themes.getOrNull(position) ?: return@setOnItemClickListener
            if (theme == preferences.selectedTheme()) return@setOnItemClickListener
            preferences.select(theme)
            requireActivity().recreate()
        }
    }

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
        private const val GITHUB_REPOSITORY_URL =
            "https://github.com/stmSi/vault-note-android"
        private const val GITHUB_RELEASES_URL =
            "https://github.com/stmSi/vault-note-android/releases"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        fun newInstance(): SecuritySettingsFragment = SecuritySettingsFragment()
    }
}
