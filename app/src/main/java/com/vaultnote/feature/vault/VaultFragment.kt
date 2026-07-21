package com.vaultnote.feature.vault

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.snackbar.Snackbar
import com.vaultnote.R
import com.vaultnote.app.MainNavigator
import com.vaultnote.app.appContainer
import com.vaultnote.core.common.AppError
import com.vaultnote.databinding.FragmentVaultBinding
import kotlinx.coroutines.launch

class VaultFragment : Fragment() {
    private var binding: FragmentVaultBinding? = null
    private var lastContentPage: Pair<VaultSection, Int>? = null
    private val viewModel: VaultViewModel by viewModels {
        val container = requireContext().appContainer()
        VaultViewModel.Factory(container.vaultRepository, container.attachmentRepository)
    }
    private val noteAdapter = VaultItemAdapter(
        onOpen = { row -> viewModel.openItem(row) },
        onPinnedChanged = { id, pinned -> viewModel.setPinned(id, pinned) },
        onFavoriteChanged = { id, favorite -> viewModel.setFavorite(id, favorite) },
        onRestore = { id, source -> viewModel.restore(id, source) },
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val viewBinding = FragmentVaultBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val currentBinding = requireNotNull(binding)
        currentBinding.noteList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = noteAdapter
            setHasFixedSize(true)
        }
        currentBinding.addNoteButton.setOnClickListener { viewModel.createNote() }
        currentBinding.retryButton.setOnClickListener { viewModel.retry() }
        currentBinding.previousPageButton.setOnClickListener { viewModel.previousPage() }
        currentBinding.nextPageButton.setOnClickListener { viewModel.nextPage() }
        currentBinding.archiveTrashToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.show_archived_button -> viewModel.selectSection(VaultSection.ARCHIVED)
                R.id.show_trash_button -> viewModel.selectSection(VaultSection.TRASH)
            }
        }
        ItemTouchHelper(
            VaultSwipeCallback(requireContext(), noteAdapter, viewModel::handleSwipe),
        ).attachToRecyclerView(currentBinding.noteList)
        val initialSection = arguments?.getString(ARG_SECTION)
            ?.let { name -> runCatching { VaultSection.valueOf(name) }.getOrNull() }
            ?: VaultSection.ACTIVE
        viewModel.selectSection(initialSection)

        applyWindowInsets(currentBinding)
        collectViewModel(currentBinding)
    }

    override fun onDestroyView() {
        binding?.noteList?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private fun collectViewModel(currentBinding: FragmentVaultBinding) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state -> render(currentBinding, state) }
                }
                launch {
                    viewModel.events.collect(::handleEvent)
                }
            }
        }
    }

    private fun render(currentBinding: FragmentVaultBinding, state: VaultUiState) {
        currentBinding.loadingIndicator.isVisible = state is VaultUiState.Loading
        currentBinding.emptyState.isVisible = state is VaultUiState.Empty
        currentBinding.errorState.isVisible = state is VaultUiState.Error
        currentBinding.noteList.isVisible = state is VaultUiState.Content
        currentBinding.addNoteButton.isVisible = state.section == VaultSection.ACTIVE
        currentBinding.archiveTrashToggle.isVisible = state.section != VaultSection.ACTIVE
        if (state.section != VaultSection.ACTIVE) {
            val checkedId = if (state.section == VaultSection.ARCHIVED) {
                R.id.show_archived_button
            } else {
                R.id.show_trash_button
            }
            if (currentBinding.archiveTrashToggle.checkedButtonId != checkedId) {
                currentBinding.archiveTrashToggle.check(checkedId)
            }
        }
        currentBinding.pageControls.visibility = if (
            state is VaultUiState.Content || state.pageIndex > 0
        ) {
            View.VISIBLE
        } else {
            View.INVISIBLE
        }
        currentBinding.pageIndicator.text = getString(R.string.page_number, state.pageIndex + 1)
        currentBinding.previousPageButton.isEnabled =
            state.pageIndex > 0 && state !is VaultUiState.Loading
        currentBinding.nextPageButton.isEnabled =
            (state as? VaultUiState.Content)?.hasNextPage == true

        when (state) {
            is VaultUiState.Content -> {
                val nextPage = state.section to state.pageIndex
                val shouldScrollToStart = lastContentPage != null && lastContentPage != nextPage
                lastContentPage = nextPage
                noteAdapter.submitList(state.items) {
                    if (shouldScrollToStart && binding === currentBinding) {
                        currentBinding.noteList.scrollToPosition(0)
                    }
                }
            }
            is VaultUiState.Empty,
            is VaultUiState.Loading,
            is VaultUiState.Error,
            -> noteAdapter.submitList(emptyList())
        }
        if (state is VaultUiState.Empty) {
            currentBinding.emptyTitle.setText(
                when (state.section) {
                    VaultSection.ACTIVE -> R.string.empty_vault_title
                    VaultSection.ARCHIVED -> R.string.empty_archived_title
                    VaultSection.TRASH -> R.string.empty_trash_title
                },
            )
            currentBinding.emptyMessage.setText(
                when (state.section) {
                    VaultSection.ACTIVE -> R.string.empty_vault_message
                    VaultSection.ARCHIVED -> R.string.empty_archived_message
                    VaultSection.TRASH -> R.string.empty_trash_message
                },
            )
        }
        if (state is VaultUiState.Error) {
            currentBinding.retryButton.isVisible = state.retryable
        }
    }

    private fun handleEvent(event: VaultEvent) {
        when (event) {
            is VaultEvent.OpenEditor -> openEditor(event.itemId)
            is VaultEvent.OpenAttachment -> {
                (activity as? MainNavigator)?.openAttachment(event.attachmentId)
            }
            is VaultEvent.ItemRestored -> {
                val message = if (event.source == VaultSection.TRASH) {
                    R.string.note_restored_from_trash
                } else {
                    R.string.note_restored_from_archive
                }
                binding?.root?.let { root -> Snackbar.make(root, message, Snackbar.LENGTH_SHORT).show() }
            }
            is VaultEvent.ShowError -> {
                event.itemId?.let(noteAdapter::resetItem)
                val message = if (event.error is AppError.SyncSchedulingFailure) {
                    R.string.sync_schedule_failed
                } else if (event.error is AppError.ItemNotFound) {
                    R.string.item_attachment_not_found
                } else {
                    R.string.operation_failed
                }
                binding?.root?.let { root -> Snackbar.make(root, message, Snackbar.LENGTH_LONG).show() }
            }
            is VaultEvent.ItemChanged -> showUndo(event)
        }
    }

    private fun showUndo(event: VaultEvent.ItemChanged) {
        val message = when (event.change) {
            VaultItemChange.ARCHIVED -> R.string.item_archived
            VaultItemChange.TRASHED -> R.string.item_moved_to_trash
            VaultItemChange.RESTORED_FROM_ARCHIVE -> R.string.note_restored_from_archive
            VaultItemChange.RESTORED_FROM_TRASH -> R.string.note_restored_from_trash
        }
        binding?.root?.let { root ->
            Snackbar.make(root, message, Snackbar.LENGTH_LONG)
                .setAction(R.string.undo) { viewModel.undo(event.itemId, event.change) }
                .show()
        }
    }

    private fun openEditor(itemId: String) {
        (activity as? MainNavigator)?.openNoteEditor(itemId)
    }

    private fun applyWindowInsets(currentBinding: FragmentVaultBinding) {
        val rootStartPadding = currentBinding.root.paddingStart
        val rootEndPadding = currentBinding.root.paddingEnd
        val rootTopPadding = currentBinding.root.paddingTop
        val fabParams = currentBinding.addNoteButton.layoutParams as ViewGroup.MarginLayoutParams
        val fabEndMargin = fabParams.marginEnd
        val fabBottomMargin = fabParams.bottomMargin

        ViewCompat.setOnApplyWindowInsetsListener(currentBinding.root) { _, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val isRtl = currentBinding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val startInset = if (isRtl) safeInsets.right else safeInsets.left
            val endInset = if (isRtl) safeInsets.left else safeInsets.right
            currentBinding.root.updatePaddingRelative(
                start = rootStartPadding + startInset,
                end = rootEndPadding + endInset,
            )
            currentBinding.root.updatePadding(top = rootTopPadding + safeInsets.top)
            val updatedFabParams = currentBinding.addNoteButton.layoutParams as ViewGroup.MarginLayoutParams
            updatedFabParams.marginEnd = fabEndMargin
            updatedFabParams.bottomMargin = fabBottomMargin
            currentBinding.addNoteButton.layoutParams = updatedFabParams
            insets
        }
        ViewCompat.requestApplyInsets(currentBinding.root)
    }

    companion object {
        private const val ARG_SECTION = "section"

        fun newInstance(section: VaultSection = VaultSection.ACTIVE): VaultFragment =
            VaultFragment().apply {
                arguments = Bundle().apply { putString(ARG_SECTION, section.name) }
            }

        fun initialSectionOf(fragment: VaultFragment): VaultSection =
            fragment.arguments?.getString(ARG_SECTION)
                ?.let { name -> runCatching { VaultSection.valueOf(name) }.getOrNull() }
                ?: VaultSection.ACTIVE
    }

    fun currentSection(): VaultSection = viewModel.uiState.value.section
}
