package com.vaultnote.feature.viewer

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.vaultnote.R
import com.vaultnote.app.MainNavigator
import com.vaultnote.app.appContainer
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.model.OpenableAttachment
import com.vaultnote.core.common.model.OcrState
import com.vaultnote.core.files.AttachmentFilenamePolicy
import com.vaultnote.databinding.DialogRenameAttachmentBinding
import com.vaultnote.databinding.FragmentAttachmentViewerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AttachmentViewerFragment : Fragment() {
    private var binding: FragmentAttachmentViewerBinding? = null
    private var pagerAdapter: AttachmentViewerPageAdapter? = null
    private var openableAttachment: OpenableAttachment? = null
    private var externalViewerHandoffPending = false
    private var externalViewerWasPaused = false
    private var externalViewerGuardJob: Job? = null
    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            pagerAdapter?.currentList?.getOrNull(position)?.attachment?.id?.let(
                viewModel::selectAttachment,
            )
        }
    }
    private val saveDocument = registerForActivityResult(CreateAttachmentDocumentContract()) { uri ->
        (activity as? MainNavigator)?.endSecureExternalHandoff()
        viewModel.completeSave(uri)
    }
    private val attachmentId: String by lazy(LazyThreadSafetyMode.NONE) {
        requireNotNull(requireArguments().getString(ARG_ATTACHMENT_ID)) { "Missing attachment ID" }
    }
    private val viewModel: AttachmentViewerViewModel by viewModels {
        AttachmentViewerViewModel.Factory(
            attachmentId,
            requireContext().appContainer().attachmentRepository,
            requireContext().appContainer().ocrRepository,
            requireContext().appContainer().attachmentExporter,
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val viewBinding = FragmentAttachmentViewerBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val currentBinding = requireNotNull(binding)
        val adapter = AttachmentViewerPageAdapter(requireContext().appContainer().imageLoader)
        pagerAdapter = adapter
        currentBinding.attachmentPager.adapter = adapter
        currentBinding.attachmentPager.registerOnPageChangeCallback(pageCallback)
        currentBinding.toolbar.setNavigationOnClickListener {
            (activity as? MainNavigator)?.navigateBack()
        }
        currentBinding.toolbar.setOnMenuItemClickListener(::onMenuItemSelected)
        currentBinding.retryButton.setOnClickListener { viewModel.retry() }
        currentBinding.openExternalButton.setOnClickListener { openExternally() }
        currentBinding.retryOcrButton.setOnClickListener { viewModel.retryOcr() }
        applyWindowInsets(currentBinding)
        collectState(currentBinding, adapter)
    }

    override fun onResume() {
        super.onResume()
        if (externalViewerHandoffPending && externalViewerWasPaused) {
            finishExternalViewerHandoff()
        }
    }

    override fun onPause() {
        if (externalViewerHandoffPending) externalViewerWasPaused = true
        super.onPause()
    }

    override fun onDestroyView() {
        finishExternalViewerHandoff()
        binding?.attachmentPager?.unregisterOnPageChangeCallback(pageCallback)
        binding?.attachmentPager?.adapter = null
        openableAttachment = null
        pagerAdapter = null
        binding = null
        super.onDestroyView()
    }

    private fun collectState(
        currentBinding: FragmentAttachmentViewerBinding,
        adapter: AttachmentViewerPageAdapter,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { state -> render(currentBinding, adapter, state) } }
                launch { viewModel.events.collect(::handleEvent) }
            }
        }
    }

    private fun render(
        currentBinding: FragmentAttachmentViewerBinding,
        adapter: AttachmentViewerPageAdapter,
        state: AttachmentViewerState,
    ) {
        currentBinding.loadingIndicator.isVisible = state is AttachmentViewerState.Loading
        currentBinding.errorState.isVisible = state is AttachmentViewerState.Error
        currentBinding.content.isVisible = state is AttachmentViewerState.Content
        val content = state as? AttachmentViewerState.Content
        val actionsEnabled = content != null &&
            !content.isBusy &&
            !content.isLoadingSelection &&
            content.openableAttachment != null
        currentBinding.toolbar.menu.findItem(R.id.action_delete_attachment).isEnabled =
            content != null && !content.isBusy && !content.isLoadingSelection
        currentBinding.toolbar.menu.findItem(R.id.action_save_attachment).isEnabled = actionsEnabled
        currentBinding.toolbar.menu.findItem(R.id.action_share_attachment).isEnabled = actionsEnabled
        currentBinding.toolbar.menu.findItem(R.id.action_rename_attachment).isEnabled =
            content != null && !content.isBusy && !content.isLoadingSelection

        when (state) {
            AttachmentViewerState.Loading -> Unit
            is AttachmentViewerState.Error -> {
                openableAttachment = null
                currentBinding.errorMessage.setText(errorMessage(state.reason))
                currentBinding.retryButton.isVisible = state.retryable
            }
            is AttachmentViewerState.Content -> renderContent(currentBinding, adapter, state)
        }
    }

    private fun renderContent(
        currentBinding: FragmentAttachmentViewerBinding,
        adapter: AttachmentViewerPageAdapter,
        state: AttachmentViewerState.Content,
    ) {
        val attachment = state.selectedAttachment ?: return
        val openable = state.openableAttachment?.takeIf { it.attachment.id == attachment.id }
        openableAttachment = openable
        currentBinding.toolbar.title = attachment.displayName
        currentBinding.filename.text = attachment.displayName
        currentBinding.mimeType.text = attachment.mimeType
        currentBinding.fileSize.text = Formatter.formatShortFileSize(
            requireContext(),
            attachment.fileSizeBytes,
        )
        currentBinding.details.text = when {
            attachment.imageWidth != null && attachment.imageHeight != null -> getString(
                R.string.image_dimensions,
                attachment.imageWidth,
                attachment.imageHeight,
            )
            attachment.pdfPageCount != null -> resources.getQuantityString(
                R.plurals.pdf_page_count,
                attachment.pdfPageCount,
                attachment.pdfPageCount,
            )
            else -> getString(R.string.document_attachment)
        }
        currentBinding.operationIndicator.isVisible = state.isBusy
        currentBinding.operationIndicator.contentDescription = getString(
            when {
                state.isDeleting -> R.string.deleting_attachment
                state.isRenaming -> R.string.rename_attachment
                else -> R.string.saving_attachment
            },
        )
        currentBinding.openExternalButton.isEnabled = !state.isBusy &&
            !state.isLoadingSelection && openable != null
        currentBinding.ocrStatus.isVisible = attachment.ocrState != OcrState.NOT_APPLICABLE
        currentBinding.ocrStatus.setText(
            when (attachment.ocrState) {
                OcrState.NOT_APPLICABLE -> R.string.ocr_not_applicable
                OcrState.PENDING -> R.string.ocr_pending
                OcrState.PROCESSING -> R.string.ocr_processing
                OcrState.COMPLETE -> R.string.ocr_complete
                OcrState.FAILED -> R.string.ocr_failed
            },
        )
        currentBinding.retryOcrButton.isVisible = attachment.ocrState == OcrState.FAILED &&
            requireContext().appContainer().ocrRepository.isRetryable(attachment.ocrFailureCode)
        currentBinding.retryOcrButton.isEnabled = !state.isRetryingOcr && !state.isBusy

        val selectedIndex = state.attachments.indexOfFirst { it.id == state.selectedAttachmentId }
        currentBinding.attachmentPosition.isVisible = state.attachments.size > 1
        if (selectedIndex >= 0) {
            currentBinding.attachmentPosition.text = getString(
                R.string.attachment_position,
                selectedIndex + 1,
                state.attachments.size,
            )
        }
        currentBinding.attachmentPager.isUserInputEnabled = !state.isBusy
        val pages = state.attachments.map { sibling ->
            val selected = sibling.id == state.selectedAttachmentId
            AttachmentViewerPage(
                attachment = sibling,
                previewUri = openable?.contentUri?.takeIf { selected },
                selected = selected,
                loading = selected && state.isLoadingSelection,
            )
        }
        adapter.submitList(pages) {
            val target = pages.indexOfFirst { it.attachment.id == state.selectedAttachmentId }
            if (target >= 0 && currentBinding.attachmentPager.currentItem != target) {
                currentBinding.attachmentPager.setCurrentItem(target, false)
            }
        }
    }

    private fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_save_attachment -> {
            launchSaveDocument()
            true
        }
        R.id.action_share_attachment -> {
            shareAttachment()
            true
        }
        R.id.action_rename_attachment -> {
            showRenameDialog()
            true
        }
        R.id.action_delete_attachment -> {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_attachment_title)
                .setMessage(R.string.delete_attachment_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete_attachment) { _, _ -> viewModel.delete() }
                .show()
            true
        }
        else -> false
    }

    private fun showRenameDialog() {
        val current = viewModel.state.value as? AttachmentViewerState.Content ?: return
        val attachment = current.selectedAttachment ?: return
        if (current.isBusy || current.isLoadingSelection) return
        val dialogBinding = DialogRenameAttachmentBinding.inflate(layoutInflater)
        dialogBinding.attachmentName.setText(attachment.displayName)
        dialogBinding.attachmentName.setSelection(
            0,
            attachment.displayName.lastIndexOf('.').takeIf { it > 0 }
                ?: attachment.displayName.length,
        )
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rename_attachment_title)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rename_attachment, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val requestedName = dialogBinding.attachmentName.text?.toString().orEmpty()
                when (
                    val result = AttachmentFilenamePolicy.renameForMimeType(
                        requestedName = requestedName,
                        currentName = attachment.displayName,
                        mimeType = attachment.mimeType,
                    )
                ) {
                    is RepositoryResult.Success -> {
                        viewModel.rename(result.value)
                        dialog.dismiss()
                    }
                    is RepositoryResult.Failure -> {
                        dialogBinding.attachmentNameLayout.error = if (
                            (result.error as? com.vaultnote.core.common.AppError.InvalidInput)
                                ?.reason == "extension_mismatch"
                        ) {
                            getString(R.string.attachment_name_extension_mismatch)
                        } else {
                            getString(R.string.attachment_name_invalid)
                        }
                    }
                }
            }
            dialogBinding.attachmentName.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    private fun openExternally() {
        val attachment = openableAttachment ?: return
        launchExternalViewer {
            requireContext().appContainer().fileViewer.open(requireActivity(), attachment)
        }
    }

    private fun shareAttachment() {
        val attachment = openableAttachment ?: return
        launchExternalViewer {
            requireContext().appContainer().fileViewer.share(requireActivity(), attachment)
        }
    }

    private fun launchExternalViewer(launch: () -> FileViewerResult) {
        if (externalViewerHandoffPending) return
        val navigator = activity as? MainNavigator
        if (navigator == null) {
            showMessage(R.string.no_viewer_available)
            return
        }
        if (!navigator.beginSecureExternalHandoff()) {
            showMessage(R.string.vault_locked_message)
            return
        }
        externalViewerHandoffPending = true
        externalViewerWasPaused = false
        val outcome = launch()
        if (outcome == FileViewerResult.Opened) {
            externalViewerGuardJob?.cancel()
            externalViewerGuardJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(EXTERNAL_LAUNCH_SETTLE_MILLIS)
                if (externalViewerHandoffPending && !externalViewerWasPaused) {
                    finishExternalViewerHandoff()
                }
            }
        } else {
            finishExternalViewerHandoff()
        }
        showHandoffFailure(outcome)
    }

    private fun finishExternalViewerHandoff() {
        if (!externalViewerHandoffPending) return
        externalViewerHandoffPending = false
        externalViewerWasPaused = false
        externalViewerGuardJob?.cancel()
        externalViewerGuardJob = null
        (activity as? MainNavigator)?.endSecureExternalHandoff()
    }

    private fun launchSaveDocument() {
        val request = viewModel.prepareSave() ?: return
        val navigator = activity as? MainNavigator
        if (navigator == null) {
            viewModel.completeSave(null)
            showMessage(R.string.file_picker_unavailable)
            return
        }
        if (!navigator.beginSecureExternalHandoff()) {
            viewModel.completeSave(null)
            showMessage(R.string.vault_locked_message)
            return
        }
        try {
            saveDocument.launch(request)
        } catch (_: ActivityNotFoundException) {
            navigator.endSecureExternalHandoff()
            viewModel.completeSave(null)
            showMessage(R.string.file_picker_unavailable)
        } catch (_: SecurityException) {
            navigator.endSecureExternalHandoff()
            viewModel.completeSave(null)
            showMessage(R.string.file_picker_unavailable)
        }
    }

    private fun showHandoffFailure(outcome: FileViewerResult) {
        val message = when (outcome) {
            FileViewerResult.Opened -> return
            FileViewerResult.NoCompatibleApp -> R.string.no_viewer_available
            FileViewerResult.AccessDenied -> R.string.viewer_access_denied
            FileViewerResult.InvalidFile -> R.string.viewer_file_invalid
        }
        showMessage(message)
    }

    private fun showMessage(message: Int) {
        binding?.root?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
    }

    private fun handleEvent(event: AttachmentViewerEvent) {
        when (event) {
            is AttachmentViewerEvent.DeleteComplete -> {
                if (event.warnings.isNotEmpty()) {
                    parentFragmentManager.setFragmentResult(
                        RESULT_DELETE_WARNING,
                        Bundle().apply {
                            putStringArrayList(
                                RESULT_DELETE_WARNING_REASONS,
                                ArrayList(event.warnings.map(AttachmentDeleteWarningReason::name)),
                            )
                        },
                    )
                }
                (activity as? MainNavigator)?.navigateBack()
            }
            is AttachmentViewerEvent.ShowError -> showMessage(errorMessage(event.reason))
            is AttachmentViewerEvent.RenameComplete -> showMessage(
                if (event.syncDelayed) {
                    R.string.attachment_renamed_sync_delayed
                } else {
                    R.string.attachment_renamed
                },
            )
            AttachmentViewerEvent.RenameFailed -> showMessage(R.string.attachment_rename_failed)
            AttachmentViewerEvent.SaveComplete -> showMessage(R.string.attachment_saved_copy)
            AttachmentViewerEvent.SaveFailed -> showMessage(R.string.attachment_save_failed)
        }
    }

    private fun errorMessage(reason: ViewerFailureReason): Int = when (reason) {
        ViewerFailureReason.NOT_FOUND -> R.string.attachment_not_found
        ViewerFailureReason.CORRUPTED -> R.string.viewer_file_invalid
        ViewerFailureReason.PERMISSION_DENIED -> R.string.viewer_access_denied
        ViewerFailureReason.LOCAL_DATABASE -> R.string.attachment_load_failed
        ViewerFailureReason.UNKNOWN -> R.string.attachment_load_failed
    }

    private fun applyWindowInsets(currentBinding: FragmentAttachmentViewerBinding) {
        val rootStart = currentBinding.root.paddingStart
        val rootEnd = currentBinding.root.paddingEnd
        val toolbarTop = currentBinding.toolbar.paddingTop
        val contentBottom = currentBinding.content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(currentBinding.root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val isRtl = currentBinding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL
            currentBinding.root.updatePaddingRelative(
                start = rootStart + if (isRtl) safe.right else safe.left,
                end = rootEnd + if (isRtl) safe.left else safe.right,
            )
            currentBinding.toolbar.updatePadding(top = toolbarTop + safe.top)
            currentBinding.content.updatePadding(bottom = contentBottom + safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(currentBinding.root)
    }

    companion object {
        const val BACK_STACK_NAME: String = "attachment_viewer"
        const val RESULT_DELETE_WARNING = "attachment_delete_warning"
        const val RESULT_DELETE_WARNING_REASONS = "warning_reasons"
        private const val ARG_ATTACHMENT_ID = "attachment_id"
        private const val EXTERNAL_LAUNCH_SETTLE_MILLIS = 1_000L

        fun newInstance(attachmentId: String): AttachmentViewerFragment =
            AttachmentViewerFragment().apply {
                arguments = Bundle().apply { putString(ARG_ATTACHMENT_ID, attachmentId) }
            }
    }
}
