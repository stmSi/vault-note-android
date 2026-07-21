package com.vaultnote.feature.editor

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.get
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.vaultnote.R
import com.vaultnote.app.MainNavigator
import com.vaultnote.app.appContainer
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.VaultConstraints
import com.vaultnote.core.common.model.VaultItemColor
import com.vaultnote.core.common.toStyle
import com.vaultnote.core.files.MAX_ATTACHMENTS_PER_IMPORT
import com.vaultnote.databinding.FragmentNoteEditorBinding
import com.vaultnote.feature.importing.CameraCaptureManager
import com.vaultnote.feature.importing.ImportSource
import com.vaultnote.feature.importing.ImportSourceKind
import com.vaultnote.feature.importing.IncomingImport
import com.vaultnote.feature.importing.ImportPreviewFragment
import com.vaultnote.feature.importing.ImportWarningReason
import com.vaultnote.feature.importing.PendingCameraCapture
import com.vaultnote.feature.viewer.AttachmentDeleteWarningReason
import com.vaultnote.feature.viewer.AttachmentViewerFragment
import kotlinx.coroutines.launch

class NoteEditorFragment : Fragment() {
    private var binding: FragmentNoteEditorBinding? = null
    private var isRendering = false
    private var lastTitleInputValue: String? = null
    private var lastBodyInputValue: String? = null
    private var lastTagsInputValue: String? = null
    private var attachmentAdapter: EditorAttachmentAdapter? = null
    private val cameraCaptureManager: CameraCaptureManager by lazy(LazyThreadSafetyMode.NONE) {
        CameraCaptureManager(requireContext())
    }
    private val itemId: String by lazy(LazyThreadSafetyMode.NONE) {
        requireNotNull(requireArguments().getString(ARG_ITEM_ID)) { "Missing note ID" }
    }
    private val viewModel: NoteEditorViewModel by viewModels {
        NoteEditorViewModel.Factory(
            itemId = itemId,
            repository = requireContext().appContainer().vaultRepository,
        )
    }
    private val attachmentsViewModel: EditorAttachmentsViewModel by viewModels {
        EditorAttachmentsViewModel.Factory(
            itemId = itemId,
            attachmentRepository = requireContext().appContainer().attachmentRepository,
        )
    }
    private val cameraCaptureViewModel: CameraCaptureViewModel by viewModels()

    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS_PER_IMPORT),
    ) { uris -> openSelectedUris(uris) }

    private val documentPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> openSelectedUris(uris) }

    private val cameraCapture = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { captured ->
        val reference = cameraCaptureViewModel.peek()
        viewLifecycleOwner.lifecycleScope.launch {
            val pending = reference?.inMemoryCapture
                ?: reference?.captureId?.let { cameraCaptureManager.restoreCapture(it) }
            if (captured && pending != null) {
                val accepted = openImport(
                    sources = listOf(pending.source),
                    cameraCaptureId = pending.captureId,
                )
                if (!accepted) {
                    showMessage(R.string.import_preview_unavailable)
                }
            } else {
                deleteCaptureAndClear(reference, pending)
                if (captured) showMessage(R.string.camera_capture_expired)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val viewBinding = FragmentNoteEditorBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val currentBinding = requireNotNull(binding)
        configureToolbar(currentBinding)
        configureInputs(currentBinding)
        configureAttachments(currentBinding)
        configureAttachmentWarningResults(currentBinding)
        configureBackHandling()
        applyWindowInsets(currentBinding)
        collectViewModel(currentBinding)
    }

    override fun onStop() {
        if (binding != null) viewModel.flushInBackground()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        attachmentsViewModel.reconcileFileCleanup()
        reconcilePendingCameraCapture()
    }

    private fun reconcilePendingCameraCapture() {
        val reference = cameraCaptureViewModel.peek() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            if (cameraCaptureManager.restoreCapture(reference.captureId) == null) {
                cameraCaptureViewModel.clear(reference.captureId)
            }
        }
    }

    override fun onDestroyView() {
        binding?.attachmentsList?.adapter = null
        attachmentAdapter = null
        binding = null
        lastTitleInputValue = null
        lastBodyInputValue = null
        lastTagsInputValue = null
        super.onDestroyView()
    }

    private fun configureToolbar(currentBinding: FragmentNoteEditorBinding) {
        currentBinding.toolbar.setNavigationOnClickListener { viewModel.requestClose() }
        currentBinding.toolbar.setOnMenuItemClickListener(::onMenuItemSelected)
        currentBinding.retryButton.setOnClickListener { viewModel.retryLoad() }
    }

    private fun configureAttachments(currentBinding: FragmentNoteEditorBinding) {
        val adapter = EditorAttachmentAdapter(
            imageLoader = requireContext().appContainer().imageLoader,
            onAdd = ::showAttachmentSourceChooser,
            onOpen = { attachment ->
                (activity as? MainNavigator)?.openAttachment(attachment.id)
            },
        )
        attachmentAdapter = adapter
        currentBinding.attachmentsList.adapter = adapter
        currentBinding.attachmentsList.setHasFixedSize(true)
        currentBinding.attachmentsList.itemAnimator = null
        currentBinding.attachmentsRetry.setOnClickListener { attachmentsViewModel.retry() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                attachmentsViewModel.state.collect { state ->
                    currentBinding.attachmentsLoading.isVisible = state is EditorAttachmentsState.Loading
                    currentBinding.attachmentsRetry.isVisible = state is EditorAttachmentsState.Error
                    val rows = buildList {
                        add(EditorAttachmentRow.Add)
                        if (state is EditorAttachmentsState.Content) {
                            state.attachments.forEach { add(EditorAttachmentRow.Attachment(it)) }
                        }
                    }
                    adapter.submitList(rows)
                }
            }
        }
    }

    private fun configureAttachmentWarningResults(currentBinding: FragmentNoteEditorBinding) {
        parentFragmentManager.setFragmentResultListener(
            ImportPreviewFragment.RESULT_IMPORT_WARNINGS,
            viewLifecycleOwner,
        ) { _, result ->
            val reasons = result
                .getStringArrayList(ImportPreviewFragment.RESULT_WARNING_REASONS)
                .orEmpty()
                .mapNotNull { name -> ImportWarningReason.entries.firstOrNull { it.name == name } }
                .distinct()
            if (reasons.isNotEmpty()) {
                val message = reasons.joinToString(separator = " ") { reason ->
                    getString(
                        when (reason) {
                            ImportWarningReason.SYNC_DELAYED -> R.string.import_warning_sync_delayed
                            ImportWarningReason.PREVIEW_UNAVAILABLE ->
                                R.string.import_warning_preview_unavailable
                            ImportWarningReason.FILE_CLEANUP_PENDING ->
                                R.string.import_warning_cleanup_pending
                            ImportWarningReason.LOCAL_MAINTENANCE_PENDING ->
                                R.string.import_warning_maintenance_pending
                        },
                    )
                }
                Snackbar.make(currentBinding.root, message, Snackbar.LENGTH_LONG).show()
            }
        }
        parentFragmentManager.setFragmentResultListener(
            AttachmentViewerFragment.RESULT_DELETE_WARNING,
            viewLifecycleOwner,
        ) { _, result ->
            val reasons = result
                .getStringArrayList(AttachmentViewerFragment.RESULT_DELETE_WARNING_REASONS)
                .orEmpty()
                .mapNotNull { name ->
                    AttachmentDeleteWarningReason.entries.firstOrNull { it.name == name }
                }
                .distinct()
            if (reasons.isNotEmpty()) {
                val message = reasons.joinToString(separator = " ") { reason ->
                    getString(
                        when (reason) {
                            AttachmentDeleteWarningReason.SYNC_DELAYED ->
                                R.string.delete_warning_sync_delayed
                            AttachmentDeleteWarningReason.FILE_CLEANUP_PENDING ->
                                R.string.delete_warning_cleanup_pending
                        },
                    )
                }
                Snackbar.make(currentBinding.root, message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun configureInputs(currentBinding: FragmentNoteEditorBinding) {
        currentBinding.titleInput.addSafeTextChangedListener { value ->
            lastTitleInputValue = value
            viewModel.onTitleChanged(value)
        }
        currentBinding.bodyInput.addSafeTextChangedListener { value ->
            lastBodyInputValue = value
            viewModel.onBodyChanged(value)
        }
        currentBinding.tagsInput.addSafeTextChangedListener { value ->
            lastTagsInputValue = value
            viewModel.onTagsChanged(value)
        }
        currentBinding.titleInput.addCodePointLimit(VaultConstraints.MAX_NOTE_TITLE_CHARACTERS)
        currentBinding.bodyInput.addCodePointLimit(VaultConstraints.MAX_NOTE_BODY_CHARACTERS)
        currentBinding.tagsInput.addCodePointLimit(VaultConstraints.MAX_NOTE_TAG_TEXT_CHARACTERS)
        currentBinding.saveRetryButton.setOnClickListener { viewModel.retrySave() }
    }

    private fun configureBackHandling() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    viewModel.requestClose()
                }
            },
        )
    }

    private fun collectViewModel(currentBinding: FragmentNoteEditorBinding) {
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

    private fun render(currentBinding: FragmentNoteEditorBinding, state: EditorUiState) {
        val isContent = state is EditorUiState.Content
        currentBinding.loadingIndicator.isVisible = state is EditorUiState.Loading
        currentBinding.errorState.isVisible = state is EditorUiState.Error
        currentBinding.saveStatusRow.isVisible = isContent
        currentBinding.saveRetryButton.isVisible = false
        currentBinding.titleContainer.isVisible = isContent
        currentBinding.bodyContainer.isVisible = isContent
        currentBinding.tagsContainer.isVisible = isContent
        currentBinding.attachmentsSection.isVisible = isContent

        when (state) {
            EditorUiState.Loading -> setEditorActionsEnabled(currentBinding, false)
            is EditorUiState.Error -> {
                setEditorActionsEnabled(currentBinding, false)
                currentBinding.errorTitle.setText(
                    if (state.noteMissing) R.string.note_missing_title else R.string.load_failed_title,
                )
                currentBinding.errorMessage.setText(
                    if (state.noteMissing) R.string.note_missing_message else R.string.editor_load_failed,
                )
                currentBinding.retryButton.isVisible = state.retryable
            }

            is EditorUiState.Content -> {
                setEditorActionsEnabled(currentBinding, true)
                renderDraft(currentBinding, state)
            }
        }
    }

    private fun renderDraft(
        currentBinding: FragmentNoteEditorBinding,
        state: EditorUiState.Content,
    ) {
        isRendering = true
        try {
            if (lastTitleInputValue !== state.draft.title) {
                currentBinding.titleInput.replaceTextIfDifferent(state.draft.title)
                lastTitleInputValue = state.draft.title
            }
            if (lastBodyInputValue !== state.draft.body) {
                currentBinding.bodyInput.replaceTextIfDifferent(state.draft.body)
                lastBodyInputValue = state.draft.body
            }
            if (lastTagsInputValue !== state.draft.tagsText) {
                currentBinding.tagsInput.replaceTextIfDifferent(state.draft.tagsText)
                lastTagsInputValue = state.draft.tagsText
            }
        } finally {
            isRendering = false
        }

        currentBinding.toolbar.title = state.draft.title.ifBlank {
            getString(R.string.untitled_note)
        }
        val colorStyle = state.draft.color.toStyle()
        val surfaceColor = ContextCompat.getColor(requireContext(), colorStyle.surfaceColor)
        val titleColor = ContextCompat.getColor(requireContext(), colorStyle.titleColor)
        currentBinding.root.setBackgroundColor(surfaceColor)
        currentBinding.titleInput.setTextColor(titleColor)
        currentBinding.toolbar.setTitleTextColor(titleColor)
        currentBinding.saveStatus.setText(
            when (state.saveStatus) {
                EditorSaveStatus.DIRTY -> R.string.unsaved_changes
                EditorSaveStatus.SAVING -> R.string.saving
                EditorSaveStatus.SAVED -> R.string.saved
                EditorSaveStatus.FAILED -> R.string.save_failed
            },
        )
        currentBinding.saveRetryButton.isVisible =
            state.saveStatus == EditorSaveStatus.FAILED &&
                state.saveRetryable &&
                !state.isClosing
        val editorEnabled = !state.isClosing
        currentBinding.titleInput.isEnabled = editorEnabled
        currentBinding.bodyInput.isEnabled = editorEnabled
        currentBinding.tagsInput.isEnabled = editorEnabled
        setEditorActionsEnabled(
            currentBinding,
            enabled = !state.isClosing && !state.isMetadataSaving,
        )

        val pinItem = currentBinding.toolbar.menu.findItem(R.id.action_pin)
        pinItem.isCheckable = true
        pinItem.isChecked = state.draft.isPinned
        pinItem.title = getString(
            if (state.draft.isPinned) R.string.unpin_note else R.string.pin_note,
        )
        pinItem.icon?.state = checkedState(state.draft.isPinned)

        val favoriteItem = currentBinding.toolbar.menu.findItem(R.id.action_favorite)
        favoriteItem.isCheckable = true
        favoriteItem.isChecked = state.draft.isFavorite
        favoriteItem.title = getString(
            if (state.draft.isFavorite) R.string.unfavorite_note else R.string.favorite_note,
        )
        favoriteItem.icon?.state = checkedState(state.draft.isFavorite)

        val archiveItem = currentBinding.toolbar.menu.findItem(R.id.action_archive)
        archiveItem.title = getString(
            if (state.draft.isArchived) R.string.unarchive_note else R.string.archive_note,
        )
        archiveItem.setIcon(
            if (state.draft.isArchived) R.drawable.ic_restore else R.drawable.ic_archive,
        )
    }

    private fun setEditorActionsEnabled(
        currentBinding: FragmentNoteEditorBinding,
        enabled: Boolean,
    ) {
        for (index in 0 until currentBinding.toolbar.menu.size) {
            currentBinding.toolbar.menu[index].isEnabled = enabled
        }
    }

    private fun onMenuItemSelected(item: MenuItem): Boolean {
        val state = viewModel.uiState.value as? EditorUiState.Content ?: return false
        return when (item.itemId) {
            R.id.action_pin -> {
                viewModel.setPinned(!state.draft.isPinned)
                true
            }

            R.id.action_favorite -> {
                viewModel.setFavorite(!state.draft.isFavorite)
                true
            }

            R.id.action_archive -> {
                viewModel.archiveAndClose()
                true
            }

            R.id.action_add_attachment -> {
                showAttachmentSourceChooser()
                true
            }

            R.id.action_color -> {
                showColorChooser(state.draft.color)
                true
            }

            R.id.action_delete -> {
                viewModel.moveToTrashAndClose()
                true
            }

            else -> false
        }
    }

    private fun showColorChooser(selected: VaultItemColor) {
        val colors = VaultItemColor.entries
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.choose_item_color)
            .setSingleChoiceItems(
                colors.map { getString(it.toStyle().label) }.toTypedArray(),
                colors.indexOf(selected),
            ) { dialog, index ->
                colors.getOrNull(index)?.let(viewModel::setColor)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAttachmentSourceChooser() {
        if (viewModel.uiState.value !is EditorUiState.Content) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_attachment)
            .setItems(
                arrayOf(
                    getString(R.string.choose_photos),
                    getString(R.string.choose_documents),
                    getString(R.string.take_photo),
                ),
            ) { _, index ->
                when (index) {
                    0 -> launchPhotoPicker()
                    1 -> launchDocumentPicker()
                    2 -> launchCamera()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchPhotoPicker() {
        try {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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

    private fun launchCamera() {
        viewLifecycleOwner.lifecycleScope.launch {
            val pending = cameraCaptureManager.createCapture().getOrElse {
                showMessage(R.string.camera_capture_failed)
                return@launch
            }
            cameraCaptureViewModel.replace(pending)?.let { previous ->
                val priorCapture = previous.inMemoryCapture
                    ?: cameraCaptureManager.restoreCapture(previous.captureId)
                cameraCaptureManager.deleteCapture(priorCapture)
            }
            try {
                cameraCapture.launch(pending.source.uri)
            } catch (_: ActivityNotFoundException) {
                val reference = cameraCaptureViewModel.peek()
                val capture = reference?.inMemoryCapture
                    ?: reference?.captureId?.let { cameraCaptureManager.restoreCapture(it) }
                deleteCaptureAndClear(reference, capture)
                showMessage(R.string.camera_unavailable)
            } catch (_: SecurityException) {
                val reference = cameraCaptureViewModel.peek()
                val capture = reference?.inMemoryCapture
                    ?: reference?.captureId?.let { cameraCaptureManager.restoreCapture(it) }
                deleteCaptureAndClear(reference, capture)
                showMessage(R.string.camera_capture_failed)
            }
        }
    }

    private suspend fun deleteCaptureAndClear(
        reference: PendingCameraReference?,
        capture: PendingCameraCapture?,
    ) {
        cameraCaptureManager.deleteCapture(capture)
        val captureId = reference?.captureId ?: return
        if (cameraCaptureManager.restoreCapture(captureId) == null) {
            cameraCaptureViewModel.clear(captureId)
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
        if (!openImport(
            uniqueUris.map { uri -> ImportSource(uri, ImportSourceKind.EXTERNAL) },
        )) {
            showMessage(R.string.import_preview_unavailable)
        }
    }

    private fun openImport(
        sources: List<ImportSource>,
        cameraCaptureId: String? = null,
    ): Boolean = (activity as? MainNavigator)?.openImportPreview(
            parentItemId = itemId,
            incomingImport = IncomingImport(sharedText = null, sources = sources),
            cameraCaptureId = cameraCaptureId,
        )
        ?: false

    private fun showMessage(message: Int) {
        binding?.root?.let { root -> Snackbar.make(root, message, Snackbar.LENGTH_LONG).show() }
    }

    private fun handleEvent(event: EditorEvent) {
        when (event) {
            EditorEvent.NavigateBack -> (activity as? MainNavigator)?.navigateBack()
            is EditorEvent.ShowError -> showError(event.error)
        }
    }

    private fun showError(error: AppError) {
        val root = binding?.root ?: return
        val message = when (error) {
            is AppError.SyncSchedulingFailure -> R.string.sync_schedule_failed
            is AppError.InvalidInput -> {
                if (error.field == "tags") {
                    R.string.invalid_tags_message
                } else {
                    R.string.invalid_note_content_message
                }
            }

            else -> R.string.operation_failed
        }
        val snackbar = Snackbar.make(root, message, Snackbar.LENGTH_LONG)
        snackbar.show()
    }

    private fun TextInputEditText.addSafeTextChangedListener(onChanged: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isRendering) onChanged(text?.toString().orEmpty())
            }

            override fun afterTextChanged(editable: Editable?) = Unit
        })
    }

    private fun TextInputEditText.replaceTextIfDifferent(value: String) {
        if (text.contentEquals(value)) return
        val previousSelection = selectionStart.coerceAtLeast(0)
        setText(value)
        setSelection(previousSelection.coerceAtMost(value.length))
    }

    private fun Editable?.contentEquals(value: String): Boolean {
        if (this == null) return value.isEmpty()
        if (length != value.length) return false
        for (index in value.indices) {
            if (this[index] != value[index]) return false
        }
        return true
    }

    private fun TextInputEditText.addCodePointLimit(maximumCodePoints: Int) {
        filters = filters + CodePointLengthFilter(maximumCodePoints)
    }

    private fun applyWindowInsets(currentBinding: FragmentNoteEditorBinding) {
        val rootStartPadding = currentBinding.root.paddingStart
        val rootEndPadding = currentBinding.root.paddingEnd
        val toolbarTopPadding = currentBinding.toolbar.paddingTop
        val tagsParams = currentBinding.tagsContainer.layoutParams as ViewGroup.MarginLayoutParams
        val tagsBottomMargin = tagsParams.bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(currentBinding.root) { _, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val isRtl = currentBinding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val startInset = if (isRtl) safeInsets.right else safeInsets.left
            val endInset = if (isRtl) safeInsets.left else safeInsets.right
            currentBinding.root.updatePaddingRelative(
                start = rootStartPadding + startInset,
                end = rootEndPadding + endInset,
            )
            currentBinding.toolbar.updatePadding(top = toolbarTopPadding + safeInsets.top)
            val updatedTagsParams =
                currentBinding.tagsContainer.layoutParams as ViewGroup.MarginLayoutParams
            updatedTagsParams.bottomMargin = tagsBottomMargin + maxOf(safeInsets.bottom, ime.bottom)
            currentBinding.tagsContainer.layoutParams = updatedTagsParams
            insets
        }
        ViewCompat.requestApplyInsets(currentBinding.root)
    }

    private fun checkedState(isChecked: Boolean): IntArray =
        if (isChecked) intArrayOf(android.R.attr.state_checked) else intArrayOf()

    companion object {
        const val BACK_STACK_NAME = "note_editor"
        private const val ARG_ITEM_ID = "item_id"
        private const val ANY_FILE_MIME_TYPE = "*/*"

        fun newInstance(itemId: String): NoteEditorFragment = NoteEditorFragment().apply {
            arguments = Bundle().apply { putString(ARG_ITEM_ID, itemId) }
        }
    }
}
