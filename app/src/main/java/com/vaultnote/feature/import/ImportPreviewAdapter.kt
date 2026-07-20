package com.vaultnote.feature.importing

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vaultnote.R
import com.vaultnote.databinding.ItemImportCandidateBinding

internal data class ImportCandidateRow(
    val stableId: Long,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val accepted: Boolean,
)

internal class ImportPreviewAdapter :
    ListAdapter<ImportCandidateRow, ImportPreviewAdapter.ViewHolder>(DiffCallback) {
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).stableId

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ItemImportCandidateBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    internal class ViewHolder(
        private val binding: ItemImportCandidateBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: ImportCandidateRow) {
            val context = binding.root.context
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
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ImportCandidateRow>() {
        override fun areItemsTheSame(oldItem: ImportCandidateRow, newItem: ImportCandidateRow): Boolean =
            oldItem.stableId == newItem.stableId

        override fun areContentsTheSame(oldItem: ImportCandidateRow, newItem: ImportCandidateRow): Boolean =
            oldItem == newItem
    }
}
