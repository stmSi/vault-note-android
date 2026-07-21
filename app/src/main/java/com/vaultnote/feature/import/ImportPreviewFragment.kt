package com.vaultnote.feature.importing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
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
import com.vaultnote.R
import com.vaultnote.app.MainNavigator
import com.vaultnote.app.appContainer
import com.vaultnote.core.common.DefaultDispatcherProvider
import com.vaultnote.databinding.FragmentImportPreviewBinding
import kotlinx.coroutines.launch

class ImportPreviewFragment : Fragment() {
    private var binding: FragmentImportPreviewBinding? = null
    private var adapter: ImportPreviewAdapter? = null
    private val token: Long by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getLong(ARG_TOKEN, INVALID_TOKEN)
    }
    private val parentItemId: String? by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_PARENT_ITEM_ID)
    }
    private val cameraCaptureId: String? by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_CAMERA_CAPTURE_ID)
    }
    private val standaloneFiles: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getBoolean(ARG_STANDALONE_FILES, false)
    }
    private val viewModel: ImportPreviewViewModel by viewModels {
        val container = requireContext().appContainer()
        ImportPreviewViewModel.Factory(
            parentItemId = parentItemId,
            incomingImport = (activity as? MainNavigator)?.takePendingImport(token),
            cameraCaptureId = cameraCaptureId,
            vaultRepository = container.vaultRepository,
            attachmentRepository = container.attachmentRepository,
            attachmentFileManager = container.attachmentFileManager,
            cameraCaptureManager = CameraCaptureManager(requireContext()),
            dispatchers = DefaultDispatcherProvider,
            importedFilesTitle = getString(R.string.imported_files_note_title),
            standaloneFiles = standaloneFiles,
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val viewBinding = FragmentImportPreviewBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val currentBinding = requireNotNull(binding)
        val listAdapter = ImportPreviewAdapter()
        adapter = listAdapter
        currentBinding.filesList.adapter = listAdapter
        currentBinding.filesList.setHasFixedSize(true)
        currentBinding.toolbar.setNavigationOnClickListener { viewModel.cancel() }
        currentBinding.importButton.setOnClickListener { viewModel.importSelection() }
        currentBinding.errorRetry.setOnClickListener { viewModel.importSelection() }
        configureBackHandling()
        applyWindowInsets(currentBinding)
        collectState(currentBinding, listAdapter)
    }

    override fun onDestroyView() {
        binding?.filesList?.adapter = null
        adapter = null
        binding = null
        super.onDestroyView()
    }

    private fun configureBackHandling() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = viewModel.cancel()
            },
        )
    }

    private fun collectState(
        currentBinding: FragmentImportPreviewBinding,
        listAdapter: ImportPreviewAdapter,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state -> render(currentBinding, listAdapter, state) }
                }
                launch {
                    viewModel.events.collect(::handleEvent)
                }
            }
        }
    }

    private fun render(
        currentBinding: FragmentImportPreviewBinding,
        listAdapter: ImportPreviewAdapter,
        state: ImportPreviewUiState,
    ) {
        currentBinding.loadingIndicator.isVisible = state is ImportPreviewUiState.Loading
        currentBinding.errorState.isVisible = state is ImportPreviewUiState.Error
        val showContent = state is ImportPreviewUiState.Ready || state is ImportPreviewUiState.Importing
        currentBinding.actionContainer.isVisible = showContent
        currentBinding.filesList.isVisible = showContent

        val sharedText = when (state) {
            ImportPreviewUiState.Loading -> null
            is ImportPreviewUiState.Ready -> state.sharedText
            is ImportPreviewUiState.Importing -> state.sharedText
            is ImportPreviewUiState.Error -> state.sharedText
        }
        currentBinding.sharedTextLabel.isVisible = showContent && sharedText != null
        currentBinding.sharedTextPreview.isVisible = showContent && sharedText != null
        currentBinding.sharedTextPreview.text = sharedText.orEmpty().takeCodePoints(TEXT_PREVIEW_CODE_POINTS)

        val candidates = when (state) {
            ImportPreviewUiState.Loading -> emptyList()
            is ImportPreviewUiState.Ready -> state.candidates
            is ImportPreviewUiState.Importing -> state.candidates
            is ImportPreviewUiState.Error -> state.candidates
        }
        currentBinding.filesLabel.isVisible = showContent && candidates.isNotEmpty()
        listAdapter.submitList(candidates.map(::toRow))

        when (state) {
            ImportPreviewUiState.Loading -> Unit
            is ImportPreviewUiState.Ready -> {
                currentBinding.importButton.isEnabled = true
                currentBinding.progressText.text = resources.getQuantityString(
                    R.plurals.files_ready_count,
                    state.candidates.count { it.preview != null },
                    state.candidates.count { it.preview != null },
                )
            }

            is ImportPreviewUiState.Importing -> {
                currentBinding.importButton.isEnabled = false
                currentBinding.progressText.text = resources.getQuantityString(
                    R.plurals.import_progress,
                    state.totalFiles,
                    state.completedFiles,
                    state.totalFiles,
                )
            }

            is ImportPreviewUiState.Error -> {
                currentBinding.errorMessage.setText(errorMessage(state.reason))
                currentBinding.errorRetry.isVisible = state.retryable
            }
        }
    }

    private fun toRow(candidate: InspectedImportCandidate): ImportCandidateRow {
        val preview = candidate.preview
        return ImportCandidateRow(
            stableId = candidate.stableId,
            displayName = preview?.originalFilename
                ?: getString(R.string.selected_file_number, candidate.stableId),
            mimeType = preview?.mimeType,
            sizeBytes = preview?.declaredSize,
            accepted = preview != null,
        )
    }

    private fun errorMessage(reason: ImportFailureReason): Int = when (reason) {
        ImportFailureReason.SELECTION_EXPIRED -> R.string.import_selection_expired
        ImportFailureReason.CAMERA_CAPTURE_EXPIRED -> R.string.camera_import_selection_expired
        ImportFailureReason.PERMISSION_DENIED -> R.string.import_permission_denied
        ImportFailureReason.FILE_TOO_LARGE -> R.string.import_file_too_large
        ImportFailureReason.UNSUPPORTED_FILE -> R.string.import_unsupported_file
        ImportFailureReason.CORRUPTED_FILE -> R.string.import_corrupted_file
        ImportFailureReason.INSUFFICIENT_STORAGE -> R.string.import_insufficient_storage
        ImportFailureReason.NOTE_UNAVAILABLE -> R.string.import_note_unavailable
        ImportFailureReason.LOCAL_DATABASE -> R.string.import_database_failed
        ImportFailureReason.UNKNOWN -> R.string.import_failed_message
    }

    private fun handleEvent(event: ImportPreviewEvent) {
        when (event) {
            ImportPreviewEvent.NavigateBack -> (activity as? MainNavigator)?.navigateBack()
            is ImportPreviewEvent.ImportComplete -> {
                if (event.warnings.isNotEmpty()) {
                    parentFragmentManager.setFragmentResult(
                        RESULT_IMPORT_WARNINGS,
                        Bundle().apply {
                            putStringArrayList(
                                RESULT_WARNING_REASONS,
                                ArrayList(event.warnings.map(ImportWarningReason::name)),
                            )
                        },
                    )
                }
                (activity as? MainNavigator)?.completeImport(event.itemId, event.openCreatedItem)
            }
        }
    }

    private fun applyWindowInsets(currentBinding: FragmentImportPreviewBinding) {
        val rootStart = currentBinding.root.paddingStart
        val rootEnd = currentBinding.root.paddingEnd
        val toolbarTop = currentBinding.toolbar.paddingTop
        val actionBottom = currentBinding.actionContainer.paddingBottom
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
            currentBinding.actionContainer.updatePadding(bottom = actionBottom + safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(currentBinding.root)
    }

    private fun String.takeCodePoints(maximumCodePoints: Int): String {
        if (codePointCount(0, length) <= maximumCodePoints) return this
        return substring(0, offsetByCodePoints(0, maximumCodePoints)) + getString(R.string.ellipsis)
    }

    companion object {
        const val BACK_STACK_NAME: String = "import_preview"
        const val RESULT_IMPORT_WARNINGS = "import_warnings"
        const val RESULT_WARNING_REASONS = "warning_reasons"
        private const val ARG_TOKEN = "import_token"
        private const val ARG_PARENT_ITEM_ID = "parent_item_id"
        private const val ARG_CAMERA_CAPTURE_ID = "camera_capture_id"
        private const val ARG_STANDALONE_FILES = "standalone_files"
        private const val INVALID_TOKEN = -1L
        private const val TEXT_PREVIEW_CODE_POINTS = 4_000

        fun newInstance(
            token: Long,
            parentItemId: String?,
            cameraCaptureId: String?,
            standaloneFiles: Boolean,
        ): ImportPreviewFragment =
            ImportPreviewFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_TOKEN, token)
                    parentItemId?.let { putString(ARG_PARENT_ITEM_ID, it) }
                    cameraCaptureId?.let { putString(ARG_CAMERA_CAPTURE_ID, it) }
                    putBoolean(ARG_STANDALONE_FILES, standaloneFiles)
                }
            }
    }
}
