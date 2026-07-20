package com.vaultnote.feature.editor

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.load
import coil3.request.Disposable
import com.vaultnote.R
import com.vaultnote.core.common.model.VaultAttachment
import com.vaultnote.databinding.ItemEditorAddAttachmentBinding
import com.vaultnote.databinding.ItemEditorAttachmentBinding

internal sealed interface EditorAttachmentRow {
    data object Add : EditorAttachmentRow
    data class Attachment(val value: VaultAttachment) : EditorAttachmentRow
}

internal class EditorAttachmentAdapter(
    private val imageLoader: ImageLoader,
    private val onAdd: () -> Unit,
    private val onOpen: (VaultAttachment) -> Unit,
) : ListAdapter<EditorAttachmentRow, RecyclerView.ViewHolder>(DiffCallback) {
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = when (val item = getItem(position)) {
        EditorAttachmentRow.Add -> ADD_ID
        is EditorAttachmentRow.Attachment -> stableStringId(item.value.id)
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        EditorAttachmentRow.Add -> TYPE_ADD
        is EditorAttachmentRow.Attachment -> TYPE_ATTACHMENT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_ADD -> AddViewHolder(
                ItemEditorAddAttachmentBinding.inflate(inflater, parent, false),
                onAdd,
            )

            TYPE_ATTACHMENT -> AttachmentViewHolder(
                ItemEditorAttachmentBinding.inflate(inflater, parent, false),
                imageLoader,
                onOpen,
            )

            else -> error("Unexpected editor attachment row type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is AddViewHolder -> holder.bind()
            is AttachmentViewHolder -> holder.bind((getItem(position) as EditorAttachmentRow.Attachment).value)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is AttachmentViewHolder) holder.recycle()
        super.onViewRecycled(holder)
    }

    private class AddViewHolder(
        private val binding: ItemEditorAddAttachmentBinding,
        private val onAdd: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.root.setOnClickListener { onAdd() }
        }
    }

    private class AttachmentViewHolder(
        private val binding: ItemEditorAttachmentBinding,
        private val imageLoader: ImageLoader,
        private val onOpen: (VaultAttachment) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var disposable: Disposable? = null

        fun bind(attachment: VaultAttachment) {
            disposable?.dispose()
            binding.name.text = attachment.displayName
            binding.root.contentDescription = binding.root.context.getString(
                R.string.open_attachment_content_description,
                attachment.displayName,
            )
            binding.root.setOnClickListener { onOpen(attachment) }
            binding.thumbnail.setImageResource(iconFor(attachment.mimeType))
            binding.thumbnail.contentDescription = null
            val thumbnail = attachment.thumbnailFile ?: return
            val size = binding.root.resources.getDimensionPixelSize(R.dimen.editor_attachment_thumbnail)
            disposable = binding.thumbnail.load(thumbnail, imageLoader) {
                size(size, size)
            }
        }

        fun recycle() {
            disposable?.dispose()
            disposable = null
            binding.thumbnail.setImageDrawable(null)
            binding.root.setOnClickListener(null)
        }

        private fun iconFor(mimeType: String): Int = when {
            mimeType.startsWith("image/") -> R.drawable.ic_image
            mimeType == "application/pdf" -> R.drawable.ic_pdf
            else -> R.drawable.ic_document
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<EditorAttachmentRow>() {
        override fun areItemsTheSame(oldItem: EditorAttachmentRow, newItem: EditorAttachmentRow): Boolean =
            when {
                oldItem === EditorAttachmentRow.Add && newItem === EditorAttachmentRow.Add -> true
                oldItem is EditorAttachmentRow.Attachment && newItem is EditorAttachmentRow.Attachment ->
                    oldItem.value.id == newItem.value.id
                else -> false
            }

        override fun areContentsTheSame(oldItem: EditorAttachmentRow, newItem: EditorAttachmentRow): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val TYPE_ADD = 1
        private const val TYPE_ATTACHMENT = 2
        private const val ADD_ID = Long.MIN_VALUE

        private fun stableStringId(value: String): Long {
            var hash = -0x340d631b7bdddcdbL
            value.forEach { character ->
                hash = hash xor character.code.toLong()
                hash *= 0x100000001b3L
            }
            return if (hash == ADD_ID) ADD_ID + 1 else hash
        }
    }
}
