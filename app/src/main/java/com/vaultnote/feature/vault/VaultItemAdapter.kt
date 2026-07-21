package com.vaultnote.feature.vault

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vaultnote.R
import com.vaultnote.databinding.ItemVaultNoteBinding
import com.vaultnote.core.common.toStyle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

internal class VaultItemAdapter(
    private val onOpen: (itemId: String) -> Unit,
    private val onPinnedChanged: (itemId: String, pinned: Boolean) -> Unit,
    private val onFavoriteChanged: (itemId: String, favorite: Boolean) -> Unit,
    private val onRestore: (itemId: String, source: VaultSection) -> Unit,
) : ListAdapter<VaultListItem, VaultItemAdapter.VaultItemViewHolder>(DiffCallback) {
    private val dateFormatter = DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
    private val zoneId = ZoneId.systemDefault()

    init {
        setHasStableIds(true)
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun getItemId(position: Int): Long = stableLongId(getItem(position).note.id)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VaultItemViewHolder {
        val binding = ItemVaultNoteBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return VaultItemViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VaultItemViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VaultItemViewHolder(
        private val binding: ItemVaultNoteBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: VaultListItem) = with(binding) {
            val item = row.note
            val context = root.context
            val colorStyle = item.color.toStyle()
            root.setBackgroundColor(ContextCompat.getColor(context, colorStyle.surfaceColor))
            title.setTextColor(ContextCompat.getColor(context, colorStyle.titleColor))
            title.text = item.title.ifBlank { context.getString(R.string.untitled_note) }
            bodyPreview.text = item.bodyPreview
            bodyPreview.visibility = if (item.bodyPreview.isBlank()) View.GONE else View.VISIBLE

            val tagText = item.tags.joinToString(separator = "  ") { "#${it.name}" }
            tags.text = tagText
            tags.visibility = if (tagText.isBlank()) View.GONE else View.VISIBLE
            if (tagText.isNotBlank()) {
                tags.contentDescription = context.getString(R.string.tags_content_description, tagText)
            } else {
                tags.contentDescription = null
            }

            val updatedDate = Instant.ofEpochMilli(item.updatedAtEpochMillis)
                .atZone(zoneId)
                .toLocalDate()
                .format(dateFormatter)
            updatedAt.text = context.getString(R.string.updated_time, updatedDate)

            if (row.section == VaultSection.ACTIVE) {
                pinButton.setImageResource(R.drawable.ic_pin)
                pinButton.isSelected = item.isPinned
                pinButton.contentDescription = context.getString(
                    if (item.isPinned) R.string.unpin_note else R.string.pin_note,
                )
                ViewCompat.setStateDescription(
                    pinButton,
                    context.getString(
                        if (item.isPinned) R.string.note_pinned else R.string.note_not_pinned,
                    ),
                )
                pinButton.setOnClickListener { onPinnedChanged(item.id, !item.isPinned) }

                favoriteButton.isVisible = true
                favoriteButton.isSelected = item.isFavorite
                favoriteButton.contentDescription = context.getString(
                    if (item.isFavorite) R.string.unfavorite_note else R.string.favorite_note,
                )
                ViewCompat.setStateDescription(
                    favoriteButton,
                    context.getString(
                        if (item.isFavorite) R.string.note_favorite else R.string.note_not_favorite,
                    ),
                )
                favoriteButton.setOnClickListener { onFavoriteChanged(item.id, !item.isFavorite) }
            } else {
                pinButton.setImageResource(R.drawable.ic_restore)
                pinButton.isSelected = false
                pinButton.contentDescription = context.getString(R.string.restore_note)
                ViewCompat.setStateDescription(pinButton, null)
                pinButton.setOnClickListener { onRestore(item.id, row.section) }
                favoriteButton.isVisible = false
                favoriteButton.setOnClickListener(null)
            }

            val canOpen = row.section != VaultSection.TRASH
            root.isClickable = canOpen
            root.isFocusable = canOpen
            root.setOnClickListener(if (canOpen) View.OnClickListener { onOpen(item.id) } else null)
        }
    }

    private companion object {
        val DiffCallback = object : DiffUtil.ItemCallback<VaultListItem>() {
            override fun areItemsTheSame(
                oldItem: VaultListItem,
                newItem: VaultListItem,
            ): Boolean = oldItem.note.id == newItem.note.id

            override fun areContentsTheSame(
                oldItem: VaultListItem,
                newItem: VaultListItem,
            ): Boolean = oldItem.section == newItem.section &&
                oldItem.note.title == newItem.note.title &&
                oldItem.note.bodyPreview == newItem.note.bodyPreview &&
                oldItem.note.color == newItem.note.color &&
                oldItem.note.isPinned == newItem.note.isPinned &&
                oldItem.note.isFavorite == newItem.note.isFavorite &&
                oldItem.note.updatedAtEpochMillis == newItem.note.updatedAtEpochMillis &&
                tagNamesAreEqual(oldItem, newItem)

            private fun tagNamesAreEqual(
                oldItem: VaultListItem,
                newItem: VaultListItem,
            ): Boolean {
                val oldTags = oldItem.note.tags
                val newTags = newItem.note.tags
                if (oldTags.size != newTags.size) return false
                for (index in oldTags.indices) {
                    if (oldTags[index].name != newTags[index].name) return false
                }
                return true
            }
        }

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
