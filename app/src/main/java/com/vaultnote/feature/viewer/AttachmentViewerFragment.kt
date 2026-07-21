package com.vaultnote.feature.viewer

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.MenuItem
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
import coil3.ImageLoader
import coil3.load
import coil3.request.Disposable
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.vaultnote.R
import com.vaultnote.app.MainNavigator
import com.vaultnote.app.appContainer
import com.vaultnote.core.common.model.OpenableAttachment
import com.vaultnote.databinding.FragmentAttachmentViewerBinding
import kotlinx.coroutines.launch

class AttachmentViewerFragment : Fragment() {
    private var binding: FragmentAttachmentViewerBinding? = null
    private var imageRequest: Disposable? = null
    private var loadedImageAttachmentId: String? = null
    private var openableAttachment: OpenableAttachment? = null
    private val attachmentId: String by lazy(LazyThreadSafetyMode.NONE) {
        requireNotNull(requireArguments().getString(ARG_ATTACHMENT_ID)) { "Missing attachment ID" }
    }
    private val imageLoader: ImageLoader by lazy(LazyThreadSafetyMode.NONE) {
        requireContext().appContainer().imageLoader
    }
    private val viewModel: AttachmentViewerViewModel by viewModels {
        AttachmentViewerViewModel.Factory(
            attachmentId,
            requireContext().appContainer().attachmentRepository,
            requireContext().appContainer().ocrRepository,
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
        currentBinding.toolbar.setNavigationOnClickListener {
            (activity as? MainNavigator)?.navigateBack()
        }
        currentBinding.toolbar.setOnMenuItemClickListener(::onMenuItemSelected)
        currentBinding.retryButton.setOnClickListener { viewModel.retry() }
        currentBinding.openExternalButton.setOnClickListener { openExternally() }
        currentBinding.retryOcrButton.setOnClickListener { viewModel.retryOcr() }
        applyWindowInsets(currentBinding)
        collectState(currentBinding)
    }

    override fun onDestroyView() {
        imageRequest?.dispose()
        imageRequest = null
        loadedImageAttachmentId = null
        openableAttachment = null
        binding?.imagePreview?.setImageDrawable(null)
        binding = null
        super.onDestroyView()
    }

    private fun collectState(currentBinding: FragmentAttachmentViewerBinding) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { state -> render(currentBinding, state) } }
                launch { viewModel.events.collect(::handleEvent) }
            }
        }
    }

    private fun render(
        currentBinding: FragmentAttachmentViewerBinding,
        state: AttachmentViewerState,
    ) {
        currentBinding.loadingIndicator.isVisible = state is AttachmentViewerState.Loading
        currentBinding.errorState.isVisible = state is AttachmentViewerState.Error
        currentBinding.content.isVisible = state is AttachmentViewerState.Content
        currentBinding.toolbar.menu.findItem(R.id.action_delete_attachment).isEnabled =
            state is AttachmentViewerState.Content && !state.isDeleting

        when (state) {
            AttachmentViewerState.Loading -> Unit
            is AttachmentViewerState.Error -> {
                currentBinding.errorMessage.setText(errorMessage(state.reason))
                currentBinding.retryButton.isVisible = state.retryable
            }
            is AttachmentViewerState.Content -> renderContent(currentBinding, state)
        }
    }

    private fun renderContent(
        currentBinding: FragmentAttachmentViewerBinding,
        state: AttachmentViewerState.Content,
    ) {
        val openable = state.attachment
        val attachment = openable.attachment
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
        currentBinding.deletingIndicator.isVisible = state.isDeleting
        currentBinding.openExternalButton.isEnabled = !state.isDeleting
        currentBinding.ocrStatus.isVisible = attachment.ocrState !=
            com.vaultnote.core.common.model.OcrState.NOT_APPLICABLE
        currentBinding.ocrStatus.setText(
            when (attachment.ocrState) {
                com.vaultnote.core.common.model.OcrState.NOT_APPLICABLE -> R.string.ocr_not_applicable
                com.vaultnote.core.common.model.OcrState.PENDING -> R.string.ocr_pending
                com.vaultnote.core.common.model.OcrState.PROCESSING -> R.string.ocr_processing
                com.vaultnote.core.common.model.OcrState.COMPLETE -> R.string.ocr_complete
                com.vaultnote.core.common.model.OcrState.FAILED -> R.string.ocr_failed
            },
        )
        currentBinding.retryOcrButton.isVisible = attachment.ocrState ==
            com.vaultnote.core.common.model.OcrState.FAILED &&
            requireContext().appContainer().ocrRepository.isRetryable(attachment.ocrFailureCode)
        currentBinding.retryOcrButton.isEnabled = !state.isRetryingOcr

        val isImage = attachment.mimeType.startsWith("image/")
        currentBinding.imagePreview.isVisible = isImage
        currentBinding.documentIcon.isVisible = !isImage
        currentBinding.documentIcon.setImageResource(
            if (attachment.mimeType == "application/pdf") R.drawable.ic_pdf else R.drawable.ic_document,
        )
        if (isImage) {
            if (loadedImageAttachmentId != attachment.id) {
                imageRequest?.dispose()
                imageRequest = currentBinding.imagePreview.load(openable.contentUri, imageLoader) {
                    size(MAX_IMAGE_PREVIEW_PIXELS, MAX_IMAGE_PREVIEW_PIXELS)
                }
                loadedImageAttachmentId = attachment.id
            }
        } else {
            imageRequest?.dispose()
            imageRequest = null
            loadedImageAttachmentId = null
            currentBinding.imagePreview.setImageDrawable(null)
        }
    }

    private fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
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

    private fun openExternally() {
        val attachment = openableAttachment ?: return
        val outcome = requireContext().appContainer().fileViewer.open(requireActivity(), attachment)
        val message = when (outcome) {
            FileViewerResult.Opened -> return
            FileViewerResult.NoCompatibleApp -> R.string.no_viewer_available
            FileViewerResult.AccessDenied -> R.string.viewer_access_denied
            FileViewerResult.InvalidFile -> R.string.viewer_file_invalid
        }
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
            is AttachmentViewerEvent.ShowError -> binding?.root?.let {
                Snackbar.make(it, errorMessage(event.reason), Snackbar.LENGTH_LONG).show()
            }
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
        private const val MAX_IMAGE_PREVIEW_PIXELS = 1_600

        fun newInstance(attachmentId: String): AttachmentViewerFragment =
            AttachmentViewerFragment().apply {
                arguments = Bundle().apply { putString(ARG_ATTACHMENT_ID, attachmentId) }
            }
    }
}
