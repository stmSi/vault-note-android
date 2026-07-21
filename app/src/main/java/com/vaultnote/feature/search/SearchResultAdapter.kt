package com.vaultnote.feature.search

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.vaultnote.R
import com.vaultnote.core.search.RoomSearchRepository
import com.vaultnote.core.search.VaultSearchResult
import com.vaultnote.databinding.ItemSearchResultBinding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

internal class SearchResultAdapter(
    private val onOpen: (String) -> Unit,
) : ListAdapter<VaultSearchResult, SearchResultAdapter.ResultViewHolder>(DiffCallback) {
    private val dateFormatter = DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
    private val zoneId = ZoneId.systemDefault()

    init {
        setHasStableIds(true)
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun getItemId(position: Int): Long = stableLongId(getItem(position).itemId)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder =
        ResultViewHolder(
            ItemSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ResultViewHolder(
        private val binding: ItemSearchResultBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(result: VaultSearchResult) = with(binding) {
            val context = root.context
            val highlightBackground = MaterialColors.getColor(
                root,
                com.google.android.material.R.attr.colorSecondaryContainer,
            )
            val highlightForeground = MaterialColors.getColor(
                root,
                com.google.android.material.R.attr.colorOnSecondaryContainer,
            )
            title.text = highlighted(
                result.highlightedTitle.ifBlank { result.title },
                highlightBackground,
                highlightForeground,
            ).takeIf { it.isNotBlank() } ?: context.getString(R.string.untitled_note)
            snippet.text = highlighted(
                result.highlightedSnippet,
                highlightBackground,
                highlightForeground,
            )
            snippet.isVisible = snippet.text.isNotBlank()
            archivedBadge.isVisible = result.isArchived
            val date = Instant.ofEpochMilli(result.updatedAtEpochMillis)
                .atZone(zoneId)
                .toLocalDate()
                .format(dateFormatter)
            updatedAt.text = context.getString(R.string.updated_time, date)
            root.setOnClickListener { onOpen(result.itemId) }
        }
    }

    private fun highlighted(
        source: String,
        backgroundColor: Int,
        foregroundColor: Int,
    ): CharSequence {
        val builder = SpannableStringBuilder(source)
        while (true) {
            val start = builder.indexOf(RoomSearchRepository.HIGHLIGHT_START)
            if (start < 0) break
            builder.delete(start, start + RoomSearchRepository.HIGHLIGHT_START.length)
            val end = builder.indexOf(RoomSearchRepository.HIGHLIGHT_END, start)
            if (end < 0) break
            builder.delete(end, end + RoomSearchRepository.HIGHLIGHT_END.length)
            if (end > start) {
                builder.setSpan(
                    BackgroundColorSpan(backgroundColor),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                builder.setSpan(
                    ForegroundColorSpan(foregroundColor),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                builder.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        removeMarker(builder, RoomSearchRepository.HIGHLIGHT_START)
        removeMarker(builder, RoomSearchRepository.HIGHLIGHT_END)
        return builder
    }

    private fun removeMarker(builder: SpannableStringBuilder, marker: String) {
        while (true) {
            val index = builder.indexOf(marker)
            if (index < 0) return
            builder.delete(index, index + marker.length)
        }
    }

    private companion object {
        val DiffCallback = object : DiffUtil.ItemCallback<VaultSearchResult>() {
            override fun areItemsTheSame(oldItem: VaultSearchResult, newItem: VaultSearchResult): Boolean =
                oldItem.itemId == newItem.itemId

            override fun areContentsTheSame(
                oldItem: VaultSearchResult,
                newItem: VaultSearchResult,
            ): Boolean = oldItem == newItem
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
