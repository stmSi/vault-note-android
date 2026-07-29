package com.vaultnote.feature.editor

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.vaultnote.R
import com.vaultnote.app.MainNavigator
import com.vaultnote.app.appContainer
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.VaultConstraints
import com.vaultnote.core.common.model.VaultItemColor
import com.vaultnote.core.common.model.NoteBlockType
import com.vaultnote.core.common.model.DatedEntry
import com.vaultnote.core.common.model.DatedEntryDraft
import com.vaultnote.core.common.model.DatedEntryType
import com.vaultnote.core.common.model.RecurrenceRule
import com.vaultnote.core.common.model.RecurrenceUnit
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
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class NoteEditorFragment : Fragment() {
    private var binding: FragmentNoteEditorBinding? = null
    private var isRendering = false
    private var lastTitleInputValue: String? = null
    private var lastTagsInputValue: String? = null
    private var attachmentAdapter: EditorAttachmentAdapter? = null
    private var bodyEditorHasFocus = false
    private var keyboardIsVisible = false
    private var metadataPanelSelection = MetadataPanelSelection.ATTACHMENTS
    private lateinit var noteBlockAdapter: NoteBlockAdapter
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

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) showMessage(R.string.notification_permission_needed)
    }

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
        currentBinding.root.requestFocus()
        configureToolbar(currentBinding)
        configureBlockEditor(currentBinding)
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
        binding?.bodyBlocks?.adapter = null
        attachmentAdapter = null
        bodyEditorHasFocus = false
        keyboardIsVisible = false
        binding = null
        lastTitleInputValue = null
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
        currentBinding.tagsInput.addSafeTextChangedListener { value ->
            lastTagsInputValue = value
            viewModel.onTagsChanged(value)
        }
        currentBinding.titleInput.addCodePointLimit(VaultConstraints.MAX_NOTE_TITLE_CHARACTERS)
        currentBinding.tagsInput.addCodePointLimit(VaultConstraints.MAX_NOTE_TAG_TEXT_CHARACTERS)
        currentBinding.saveRetryButton.setOnClickListener { viewModel.retrySave() }
        currentBinding.addParagraphButton.setOnClickListener {
            noteBlockAdapter.addBlock(NoteBlockType.PARAGRAPH)
        }
        currentBinding.addChecklistButton.setOnClickListener {
            noteBlockAdapter.addBlock(NoteBlockType.CHECKLIST_ITEM)
        }
        currentBinding.tagsButton.setOnClickListener {
            toggleMetadataPanel(currentBinding, showTags = true)
        }
        currentBinding.attachmentsButton.setOnClickListener {
            toggleMetadataPanel(currentBinding, showTags = false)
        }
        currentBinding.datesButton.setOnClickListener {
            leaveBodyTypingMode(currentBinding)
            showDatesDialog()
        }
    }

    private fun configureBlockEditor(currentBinding: FragmentNoteEditorBinding) {
        noteBlockAdapter = NoteBlockAdapter(
            onDocumentChanged = viewModel::onBodyDocumentChanged,
            onBodyFocusChanged = {
                currentBinding.bodyBlocks.post {
                    if (binding !== currentBinding) return@post
                    bodyEditorHasFocus = currentBinding.bodyBlocks.hasFocus()
                    updateEditorChrome(currentBinding)
                }
            },
            onBlockFocusRequested = { position ->
                currentBinding.bodyBlocks.post {
                    if (binding !== currentBinding) return@post
                    currentBinding.bodyBlocks.scrollToPosition(position)
                }
            },
        )
        currentBinding.bodyBlocks.layoutManager = LinearLayoutManager(requireContext())
        currentBinding.bodyBlocks.adapter = noteBlockAdapter
        currentBinding.bodyBlocks.itemAnimator = null
    }

    private fun toggleMetadataPanel(
        currentBinding: FragmentNoteEditorBinding,
        showTags: Boolean,
    ) {
        val requestedPanel = if (showTags) {
            MetadataPanelSelection.TAGS
        } else {
            MetadataPanelSelection.ATTACHMENTS
        }
        val samePanelVisible = currentBinding.metadataPanel.isVisible &&
            metadataPanelSelection == requestedPanel
        metadataPanelSelection = if (samePanelVisible) {
            MetadataPanelSelection.NONE
        } else {
            requestedPanel
        }
        leaveBodyTypingMode(currentBinding)
        if (metadataPanelSelection == MetadataPanelSelection.TAGS) {
            currentBinding.tagsInput.requestFocus()
        }
    }

    private fun leaveBodyTypingMode(currentBinding: FragmentNoteEditorBinding) {
        currentBinding.root.requestFocus()
        WindowInsetsControllerCompat(requireActivity().window, currentBinding.root)
            .hide(WindowInsetsCompat.Type.ime())
        bodyEditorHasFocus = false
        updateEditorChrome(currentBinding)
    }

    private fun updateEditorChrome(currentBinding: FragmentNoteEditorBinding) {
        val isTypingBody = bodyEditorHasFocus && keyboardIsVisible
        val showMetadata = currentBinding.bodyBlocks.isVisible &&
            !bodyEditorHasFocus &&
            metadataPanelSelection != MetadataPanelSelection.NONE
        currentBinding.metadataPanel.isVisible = showMetadata
        currentBinding.attachmentsSection.isVisible =
            showMetadata && metadataPanelSelection == MetadataPanelSelection.ATTACHMENTS
        currentBinding.tagsContainer.isVisible =
            showMetadata && metadataPanelSelection == MetadataPanelSelection.TAGS
        currentBinding.toolbar.isVisible = !isTypingBody
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
        currentBinding.saveStatusRow.isVisible = false
        currentBinding.saveRetryButton.isVisible = false
        currentBinding.titleContainer.isVisible = isContent
        currentBinding.bodyBlocks.isVisible = isContent
        currentBinding.editorActionBar.isVisible = isContent
        updateEditorChrome(currentBinding)

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
            noteBlockAdapter.submitDocument(state.draft.bodyDocument)
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
        val surfaceColor = colorStyle.resolveSurface(requireContext())
        val titleColor = colorStyle.resolveTitle(requireContext())
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
        currentBinding.saveStatusRow.isVisible =
            state.saveStatus == EditorSaveStatus.FAILED
        val editorEnabled = !state.isClosing
        currentBinding.titleInput.isEnabled = editorEnabled
        currentBinding.tagsInput.isEnabled = editorEnabled
        currentBinding.bodyBlocks.isEnabled = editorEnabled
        currentBinding.editorActionBar.isEnabled = editorEnabled
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

    private fun showDatesDialog() {
        val state = viewModel.uiState.value as? EditorUiState.Content ?: return
        val entries = state.draft.datedEntries
        val labels = buildList {
            add(getString(R.string.add_date))
            entries.forEach { entry ->
                val date = Instant.ofEpochMilli(entry.occurrenceAtEpochMillis)
                    .atZone(ZoneId.of(entry.timeZoneId))
                val formatted = if (entry.isAllDay) {
                    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                } else {
                    date.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
                }
                add("${entry.label.ifBlank { dateTypeLabel(entry.type) }} — $formatted")
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.manage_dates)
            .setItems(labels.toTypedArray()) { _, index ->
                if (index == 0) showDateEditor(null) else showDateEditor(entries[index - 1])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDateEditor(existing: DatedEntry?) {
        val context = requireContext()
        val padding = resources.getDimensionPixelSize(R.dimen.space_m)
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
        }
        val types = DatedEntryType.entries
        val typeSpinner = android.widget.Spinner(context).apply {
            adapter = android.widget.ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                types.map(::dateTypeLabel),
            )
            setSelection(existing?.type?.let(types::indexOf) ?: 0)
        }
        val labelInput = android.widget.EditText(context).apply {
            hint = getString(R.string.date_label_hint)
            setText(existing?.label.orEmpty())
            maxLines = 1
        }
        val zone = existing?.timeZoneId?.let(ZoneId::of) ?: ZoneId.systemDefault()
        var selected = existing?.occurrenceAtEpochMillis
            ?.let { Instant.ofEpochMilli(it).atZone(zone) }
            ?: ZonedDateTime.now(zone).plusHours(1).withSecond(0).withNano(0)
        val dateButton = com.google.android.material.button.MaterialButton(context).apply {
            text = selected.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
            setOnClickListener {
                DatePickerDialog(
                    context,
                    { _, year, month, day ->
                        selected = selected.withYear(year).withMonth(month + 1).withDayOfMonth(day)
                        text = selected.format(
                            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM),
                        )
                    },
                    selected.year,
                    selected.monthValue - 1,
                    selected.dayOfMonth,
                ).show()
            }
        }
        val timeButton = com.google.android.material.button.MaterialButton(context).apply {
            text = selected.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
            setOnClickListener {
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        selected = selected.withHour(hour).withMinute(minute)
                        text = selected.format(
                            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT),
                        )
                    },
                    selected.hour,
                    selected.minute,
                    android.text.format.DateFormat.is24HourFormat(context),
                ).show()
            }
        }
        val allDay = com.google.android.material.checkbox.MaterialCheckBox(context).apply {
            text = getString(R.string.all_day)
            isChecked = existing?.isAllDay == true
            timeButton.isVisible = !isChecked
            setOnCheckedChangeListener { _, checked -> timeButton.isVisible = !checked }
        }
        val recurrenceOptions = listOf(
            getString(R.string.repeat_none),
            getString(R.string.repeat_daily),
            getString(R.string.repeat_weekly),
            getString(R.string.repeat_monthly),
            getString(R.string.repeat_yearly),
        )
        val recurrenceSpinner = android.widget.Spinner(context).apply {
            adapter = android.widget.ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                recurrenceOptions,
            )
            setSelection(
                when (existing?.recurrence?.unit) {
                    null -> 0
                    RecurrenceUnit.DAY -> 1
                    RecurrenceUnit.WEEK -> 2
                    RecurrenceUnit.MONTH -> 3
                    RecurrenceUnit.YEAR -> 4
                },
            )
        }
        val alertLeadTimes = listOf(0L, 10L, 60L, 1_440L, 10_080L)
        val alertSpinner = android.widget.Spinner(context).apply {
            adapter = android.widget.ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    getString(R.string.alert_at_time),
                    getString(R.string.alert_ten_minutes),
                    getString(R.string.alert_one_hour),
                    getString(R.string.alert_one_day),
                    getString(R.string.alert_one_week),
                ),
            )
            val existingLead = existing?.alerts?.firstOrNull()?.leadTimeMinutes ?: 0L
            setSelection(alertLeadTimes.indexOf(existingLead).coerceAtLeast(0))
        }
        container.addView(typeSpinner)
        container.addView(labelInput)
        container.addView(dateButton)
        container.addView(allDay)
        container.addView(timeButton)
        container.addView(recurrenceSpinner)
        container.addView(alertSpinner)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (existing == null) R.string.add_date else R.string.edit_date)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val occurrence = if (allDay.isChecked) {
                    selected.withHour(9).withMinute(0).withSecond(0).withNano(0)
                } else {
                    selected.withSecond(0).withNano(0)
                }
                val recurrence = when (recurrenceSpinner.selectedItemPosition) {
                    1 -> RecurrenceRule(1, RecurrenceUnit.DAY)
                    2 -> RecurrenceRule(1, RecurrenceUnit.WEEK)
                    3 -> RecurrenceRule(1, RecurrenceUnit.MONTH)
                    4 -> RecurrenceRule(1, RecurrenceUnit.YEAR)
                    else -> null
                }
                viewModel.saveDatedEntry(
                    DatedEntryDraft(
                        id = existing?.id,
                        type = types[typeSpinner.selectedItemPosition],
                        label = labelInput.text?.toString().orEmpty(),
                        occurrenceAtEpochMillis = occurrence.toInstant().toEpochMilli(),
                        isAllDay = allDay.isChecked,
                        timeZoneId = zone.id,
                        recurrence = recurrence,
                        alertLeadTimesMinutes = listOf(
                            alertLeadTimes[alertSpinner.selectedItemPosition],
                        ),
                    ),
                )
            }
        if (existing != null) {
            dialog.setNeutralButton(R.string.delete_note) { _, _ ->
                viewModel.deleteDatedEntry(existing.id)
            }
        }
        dialog.show()
    }

    private fun dateTypeLabel(type: DatedEntryType): String = getString(
        when (type) {
            DatedEntryType.REMINDER -> R.string.date_type_reminder
            DatedEntryType.DEADLINE -> R.string.date_type_deadline
            DatedEntryType.IMPORTANT_DATE -> R.string.date_type_important
            DatedEntryType.RENEWAL -> R.string.date_type_renewal
        },
    )

    private fun ensureReminderPermissions() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager =
                requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                showMessage(R.string.exact_alarm_reduced)
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(Uri.parse("package:${requireContext().packageName}")),
                    )
                }
            }
        }
    }

    private fun handleEvent(event: EditorEvent) {
        when (event) {
            EditorEvent.NavigateBack -> (activity as? MainNavigator)?.navigateBack()
            is EditorEvent.ShowError -> showError(event.error)
            EditorEvent.DatedEntrySaved -> {
                showMessage(R.string.date_saved)
                ensureReminderPermissions()
            }
            EditorEvent.DatedEntryDeleted -> showMessage(R.string.date_deleted)
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
        val actionBarBottomPadding = currentBinding.editorActionBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(currentBinding.root) { _, insets ->
            keyboardIsVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            updateEditorChrome(currentBinding)
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val keyboardInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val isRtl = currentBinding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val startInset = if (isRtl) safeInsets.right else safeInsets.left
            val endInset = if (isRtl) safeInsets.left else safeInsets.right
            currentBinding.root.updatePaddingRelative(
                start = rootStartPadding + startInset,
                end = rootEndPadding + endInset,
            )
            currentBinding.toolbar.updatePadding(top = toolbarTopPadding + safeInsets.top)
            currentBinding.editorActionBar.updatePadding(
                bottom = actionBarBottomPadding + maxOf(safeInsets.bottom, keyboardInsets.bottom),
            )
            if (keyboardIsVisible) {
                (currentBinding.bodyBlocks.findFocus() as? TextInputEditText)
                    ?.requestCursorVisibility()
            }
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

    private enum class MetadataPanelSelection {
        NONE,
        ATTACHMENTS,
        TAGS,
    }
}
