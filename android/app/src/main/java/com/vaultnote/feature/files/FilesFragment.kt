package com.vaultnote.feature.files

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.vaultnote.R
import com.vaultnote.app.MainNavigator
import com.vaultnote.app.appContainer
import com.vaultnote.core.files.MAX_ATTACHMENTS_PER_IMPORT
import com.vaultnote.databinding.FragmentFilesBinding
import com.vaultnote.feature.importing.ImportSource
import com.vaultnote.feature.importing.ImportSourceKind
import com.vaultnote.feature.importing.IncomingImport
import kotlinx.coroutines.launch

class FilesFragment : Fragment() {
    private var binding: FragmentFilesBinding? = null
    private var adapter: FilesAdapter? = null
    private var lastContentKey: Pair<String, Int>? = null
    private val viewModel: FilesViewModel by viewModels {
        FilesViewModel.Factory(requireContext().appContainer().attachmentRepository)
    }

    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS_PER_IMPORT),
    ) { uris: List<android.net.Uri> -> openSelectedUris(uris) }

    private val documentPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<android.net.Uri> -> openSelectedUris(uris) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val viewBinding = FragmentFilesBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val currentBinding = requireNotNull(binding)
        val filesAdapter = FilesAdapter(
            imageLoader = requireContext().appContainer().imageLoader,
            onOpen = { file -> (activity as? MainNavigator)?.openAttachment(file.id) },
        )
        adapter = filesAdapter
        currentBinding.fileList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = filesAdapter
            setHasFixedSize(true)
        }
        currentBinding.fileSearchInput.setText(viewModel.searchText.value)
        currentBinding.fileSearchInput.setSelection(
            currentBinding.fileSearchInput.text?.length ?: 0,
        )
        currentBinding.fileSearchInput.doAfterTextChanged { editable ->
            viewModel.updateSearchText(editable?.toString().orEmpty())
        }
        currentBinding.addFileButton.setOnClickListener { showImportSourceChooser() }
        currentBinding.retryButton.setOnClickListener { viewModel.retry() }
        currentBinding.previousPageButton.setOnClickListener { viewModel.previousPage() }
        currentBinding.nextPageButton.setOnClickListener { viewModel.nextPage() }
        applyWindowInsets(currentBinding)
        collectState(currentBinding, filesAdapter)
    }

    override fun onDestroyView() {
        binding?.fileList?.adapter = null
        adapter = null
        binding = null
        super.onDestroyView()
    }

    private fun collectState(currentBinding: FragmentFilesBinding, filesAdapter: FilesAdapter) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(currentBinding, filesAdapter, state) }
            }
        }
    }

    private fun render(
        currentBinding: FragmentFilesBinding,
        filesAdapter: FilesAdapter,
        state: FilesUiState,
    ) {
        currentBinding.loadingIndicator.isVisible = state is FilesUiState.Loading
        currentBinding.emptyState.isVisible = state is FilesUiState.Empty
        currentBinding.errorState.isVisible = state is FilesUiState.Error
        currentBinding.fileList.isVisible = state is FilesUiState.Content
        currentBinding.retryButton.isVisible = (state as? FilesUiState.Error)?.retryable == true
        currentBinding.pageControls.visibility = if (
            state is FilesUiState.Content || state.pageIndex > 0
        ) {
            View.VISIBLE
        } else {
            View.INVISIBLE
        }
        currentBinding.pageIndicator.text = getString(R.string.page_number, state.pageIndex + 1)
        currentBinding.previousPageButton.isEnabled =
            state.pageIndex > 0 && state !is FilesUiState.Loading
        currentBinding.nextPageButton.isEnabled =
            (state as? FilesUiState.Content)?.hasNextPage == true
        val hasSearch = state.searchText.isNotBlank()
        currentBinding.emptyTitle.setText(
            if (hasSearch) R.string.no_matching_files_title else R.string.empty_files_title,
        )
        currentBinding.emptyMessage.setText(
            if (hasSearch) R.string.no_matching_files_message else R.string.empty_files_message,
        )

        if (state is FilesUiState.Content) {
            val contentKey = state.searchText to state.pageIndex
            val shouldScrollToStart = lastContentKey != null && lastContentKey != contentKey
            lastContentKey = contentKey
            filesAdapter.submitList(state.files) {
                if (shouldScrollToStart && binding === currentBinding) {
                    currentBinding.fileList.scrollToPosition(0)
                }
            }
        } else {
            filesAdapter.submitList(emptyList())
        }
    }

    private fun showImportSourceChooser() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_file)
            .setItems(
                arrayOf(
                    getString(R.string.choose_photos),
                    getString(R.string.choose_documents),
                ),
            ) { _, index ->
                when (index) {
                    0 -> launchPhotoPicker()
                    1 -> launchDocumentPicker()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchPhotoPicker() {
        try {
            photoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        } catch (_: ActivityNotFoundException) {
            showMessage(R.string.file_picker_unavailable)
        }
    }

    private fun launchDocumentPicker() {
        try {
            documentPicker.launch(arrayOf(ANY_FILE_MIME_TYPE))
        } catch (_: ActivityNotFoundException) {
            showMessage(R.string.file_picker_unavailable)
        }
    }

    private fun openSelectedUris(uris: List<android.net.Uri>) {
        val uniqueUris = uris.distinctBy(android.net.Uri::toString)
        if (uniqueUris.size > MAX_ATTACHMENTS_PER_IMPORT) {
            showMessage(R.string.too_many_files)
            return
        }
        if (uniqueUris.any { it.scheme != "content" }) {
            showMessage(R.string.unsupported_uri)
            return
        }
        if (uniqueUris.isEmpty()) return
        val accepted = (activity as? MainNavigator)?.openImportPreview(
            parentItemId = null,
            incomingImport = IncomingImport(
                sharedText = null,
                sources = uniqueUris.map { uri ->
                    ImportSource(uri = uri, kind = ImportSourceKind.EXTERNAL)
                },
            ),
            standaloneFiles = true,
        ) ?: false
        if (!accepted) showMessage(R.string.import_preview_unavailable)
    }

    private fun showMessage(message: Int) {
        binding?.root?.let { root -> Snackbar.make(root, message, Snackbar.LENGTH_LONG).show() }
    }

    private fun applyWindowInsets(currentBinding: FragmentFilesBinding) {
        val rootStart = currentBinding.root.paddingStart
        val rootEnd = currentBinding.root.paddingEnd
        val rootTop = currentBinding.root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(currentBinding.root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val isRtl = currentBinding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL
            currentBinding.root.updatePaddingRelative(
                start = rootStart + if (isRtl) safe.right else safe.left,
                end = rootEnd + if (isRtl) safe.left else safe.right,
            )
            currentBinding.root.updatePadding(top = rootTop + safe.top)
            insets
        }
        ViewCompat.requestApplyInsets(currentBinding.root)
    }

    companion object {
        private const val ANY_FILE_MIME_TYPE = "*/*"

        fun newInstance(): FilesFragment = FilesFragment()
    }
}
