package com.vaultnote.feature.sync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.core.widget.doAfterTextChanged
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
import com.vaultnote.core.sync.lan.RelayConnectionState
import com.vaultnote.core.sync.lan.RelayConnectionSummary
import com.vaultnote.core.sync.lan.RelayPairingResult
import com.vaultnote.databinding.FragmentSyncStatusBinding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.launch

class SyncStatusFragment : Fragment() {
    private var binding: FragmentSyncStatusBinding? = null
    private var pendingLanAction: PendingLanAction? = null
    private var renderedConnection: RelayConnectionSummary? = null
    private val requestLocalNetworkPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingLanAction
        pendingLanAction = null
        if (granted && action != null) {
            executeLanAction(action)
        } else if (!granted) {
            showMessage(R.string.lan_network_permission_denied)
        }
    }
    private val viewModel: SyncStatusViewModel by viewModels {
        val container = requireContext().appContainer()
        SyncStatusViewModel.Factory(
            container.syncRepository,
            container.syncScheduler,
            container.lanSyncConnectionRepository,
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FragmentSyncStatusBinding.inflate(inflater, container, false)
        .also { binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val current = requireNotNull(binding)
        current.toolbar.setNavigationOnClickListener { (activity as? MainNavigator)?.navigateBack() }
        current.syncNowButton.setOnClickListener { viewModel.syncNow() }
        current.relayFingerprintInput.doAfterTextChanged {
            current.fingerprintVerifiedCheckbox.isChecked = false
        }
        current.discoverRelayButton.setOnClickListener {
            runWithLocalNetworkPermission(PendingLanAction.Discover)
        }
        current.pairRelayButton.setOnClickListener {
            if (!current.fingerprintVerifiedCheckbox.isChecked) {
                showMessage(R.string.relay_fingerprint_confirmation_required)
                return@setOnClickListener
            }
            runWithLocalNetworkPermission(PendingLanAction.Pair)
        }
        current.disconnectRelayButton.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.disconnect_lan_relay)
                .setMessage(R.string.disconnect_lan_relay_confirmation)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.disconnect_lan_relay) { _, _ ->
                    viewModel.disconnect()
                }
                .show()
        }
        applyInsets(current)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(current, it) } }
                launch {
                    viewModel.events.collect { event ->
                        handleEvent(current, event)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        pendingLanAction = null
        renderedConnection = null
        binding = null
        super.onDestroyView()
    }

    private fun render(binding: FragmentSyncStatusBinding, state: SyncStatusState) {
        binding.loadingIndicator.isVisible = state is SyncStatusState.Loading
        binding.content.isVisible = state is SyncStatusState.Content
        binding.errorMessage.isVisible = state is SyncStatusState.Error
        val overview = (state as? SyncStatusState.Content)?.overview ?: return
        binding.pendingCount.text = getString(R.string.sync_pending_count, overview.pendingCount)
        binding.runningCount.text = getString(R.string.sync_running_count, overview.runningCount)
        binding.retryCount.text = getString(R.string.sync_retry_count, overview.retryCount)
        binding.failedCount.text = getString(R.string.sync_failed_count, overview.failedCount)
        binding.conflictCount.text = getString(R.string.sync_conflict_count, overview.conflictCount)
        binding.lastSuccess.text = getString(
            R.string.sync_last_success,
            overview.lastSuccessAtEpochMillis?.let(::formatTime) ?: getString(R.string.sync_never),
        )
        binding.connectionProgress.isVisible = state.isConnectionActionRunning
        binding.discoverRelayButton.isEnabled = !state.isConnectionActionRunning
        binding.pairRelayButton.isEnabled = !state.isConnectionActionRunning
        binding.syncNowButton.isEnabled =
            !state.isConnectionActionRunning &&
            state.connection is RelayConnectionState.Configured
        binding.disconnectRelayButton.isEnabled = !state.isConnectionActionRunning
        binding.disconnectRelayButton.isVisible =
            state.connection is RelayConnectionState.Configured
        renderConnection(binding, state.connection)
    }

    private fun renderConnection(
        binding: FragmentSyncStatusBinding,
        connection: RelayConnectionState,
    ) {
        when (connection) {
            RelayConnectionState.Loading -> {
                binding.connectionStatus.setText(R.string.lan_relay_checking)
                binding.connectionDetails.setText(R.string.lan_relay_checking_description)
            }
            RelayConnectionState.NotConfigured -> {
                renderedConnection = null
                binding.connectionStatus.setText(R.string.lan_relay_not_configured)
                binding.connectionDetails.setText(R.string.lan_relay_not_configured_description)
            }
            RelayConnectionState.Corrupted -> {
                renderedConnection = null
                binding.connectionStatus.setText(R.string.lan_relay_credentials_corrupted)
                binding.connectionDetails.setText(R.string.lan_relay_credentials_corrupted_description)
            }
            is RelayConnectionState.Configured -> {
                binding.connectionStatus.setText(R.string.lan_relay_connected)
                val summary = connection.summary
                binding.connectionDetails.text = getString(
                    R.string.lan_relay_connection_details,
                    summary.hostAddress,
                    summary.port,
                    summary.vaultId,
                    formatFingerprint(summary.certificateSha256),
                )
                if (renderedConnection != summary) {
                    populateRelay(binding, summary)
                    renderedConnection = summary
                }
            }
        }
    }

    private fun populateRelay(
        binding: FragmentSyncStatusBinding,
        summary: RelayConnectionSummary,
    ) {
        binding.relayHostInput.setText(summary.hostAddress)
        binding.relayPortInput.setText(String.format(Locale.ROOT, "%d", summary.port))
        binding.relayVaultInput.setText(summary.vaultId)
        binding.relayFingerprintInput.setText(formatFingerprint(summary.certificateSha256))
        binding.fingerprintVerifiedCheckbox.isChecked = true
    }

    private fun handleEvent(
        binding: FragmentSyncStatusBinding,
        event: SyncStatusEvent,
    ) {
        when (event) {
            SyncStatusEvent.Scheduled -> showMessage(R.string.sync_scheduled)
            SyncStatusEvent.ScheduleFailed -> showMessage(R.string.sync_schedule_failed)
            SyncStatusEvent.ConnectionRequired -> showMessage(R.string.lan_relay_connection_required)
            SyncStatusEvent.DiscoveryNotFound -> showMessage(R.string.lan_relay_not_found)
            SyncStatusEvent.DiscoveryPermissionDenied ->
                showMessage(R.string.lan_network_permission_denied)
            SyncStatusEvent.DiscoveryFailed -> showMessage(R.string.lan_relay_discovery_failed)
            is SyncStatusEvent.RelayDiscovered -> {
                renderedConnection = null
                binding.relayHostInput.setText(event.relay.hostAddress)
                binding.relayPortInput.setText(
                    String.format(Locale.ROOT, "%d", event.relay.port),
                )
                binding.relayVaultInput.setText(event.relay.vaultId)
                binding.relayFingerprintInput.setText(
                    formatFingerprint(event.relay.certificateSha256),
                )
                binding.fingerprintVerifiedCheckbox.isChecked = false
                binding.relayTokenInput.requestFocus()
                binding.scrollContent.smoothScrollTo(0, binding.relayTokenContainer.top)
                showKeyboard(binding.relayTokenInput)
                showMessage(R.string.lan_relay_discovered)
            }
            is SyncStatusEvent.PairingFinished -> {
                val message = when (event.result) {
                    is RelayPairingResult.Paired -> R.string.lan_relay_pairing_succeeded
                    RelayPairingResult.WrongPassword -> R.string.lan_relay_wrong_sync_password
                    RelayPairingResult.AuthenticationFailed ->
                        R.string.lan_relay_authentication_failed
                    RelayPairingResult.CertificateMismatch ->
                        R.string.lan_relay_certificate_mismatch
                    RelayPairingResult.PermissionDenied ->
                        R.string.lan_network_permission_denied
                    RelayPairingResult.RelayUnavailable -> R.string.lan_relay_unavailable
                    RelayPairingResult.InvalidConfiguration ->
                        R.string.lan_relay_invalid_configuration
                    RelayPairingResult.LocalStorageFailure ->
                        R.string.lan_relay_local_storage_failure
                }
                if (event.result is RelayPairingResult.Paired) {
                    binding.relayTokenInput.text?.clear()
                    hideKeyboard(binding.root)
                }
                showMessage(message)
            }
            is SyncStatusEvent.DisconnectFinished -> showMessage(
                if (event.succeeded) {
                    R.string.lan_relay_disconnected
                } else {
                    R.string.lan_relay_disconnect_failed
                },
            )
        }
    }

    private fun runWithLocalNetworkPermission(action: PendingLanAction) {
        if (
            Build.VERSION.SDK_INT < 37 ||
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            executeLanAction(action)
            return
        }
        pendingLanAction = action
        requestLocalNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    private fun executeLanAction(action: PendingLanAction) {
        when (action) {
            PendingLanAction.Discover -> viewModel.discoverRelay()
            PendingLanAction.Pair -> pairFromInputs()
        }
    }

    private fun pairFromInputs() {
        val current = binding ?: return
        val syncPassword = current.syncPasswordInput.text?.toString()?.toCharArray()
            ?: CharArray(0)
        current.syncPasswordInput.text?.clear()
        viewModel.pair(
            hostAddress = current.relayHostInput.text?.toString().orEmpty(),
            port = current.relayPortInput.text?.toString().orEmpty(),
            vaultId = current.relayVaultInput.text?.toString().orEmpty(),
            certificateSha256 = current.relayFingerprintInput.text?.toString().orEmpty(),
            authenticationToken = current.relayTokenInput.text?.toString().orEmpty(),
            syncPassword = syncPassword,
        )
    }

    private fun showKeyboard(view: View) {
        view.post {
            ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
                ?.showSoftInput(view, 0)
        }
    }

    private fun hideKeyboard(view: View) {
        ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun showMessage(message: Int) {
        val current = binding ?: return
        Snackbar.make(current.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun formatFingerprint(value: String): String =
        value.filter(Char::isLetterOrDigit)
            .uppercase()
            .chunked(2)
            .joinToString(":")

    private fun formatTime(epochMillis: Long): String = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

    private fun applyInsets(binding: FragmentSyncStatusBinding) {
        val toolbarTop = binding.toolbar.paddingTop
        val start = binding.content.paddingStart
        val end = binding.content.paddingEnd
        val bottom = binding.content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val rtl = binding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL
            binding.toolbar.updatePadding(top = toolbarTop + safe.top)
            binding.content.updatePaddingRelative(
                start = start + if (rtl) safe.right else safe.left,
                end = end + if (rtl) safe.left else safe.right,
                bottom = bottom + safe.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    companion object {
        const val BACK_STACK_NAME = "sync_status"
        fun newInstance(): SyncStatusFragment = SyncStatusFragment()
    }

    private enum class PendingLanAction {
        Discover,
        Pair,
    }
}
