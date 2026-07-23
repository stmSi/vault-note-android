package com.vaultnote.feature.files

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
import com.vaultnote.core.common.model.VaultAttachment
import com.vaultnote.databinding.ItemVaultFileBinding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

internal class FilesAdapter(
    private val imageLoader: ImageLoader,
    private val onOpen: (VaultAttachment) -> Unit,
) : ListAdapter<VaultAttachment, FilesAdapter.FileViewHolder>(DiffCallback) {
    private val dateFormatter = DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
    private val zoneId = ZoneId.systemDefault()

    init {
        setHasStableIds(true)
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun getItemId(position: Int): Long = stableLongId(getItem(position).id)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder =
        FileViewHolder(
            ItemVaultFileBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: FileViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    inner class FileViewHolder(
        private val binding: ItemVaultFileBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var thumbnailRequest: Disposable? = null

        fun bind(file: VaultAttachment) = with(binding) {
            thumbnailRequest?.dispose()
            name.text = file.displayName
            val date = Instant.ofEpochMilli(file.createdAtEpochMillis)
                .atZone(zoneId)
                .toLocalDate()
                .format(dateFormatter)
            metadata.text = root.context.getString(
                R.string.file_list_metadata,
                file.mimeType,
                Formatter.formatShortFileSize(root.context, file.fileSizeBytes),
                date,
            )
            root.contentDescription = root.context.getString(
                R.string.open_attachment_content_description,
                file.displayName,
            )
            root.setOnClickListener { onOpen(file) }
            thumbnail.scaleType = if (file.mimeType.startsWith("image/")) {
                android.widget.ImageView.ScaleType.CENTER_CROP
            } else {
                android.widget.ImageView.ScaleType.CENTER_INSIDE
            }
            thumbnail.setImageResource(iconFor(file.mimeType))
            val thumbnailUri = file.thumbnailUri ?: return@with
            val size = root.resources.getDimensionPixelSize(R.dimen.file_list_thumbnail)
            thumbnailRequest = thumbnail.load(thumbnailUri, imageLoader) { size(size, size) }
        }

        fun recycle() {
            thumbnailRequest?.dispose()
            thumbnailRequest = null
            binding.thumbnail.setImageDrawable(null)
            binding.root.setOnClickListener(null)
        }

        private fun iconFor(mimeType: String): Int = when {
            mimeType.startsWith("image/") -> R.drawable.ic_image
            mimeType == "application/pdf" -> R.drawable.ic_pdf
            else -> R.drawable.ic_document
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<VaultAttachment>() {
        override fun areItemsTheSame(oldItem: VaultAttachment, newItem: VaultAttachment): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: VaultAttachment, newItem: VaultAttachment): Boolean =
            oldItem == newItem
    }

    private companion object {
        fun stableLongId(id: String): Long {
            val uuid = runCatching { UUID.fromString(id) }.getOrNull()
            val value = if (uuid != null) {
                uuid.mostSignificantBits xor uuid.leastSignificantBits
            } else {
                id.fold(FNV_OFFSET_BASIS) { hash, char ->
                    (hash xor char.code.toLong()) * FNV_PRIME
                }
            }
            return if (value == RecyclerView.NO_ID) Long.MIN_VALUE else value
        }

        const val FNV_OFFSET_BASIS: Long = -3750763034362895579L
        const val FNV_PRIME: Long = 1099511628211L
    }
}
