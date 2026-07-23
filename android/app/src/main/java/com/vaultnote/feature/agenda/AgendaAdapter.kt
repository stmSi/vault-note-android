package com.vaultnote.feature.agenda

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vaultnote.R
import com.vaultnote.core.common.model.AgendaEntry
import com.vaultnote.core.common.model.DatedEntryType
import com.vaultnote.databinding.ItemAgendaEntryBinding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal class AgendaAdapter(
    private val onOpen: (AgendaEntry) -> Unit,
    private val onExport: (AgendaEntry) -> Unit,
) : ListAdapter<AgendaEntry, AgendaAdapter.Holder>(DiffCallback) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        ItemAgendaEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(
        private val binding: ItemAgendaEntryBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: AgendaEntry) = with(binding) {
            val context = root.context
            val dateTime = Instant.ofEpochMilli(row.entry.occurrenceAtEpochMillis)
                .atZone(ZoneId.of(row.entry.timeZoneId))
            date.text = if (row.entry.isAllDay) {
                dateTime.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
            } else {
                dateTime.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))
            }
            type.setText(
                when (row.entry.type) {
                    DatedEntryType.REMINDER -> R.string.date_type_reminder
                    DatedEntryType.DEADLINE -> R.string.date_type_deadline
                    DatedEntryType.IMPORTANT_DATE -> R.string.date_type_important
                    DatedEntryType.RENEWAL -> R.string.date_type_renewal
                },
            )
            label.text = row.entry.label.ifBlank { type.text }
            noteTitle.text = row.noteTitle.ifBlank { context.getString(R.string.untitled_note) }
            root.alpha = if (row.entry.completedAtEpochMillis == null) 1f else 0.55f
            root.setOnClickListener { onOpen(row) }
            exportButton.setOnClickListener { onExport(row) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<AgendaEntry>() {
        override fun areItemsTheSame(oldItem: AgendaEntry, newItem: AgendaEntry): Boolean =
            oldItem.entry.id == newItem.entry.id

        override fun areContentsTheSame(oldItem: AgendaEntry, newItem: AgendaEntry): Boolean =
            oldItem == newItem
    }
}
