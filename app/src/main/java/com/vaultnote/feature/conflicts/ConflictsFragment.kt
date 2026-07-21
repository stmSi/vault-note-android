package com.vaultnote.feature.conflicts

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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.vaultnote.R
import com.vaultnote.app.MainNavigator
import com.vaultnote.app.appContainer
import com.vaultnote.databinding.FragmentConflictsBinding
import kotlinx.coroutines.launch

class ConflictsFragment : Fragment() {
    private var binding: FragmentConflictsBinding? = null
    private val viewModel: ConflictsViewModel by viewModels {
        ConflictsViewModel.Factory(requireContext().appContainer().syncRepository)
    }
    private val adapter = ConflictAdapter(
        onKeep = { itemId -> viewModel.keepVersion(itemId) },
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FragmentConflictsBinding.inflate(inflater, container, false)
        .also { binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val current = requireNotNull(binding)
        current.toolbar.setNavigationOnClickListener { (activity as? MainNavigator)?.navigateBack() }
        current.conflictList.layoutManager = LinearLayoutManager(requireContext())
        current.conflictList.adapter = adapter
        applyInsets(current)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(current, it) } }
                launch {
                    viewModel.events.collect { event ->
                        Snackbar.make(
                            current.root,
                            if (event == ConflictsEvent.Resolved) {
                                R.string.conflict_resolved
                            } else {
                                R.string.conflict_resolve_failed
                            },
                            Snackbar.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        binding?.conflictList?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private fun render(binding: FragmentConflictsBinding, state: ConflictsState) {
        binding.loadingIndicator.isVisible = state is ConflictsState.Loading
        binding.emptyMessage.isVisible = state is ConflictsState.Empty
        binding.errorMessage.isVisible = state is ConflictsState.Error
        binding.conflictList.isVisible = state is ConflictsState.Content
        adapter.submitList((state as? ConflictsState.Content)?.items.orEmpty())
    }

    private fun applyInsets(binding: FragmentConflictsBinding) {
        val toolbarTop = binding.toolbar.paddingTop
        val listStart = binding.conflictList.paddingStart
        val listEnd = binding.conflictList.paddingEnd
        val listBottom = binding.conflictList.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val rtl = binding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL
            binding.toolbar.updatePadding(top = toolbarTop + safe.top)
            binding.conflictList.updatePaddingRelative(
                start = listStart + if (rtl) safe.right else safe.left,
                end = listEnd + if (rtl) safe.left else safe.right,
                bottom = listBottom + safe.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    companion object {
        const val BACK_STACK_NAME = "conflicts"
        fun newInstance(): ConflictsFragment = ConflictsFragment()
    }
}
