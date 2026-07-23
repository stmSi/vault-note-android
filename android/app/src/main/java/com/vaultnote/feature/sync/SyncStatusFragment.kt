package com.vaultnote.feature.sync

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
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
import com.vaultnote.databinding.FragmentSyncStatusBinding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

class SyncStatusFragment : Fragment() {
    private var binding: FragmentSyncStatusBinding? = null
    private val viewModel: SyncStatusViewModel by viewModels {
        val container = requireContext().appContainer()
        SyncStatusViewModel.Factory(container.syncRepository, container.syncScheduler)
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
        applyInsets(current)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(current, it) } }
                launch {
                    viewModel.events.collect { event ->
                        Snackbar.make(
                            current.root,
                            if (event == SyncStatusEvent.Scheduled) {
                                R.string.sync_scheduled
                            } else {
                                R.string.sync_schedule_failed
                            },
                            Snackbar.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
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
    }

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
}
