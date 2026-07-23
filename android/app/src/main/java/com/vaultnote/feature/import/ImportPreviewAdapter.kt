package com.vaultnote.feature.importing

import android.net.Uri
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.load
import coil3.request.Disposable
import com.vaultnote.R
import com.vaultnote.core.files.AttachmentCategory
import com.vaultnote.databinding.ItemImportCandidateBinding

internal data class ImportCandidateRow(
    val stableId: Long,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val accepted: Boolean,
    val renameEnabled: Boolean,
    val sourceUri: Uri,
    val category: AttachmentCategory?,
)

internal class ImportPreviewAdapter(
    private val imageLoader: ImageLoader,
    private val onRename: (ImportCandidateRow) -> Unit,
) :
    ListAdapter<ImportCandidateRow, ImportPreviewAdapter.ViewHolder>(DiffCallback) {
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).stableId

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ItemImportCandidateBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        imageLoader,
        onRename,
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    internal class ViewHolder(
        private val binding: ItemImportCandidateBinding,
        private val imageLoader: ImageLoader,
        private val onRename: (ImportCandidateRow) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var previewRequest: Disposable? = null

        fun bind(row: ImportCandidateRow) {
            val context = binding.root.context
            previewRequest?.dispose()
            binding.name.text = row.displayName
            binding.metadata.text = when {
                !row.accepted -> context.getString(R.string.file_not_supported)
                row.sizeBytes != null -> context.getString(
                    R.string.attachment_metadata,
                    row.mimeType.orEmpty(),
                    Formatter.formatShortFileSize(context, row.sizeBytes),
                )
                else -> row.mimeType.orEmpty()
            }
            binding.statusIcon.setImageResource(
                if (row.accepted) R.drawable.ic_check else R.drawable.ic_error,
            )
            binding.statusIcon.contentDescription = context.getString(
                if (row.accepted) R.string.file_ready else R.string.file_not_supported,
            )
            binding.renameButton.isEnabled = row.renameEnabled
            binding.renameButton.visibility = if (row.accepted) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
            binding.renameButton.setOnClickListener { onRename(row) }
            val fallbackIcon = iconFor(row.category)
            binding.filePreview.setImageResource(fallbackIcon)
            binding.filePreview.scaleType = if (row.category == AttachmentCategory.IMAGE) {
                android.widget.ImageView.ScaleType.CENTER_CROP
            } else {
                android.widget.ImageView.ScaleType.CENTER_INSIDE
            }
            if (row.accepted && row.category == AttachmentCategory.IMAGE) {
                val size = context.resources.getDimensionPixelSize(R.dimen.file_list_thumbnail)
                previewRequest = binding.filePreview.load(row.sourceUri, imageLoader) {
                    size(size, size)
                    listener(
                        onStart = { binding.filePreview.setImageResource(fallbackIcon) },
                        onError = { _, _ -> binding.filePreview.setImageResource(fallbackIcon) },
                    )
                }
            }
        }

        fun recycle() {
            previewRequest?.dispose()
            previewRequest = null
            binding.renameButton.setOnClickListener(null)
            binding.filePreview.setImageDrawable(null)
        }

        private fun iconFor(category: AttachmentCategory?): Int = when (category) {
            AttachmentCategory.IMAGE -> R.drawable.ic_image
            AttachmentCategory.PDF -> R.drawable.ic_pdf
            AttachmentCategory.TEXT,
            AttachmentCategory.DOCUMENT,
            null,
            -> R.drawable.ic_document
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ImportCandidateRow>() {
        override fun areItemsTheSame(oldItem: ImportCandidateRow, newItem: ImportCandidateRow): Boolean =
            oldItem.stableId == newItem.stableId

        override fun areContentsTheSame(oldItem: ImportCandidateRow, newItem: ImportCandidateRow): Boolean =
            oldItem == newItem
    }
}
