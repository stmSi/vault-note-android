package com.vaultnote.feature.editor

import android.graphics.Rect
import android.text.Editable
import android.text.TextWatcher
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
) : RecyclerView.Adapter<NoteBlockAdapter.Holder>() {
    private val blocks = mutableListOf<NoteBlock>()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = blocks[position].id.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemNoteBlockBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun getItemCount(): Int = blocks.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(blocks[position])
    }

    override fun onViewRecycled(holder: Holder) {
        holder.recycle()
    }

    fun submitDocument(document: NoteBodyDocument) {
        if (blocks == document.blocks) return
        blocks.clear()
        blocks.addAll(document.blocks)
        notifyDataSetChanged()
    }

    fun addBlock(type: NoteBlockType) {
        val position = blocks.size
        blocks += NoteBlock(
            id = UUID.randomUUID().toString(),
            type = type,
            text = "",
        )
        notifyItemInserted(position)
        dispatchDocument()
    }

    private fun updateText(blockId: String, value: String) {
        val index = blocks.indexOfFirst { it.id == blockId }
        if (index == -1 || blocks[index].text == value) return
        blocks[index] = blocks[index].copy(text = value)
        dispatchDocument()
    }

    private fun updateChecked(blockId: String, checked: Boolean) {
        val index = blocks.indexOfFirst { it.id == blockId }
        if (index == -1 || blocks[index].isChecked == checked) return
        blocks[index] = blocks[index].copy(isChecked = checked)
        dispatchDocument()
    }

    private fun splitBlock(blockId: String, before: String, after: String) {
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
        blocks.add(
            index + 1,
            NoteBlock(
                id = UUID.randomUUID().toString(),
                type = nextType,
                text = after,
            ),
        )
        notifyItemChanged(index)
        notifyItemInserted(index + 1)
        dispatchDocument()
    }

    private fun deleteEmptyBlock(blockId: String): Boolean {
        val index = blocks.indexOfFirst { it.id == blockId }
        if (index <= 0 || blocks[index].text.isNotEmpty()) return false
        blocks.removeAt(index)
        notifyItemRemoved(index)
        dispatchDocument()
        return true
    }

    private fun dispatchDocument() {
        onDocumentChanged(NoteBodyDocument(blocks = blocks.toList()))
    }

    inner class Holder(
        private val binding: ItemNoteBlockBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var watcher: TextWatcher? = null
        private var boundId: String? = null
        private var rendering = false

        fun bind(block: NoteBlock) = with(binding) {
            recycle()
            boundId = block.id
            rendering = true
            text.setText(block.text)
            text.setSelection(text.text?.length ?: 0)
            checkBox.visibility =
                if (block.type == NoteBlockType.CHECKLIST_ITEM) View.VISIBLE else View.GONE
            checkBox.isChecked = block.isChecked
            rendering = false
            watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                    Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                    Unit

                override fun afterTextChanged(editable: Editable?) {
                    if (rendering) return
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
                keyCode == KeyEvent.KEYCODE_DEL &&
                    event.action == KeyEvent.ACTION_DOWN &&
                    text.text.isNullOrEmpty() &&
                    boundId?.let(::deleteEmptyBlock) == true
            }
        }

        fun recycle() {
            watcher?.let(binding.text::removeTextChangedListener)
            watcher = null
            binding.checkBox.setOnCheckedChangeListener(null)
            binding.text.onFocusChangeListener = null
            binding.text.setOnKeyListener(null)
            boundId = null
        }
    }
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
