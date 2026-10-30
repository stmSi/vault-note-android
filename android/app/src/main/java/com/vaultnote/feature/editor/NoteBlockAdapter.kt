package com.vaultnote.feature.editor

import android.graphics.Rect
import android.text.Editable
import android.text.TextWatcher
import android.text.InputFilter
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.vaultnote.core.common.model.NoteBlock
import com.vaultnote.core.common.model.NoteBlockType
import com.vaultnote.core.common.model.NoteBodyDocument
import com.vaultnote.databinding.ItemNoteBlockBinding
import java.util.UUID

internal class NoteBlockAdapter(
    private val onDocumentChanged: (NoteBodyDocument) -> Unit,
    private val onBodyFocusChanged: (Boolean) -> Unit,
    private val onBlockFocusRequested: (Int) -> Unit,
    private val onHistoryShortcut: (KeyEvent) -> Boolean = { false },
    private val onBlockReadOnlyChanged: (String, Boolean) -> Unit = { _, _ -> },
) : RecyclerView.Adapter<NoteBlockAdapter.Holder>() {
    private val blocks = mutableListOf<NoteBlock>()
    private var pendingFocus: PendingFocus? = null
    private var documentReadOnly = false
    private var interactionEnabled = true

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = blocks[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemNoteBlockBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun getItemCount(): Int = blocks.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(
            block = blocks[position],
            showBodyHint = blocks.size == 1 && position == 0,
        )
    }

    override fun onViewRecycled(holder: Holder) {
        holder.recycle()
    }

    fun submitDocument(document: NoteBodyDocument) {
        if (blocks == document.blocks && documentReadOnly == document.isReadOnly) return
        documentReadOnly = document.isReadOnly
        blocks.clear()
        blocks.addAll(document.blocks)
        notifyDataSetChanged()
    }

    fun setInteractionEnabled(enabled: Boolean) {
        if (interactionEnabled == enabled) return
        interactionEnabled = enabled
        notifyItemRangeChanged(0, itemCount)
    }

    private fun canEdit(blockId: String): Boolean = interactionEnabled && !documentReadOnly &&
        blocks.firstOrNull { it.id == blockId }?.isReadOnly == false

    fun addBlock(type: NoteBlockType) {
        if (!interactionEnabled || documentReadOnly) return
        val position = blocks.size
        val block = NoteBlock(
            id = UUID.randomUUID().toString(),
            type = type,
            text = "",
        )
        blocks += block
        pendingFocus = PendingFocus(block.id, 0)
        notifyItemInserted(position)
        if (position == 1) notifyItemChanged(0)
        dispatchDocument()
        onBlockFocusRequested(position)
    }

    private fun updateText(blockId: String, value: String) {
        if (!canEdit(blockId)) return
        val index = blocks.indexOfFirst { it.id == blockId }
        if (index == -1 || blocks[index].text == value) return
        blocks[index] = blocks[index].copy(text = value)
        dispatchDocument()
    }

    private fun updateChecked(blockId: String, checked: Boolean) {
        if (!canEdit(blockId)) return
        val index = blocks.indexOfFirst { it.id == blockId }
        if (index == -1 || blocks[index].isChecked == checked) return
        blocks[index] = blocks[index].copy(isChecked = checked)
        dispatchDocument()
    }

    private fun splitBlock(blockId: String, before: String, after: String) {
        if (!canEdit(blockId)) return
        val index = blocks.indexOfFirst { it.id == blockId }
        if (index == -1) return
        val original = blocks[index]
        blocks[index] = original.copy(text = before)
        val nextType = if (
            original.type == NoteBlockType.CHECKLIST_ITEM && before.isEmpty() && after.isEmpty()
        ) {
            NoteBlockType.PARAGRAPH
        } else {
            original.type
        }
        val nextBlocks = after.split('\n').map { line ->
            NoteBlock(id = UUID.randomUUID().toString(), type = nextType, text = line)
        }
        val nextBlock = nextBlocks.first()
        blocks.addAll(index + 1, nextBlocks)
        pendingFocus = PendingFocus(nextBlock.id, 0)
        notifyItemChanged(index)
        notifyItemRangeInserted(index + 1, nextBlocks.size)
        dispatchDocument()
        onBlockFocusRequested(index + 1)
    }

    private fun deleteEmptyBlock(blockId: String): Boolean {
        if (!canEdit(blockId)) return false
        val index = blocks.indexOfFirst { it.id == blockId }
        if (index <= 0 || blocks[index].text.isNotEmpty()) return false
        val previousBlock = blocks[index - 1]
        blocks.removeAt(index)
        pendingFocus = if (canEdit(previousBlock.id)) {
            PendingFocus(previousBlock.id, previousBlock.text.length)
        } else {
            null
        }
        notifyItemRemoved(index)
        notifyItemChanged(index - 1)
        dispatchDocument()
        onBlockFocusRequested(index - 1)
        return true
    }

    private fun dispatchDocument() {
        onDocumentChanged(NoteBodyDocument(blocks = blocks.toList(), isReadOnly = documentReadOnly))
    }

    inner class Holder(
        private val binding: ItemNoteBlockBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var watcher: TextWatcher? = null
        private var boundId: String? = null
        private var rendering = false
        private val editableKeyListener = binding.text.keyListener

        init {
            binding.text.filters = binding.text.filters + InputFilter { _, _, _, dest, start, end ->
                if (rendering || boundId?.let(::canEdit) == true) null else dest.subSequence(start, end)
            }
        }

        fun bind(block: NoteBlock, showBodyHint: Boolean) = with(binding) {
            recycle()
            boundId = block.id
            rendering = true
            text.setText(block.text)
            text.setSelection(text.text?.length ?: 0)
            text.hint = if (showBodyHint) {
                text.context.getString(com.vaultnote.R.string.note_body_hint)
            } else {
                null
            }
            checkBox.visibility =
                if (block.type == NoteBlockType.CHECKLIST_ITEM) View.VISIBLE else View.GONE
            checkBox.isChecked = block.isChecked
            val editable = canEdit(block.id)
            text.keyListener = if (editable) editableKeyListener else null
            text.setTextIsSelectable(true)
            text.isCursorVisible = editable
            text.showSoftInputOnFocus = editable
            checkBox.isEnabled = editable
            readOnlyButton.setImageResource(
                if (block.isReadOnly) com.vaultnote.R.drawable.ic_lock else com.vaultnote.R.drawable.ic_lock_open,
            )
            readOnlyButton.contentDescription = text.context.getString(
                if (block.isReadOnly) com.vaultnote.R.string.line_read_only_off
                else com.vaultnote.R.string.line_read_only_on,
            )
            readOnlyButton.tooltipText = readOnlyButton.contentDescription
            readOnlyButton.isEnabled = interactionEnabled && !documentReadOnly
            readOnlyButton.setOnClickListener {
                if (interactionEnabled && !documentReadOnly) {
                    onBlockReadOnlyChanged(block.id, !block.isReadOnly)
                }
            }
            rendering = false
            watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                    Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                    Unit

                override fun afterTextChanged(editable: Editable?) {
                    if (rendering) return
                    if (boundId?.let(::canEdit) != true) {
                        rendering = true
                        text.setText(blocks.firstOrNull { it.id == boundId }?.text.orEmpty())
                        rendering = false
                        return
                    }
                    val value = editable?.toString().orEmpty()
                    val newline = value.indexOf('\n')
                    if (newline >= 0) {
                        rendering = true
                        text.setText(value.substring(0, newline))
                        text.setSelection(text.text?.length ?: 0)
                        rendering = false
                        boundId?.let { id ->
                            splitBlock(id, value.substring(0, newline), value.substring(newline + 1))
                        }
                    } else {
                        boundId?.let { id -> updateText(id, value) }
                    }
                    text.requestCursorVisibility()
                }
            }.also(text::addTextChangedListener)
            checkBox.setOnCheckedChangeListener { _, checked ->
                if (!rendering) boundId?.let { id -> updateChecked(id, checked) }
            }
            text.setOnFocusChangeListener { _, focused ->
                onBodyFocusChanged(focused)
                if (focused) text.requestCursorVisibility()
            }
            text.setOnKeyListener { _, keyCode, event ->
                onHistoryShortcut(event) ||
                    keyCode == KeyEvent.KEYCODE_DEL &&
                    event.action == KeyEvent.ACTION_DOWN &&
                    text.text.isNullOrEmpty() &&
                    boundId?.let(::deleteEmptyBlock) == true
            }
            pendingFocus?.takeIf { it.blockId == block.id }?.let { request ->
                pendingFocus = null
                text.post {
                    if (boundId != request.blockId) return@post
                    text.requestFocus()
                    text.setSelection(
                        request.cursorOffset.coerceAtMost(text.text?.length ?: 0),
                    )
                    text.requestCursorVisibility()
                }
            }
        }

        fun recycle() {
            watcher?.let(binding.text::removeTextChangedListener)
            watcher = null
            binding.checkBox.setOnCheckedChangeListener(null)
            binding.text.onFocusChangeListener = null
            binding.text.setOnKeyListener(null)
            binding.readOnlyButton.setOnClickListener(null)
            boundId = null
        }
    }

    private data class PendingFocus(
        val blockId: String,
        val cursorOffset: Int,
    )
}

internal fun TextInputEditText.requestCursorVisibility() {
    post {
        if (!isFocused) return@post
        if (isLayoutRequested || layout == null) {
            doOnNextLayout { revealCursorImmediately() }
        } else {
            revealCursorImmediately()
        }
    }
}

private fun TextInputEditText.revealCursorImmediately() {
    if (!isFocused) return
    val textLayout = layout ?: return
    val cursorOffset = selectionStart.coerceIn(0, text?.length ?: 0)
    val cursorLine = textLayout.getLineForOffset(cursorOffset)
    val breathingRoom = resources.getDimensionPixelSize(com.vaultnote.R.dimen.space_s)
    val cursorBounds = Rect(
        0,
        (totalPaddingTop + textLayout.getLineTop(cursorLine) - breathingRoom).coerceAtLeast(0),
        width,
        totalPaddingTop + textLayout.getLineBottom(cursorLine) + breathingRoom,
    )
    requestRectangleOnScreen(cursorBounds, true)
}
