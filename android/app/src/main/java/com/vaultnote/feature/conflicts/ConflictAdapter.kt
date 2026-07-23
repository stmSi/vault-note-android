package com.vaultnote.feature.conflicts

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vaultnote.R
import com.vaultnote.core.common.model.VaultItemSummary
import com.vaultnote.core.common.toStyle
import com.vaultnote.databinding.ItemConflictBinding
import java.util.UUID

internal class ConflictAdapter(
    private val onKeep: (String) -> Unit,
) : ListAdapter<VaultItemSummary, ConflictAdapter.Holder>(DiffCallback) {
    init {
        setHasStableIds(true)
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun getItemId(position: Int): Long = stableLongId(getItem(position).id)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemConflictBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemConflictBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: VaultItemSummary) = with(binding) {
            val context = root.context
            val colors = item.color.toStyle()
            root.setCardBackgroundColor(ContextCompat.getColor(context, colors.surfaceColor))
            title.setTextColor(ContextCompat.getColor(context, colors.titleColor))
            title.text = item.title.ifBlank { context.getString(R.string.untitled_note) }
            preview.text = item.bodyPreview
            versionLabel.setText(
                if (item.conflictOriginId == null) {
                    R.string.conflict_local_version
                } else {
                    R.string.conflict_remote_version
                },
            )
            keepButton.setOnClickListener { onKeep(item.id) }
        }
    }

    private companion object {
        val DiffCallback = object : DiffUtil.ItemCallback<VaultItemSummary>() {
            override fun areItemsTheSame(old: VaultItemSummary, new: VaultItemSummary): Boolean =
                old.id == new.id

            override fun areContentsTheSame(old: VaultItemSummary, new: VaultItemSummary): Boolean =
                old == new
        }

        fun stableLongId(id: String): Long {
            val uuid = runCatching { UUID.fromString(id) }.getOrNull()
            val value = if (uuid != null) {
                uuid.mostSignificantBits xor uuid.leastSignificantBits
            } else {
                id.fold(FNV_OFFSET_BASIS) { hash, character ->
                    (hash xor character.code.toLong()) * FNV_PRIME
                }
            }
            return if (value == RecyclerView.NO_ID) Long.MIN_VALUE else value
        }

        const val FNV_OFFSET_BASIS: Long = -3750763034362895579L
        const val FNV_PRIME: Long = 1099511628211L
    }
}
