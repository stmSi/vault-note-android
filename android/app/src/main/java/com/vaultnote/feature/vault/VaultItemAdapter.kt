package com.vaultnote.feature.vault

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
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
    private val onOpen: (VaultListItem) -> Unit,
    private val onPinnedChanged: (itemId: String, pinned: Boolean) -> Unit,
    private val onFavoriteChanged: (itemId: String, favorite: Boolean) -> Unit,
    private val onRestore: (itemId: String, source: VaultSection) -> Unit,
    private val onReorder: (itemId: String, previousItemId: String?, nextItemId: String?) -> Unit,
) : ListAdapter<VaultListItem, VaultItemAdapter.VaultItemViewHolder>(DiffCallback) {
    private val dateFormatter = DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
    private val zoneId = ZoneId.systemDefault()
    private var committedItems = mutableListOf<VaultListItem>()
    private var originalDragItems: List<VaultListItem>? = null
    private var draggedItemId: String? = null
    private var dragActive = false
    private var awaitingReorderResult = false
    private var listUpdateInFlight = false
    private var deferredSubmission: DeferredSubmission? = null
    var dragStarter: ((VaultItemViewHolder) -> Unit)? = null

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

    fun submitVaultList(
        items: List<VaultListItem>,
        commitCallback: (() -> Unit)? = null,
    ) {
        if (dragActive || awaitingReorderResult) {
            deferredSubmission = DeferredSubmission(items, commitCallback)
            return
        }
        submitNow(items, commitCallback)
    }

    inner class VaultItemViewHolder(
        private val binding: ItemVaultNoteBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var moveEarlierActionId = View.NO_ID
        private var moveLaterActionId = View.NO_ID

        @SuppressLint("ClickableViewAccessibility")
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
                dragHandle.isVisible = true
                dragHandle.setOnClickListener {}
                dragHandle.setOnTouchListener { handle, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> requestDrag(this@VaultItemViewHolder)
                        MotionEvent.ACTION_UP -> handle.performClick()
                    }
                    true
                }
                bindReorderAccessibilityActions()
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
                dragHandle.isVisible = false
                dragHandle.setOnClickListener(null)
                clearReorderAccessibilityActions()
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
            root.setOnClickListener(if (canOpen) View.OnClickListener { onOpen(row) } else null)
        }

        private fun bindReorderAccessibilityActions() {
            clearReorderAccessibilityActions()
            val position = bindingAdapterPosition
            if (canMove(position, position - 1)) {
                moveEarlierActionId = ViewCompat.addAccessibilityAction(
                    binding.dragHandle,
                    binding.root.context.getString(R.string.move_item_earlier),
                ) { _, _ ->
                    moveByOne(this, -1)
                }
            }
            if (canMove(position, position + 1)) {
                moveLaterActionId = ViewCompat.addAccessibilityAction(
                    binding.dragHandle,
                    binding.root.context.getString(R.string.move_item_later),
                ) { _, _ ->
                    moveByOne(this, 1)
                }
            }
        }

        private fun clearReorderAccessibilityActions() {
            if (moveEarlierActionId != View.NO_ID) {
                ViewCompat.removeAccessibilityAction(binding.dragHandle, moveEarlierActionId)
                moveEarlierActionId = View.NO_ID
            }
            if (moveLaterActionId != View.NO_ID) {
                ViewCompat.removeAccessibilityAction(binding.dragHandle, moveLaterActionId)
                moveLaterActionId = View.NO_ID
            }
        }
    }

    fun itemAt(position: Int): VaultListItem? =
        currentList.getOrNull(position)

    fun canMove(fromPosition: Int, toPosition: Int): Boolean {
        val from = itemAt(fromPosition) ?: return false
        val to = itemAt(toPosition) ?: return false
        return from.section == VaultSection.ACTIVE &&
            to.section == VaultSection.ACTIVE &&
            from.note.isPinned == to.note.isPinned &&
            !awaitingReorderResult
    }

    fun moveItem(fromPosition: Int, toPosition: Int): Boolean {
        if (!dragActive || !canMove(fromPosition, toPosition)) return false
        if (fromPosition == toPosition) return true
        val moved = committedItems.removeAt(fromPosition)
        committedItems.add(toPosition, moved)
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    fun finishDrag() {
        if (!dragActive) return
        dragActive = false
        val itemId = draggedItemId
        val originalIds = originalDragItems?.map { row -> row.note.id }
        val currentIds = committedItems.map { row -> row.note.id }
        if (itemId == null || originalIds == currentIds) {
            clearDragState()
            applyDeferredSubmission()
            return
        }
        val index = committedItems.indexOfFirst { row -> row.note.id == itemId }
        val moved = committedItems.getOrNull(index)
        if (moved == null) {
            rollbackReorder()
            return
        }
        val previousId = committedItems
            .subList(0, index)
            .lastOrNull { row -> row.note.isPinned == moved.note.isPinned }
            ?.note
            ?.id
        val nextId = committedItems
            .subList(index + 1, committedItems.size)
            .firstOrNull { row -> row.note.isPinned == moved.note.isPinned }
            ?.note
            ?.id
        awaitingReorderResult = true
        onReorder(itemId, previousId, nextId)
    }

    fun confirmReorder() {
        if (!awaitingReorderResult) return
        awaitingReorderResult = false
        originalDragItems = null
        draggedItemId = null
        val deferred = deferredSubmission
        deferredSubmission = null
        if (
            deferred != null &&
            deferred.items.map { row -> row.note.id } ==
            committedItems.map { row -> row.note.id }
        ) {
            submitNow(deferred.items, deferred.commitCallback)
        }
    }

    fun rollbackReorder() {
        val original = originalDragItems
        if (original != null) {
            original.forEachIndexed { targetIndex, row ->
                val currentIndex = committedItems.indexOfFirst { current ->
                    current.note.id == row.note.id
                }
                if (currentIndex >= 0 && currentIndex != targetIndex) {
                    val moved = committedItems.removeAt(currentIndex)
                    committedItems.add(targetIndex, moved)
                    notifyItemMoved(currentIndex, targetIndex)
                }
            }
        }
        awaitingReorderResult = false
        clearDragState()
        applyDeferredSubmission()
    }

    fun resetItem(itemId: String) {
        val position = currentList.indexOfFirst { row -> row.note.id == itemId }
        if (position >= 0) notifyItemChanged(position)
    }

    private fun requestDrag(holder: VaultItemViewHolder) {
        val starter = dragStarter ?: return
        if (!beginDrag(holder)) return
        starter(holder)
    }

    private fun moveByOne(holder: VaultItemViewHolder, offset: Int): Boolean {
        val fromPosition = holder.bindingAdapterPosition
        val toPosition = fromPosition + offset
        if (!canMove(fromPosition, toPosition) || !beginDrag(holder)) return false
        if (!moveItem(fromPosition, toPosition)) {
            rollbackReorder()
            return false
        }
        finishDrag()
        return true
    }

    private fun beginDrag(holder: VaultItemViewHolder): Boolean {
        val position = holder.bindingAdapterPosition
        val row = itemAt(position) ?: return false
        if (
            row.section != VaultSection.ACTIVE ||
            dragActive ||
            awaitingReorderResult ||
            listUpdateInFlight ||
            committedItems.size != currentList.size
        ) {
            return false
        }
        originalDragItems = committedItems.toList()
        draggedItemId = row.note.id
        dragActive = true
        return true
    }

    private fun submitNow(items: List<VaultListItem>, commitCallback: (() -> Unit)?) {
        val mutableItems = items.toMutableList()
        listUpdateInFlight = true
        super.submitList(mutableItems) {
            committedItems = mutableItems
            listUpdateInFlight = false
            commitCallback?.invoke()
        }
    }

    private fun applyDeferredSubmission() {
        val deferred = deferredSubmission ?: return
        deferredSubmission = null
        submitNow(deferred.items, deferred.commitCallback)
    }

    private fun clearDragState() {
        dragActive = false
        originalDragItems = null
        draggedItemId = null
    }

    private data class DeferredSubmission(
        val items: List<VaultListItem>,
        val commitCallback: (() -> Unit)?,
    )

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
