package com.vaultnote.feature.viewer

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.load
import coil3.request.Disposable
import com.vaultnote.R
import com.vaultnote.core.common.model.VaultAttachment
import com.vaultnote.databinding.ItemAttachmentViewerPageBinding

internal data class AttachmentViewerPage(
    val attachment: VaultAttachment,
    val previewUri: Uri?,
    val selected: Boolean,
    val loading: Boolean,
)

internal class AttachmentViewerPageAdapter(
    private val imageLoader: ImageLoader,
) : ListAdapter<AttachmentViewerPage, AttachmentViewerPageAdapter.ViewHolder>(DiffCallback) {
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = stableId(getItem(position).attachment.id)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        binding = ItemAttachmentViewerPageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        ),
        imageLoader = imageLoader,
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: ViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    internal class ViewHolder(
        private val binding: ItemAttachmentViewerPageBinding,
        private val imageLoader: ImageLoader,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var imageRequest: Disposable? = null

        fun bind(page: AttachmentViewerPage) {
            imageRequest?.dispose()
            imageRequest = null
            val attachment = page.attachment
            val isImage = attachment.mimeType.startsWith("image/")
            binding.imagePreview.isVisible = isImage
            binding.documentIcon.isVisible = !isImage
            binding.loadingIndicator.isVisible = page.loading
            binding.imagePreview.contentDescription = attachment.displayName
            binding.documentIcon.contentDescription = attachment.displayName
            if (!isImage) {
                binding.imagePreview.setImageDrawable(null)
                binding.documentIcon.setImageResource(
                    if (attachment.mimeType == PDF_MIME_TYPE) {
                        R.drawable.ic_pdf
                    } else {
                        R.drawable.ic_document
                    },
                )
                return
            }

            val imageUri = page.previewUri ?: attachment.thumbnailUri
            if (imageUri == null) {
                binding.imagePreview.scaleType = ImageView.ScaleType.CENTER_INSIDE
                binding.imagePreview.setImageResource(R.drawable.ic_image)
                return
            }
            binding.imagePreview.scaleType = if (page.selected && page.previewUri != null) {
                ImageView.ScaleType.FIT_CENTER
            } else {
                ImageView.ScaleType.CENTER_INSIDE
            }
            val thumbnailPixels = binding.root.resources.getDimensionPixelSize(
                R.dimen.file_list_thumbnail,
            )
            imageRequest = binding.imagePreview.load(imageUri, imageLoader) {
                if (page.selected && page.previewUri != null) {
                    size(MAX_IMAGE_PREVIEW_PIXELS, MAX_IMAGE_PREVIEW_PIXELS)
                } else {
                    size(thumbnailPixels, thumbnailPixels)
                }
                listener(
                    onError = { _, _ ->
                        binding.imagePreview.scaleType = ImageView.ScaleType.CENTER_INSIDE
                        binding.imagePreview.setImageResource(R.drawable.ic_image)
                    },
                )
            }
        }

        fun recycle() {
            imageRequest?.dispose()
            imageRequest = null
            binding.imagePreview.setImageDrawable(null)
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<AttachmentViewerPage>() {
        override fun areItemsTheSame(
            oldItem: AttachmentViewerPage,
            newItem: AttachmentViewerPage,
        ): Boolean = oldItem.attachment.id == newItem.attachment.id

        override fun areContentsTheSame(
            oldItem: AttachmentViewerPage,
            newItem: AttachmentViewerPage,
        ): Boolean = oldItem == newItem
    }

    private companion object {
        const val PDF_MIME_TYPE: String = "application/pdf"
        const val MAX_IMAGE_PREVIEW_PIXELS: Int = 1_600

        fun stableId(value: String): Long {
            var result = -0x340d631b7bdddcdbL
            value.forEach { character ->
                result = result xor character.code.toLong()
                result *= 0x100000001b3L
            }
            return result
        }
    }
}
