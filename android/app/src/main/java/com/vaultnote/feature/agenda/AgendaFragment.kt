package com.vaultnote.feature.agenda

import android.content.Intent
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.provider.CalendarContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vaultnote.R
import com.vaultnote.app.MainNavigator
import com.vaultnote.app.appContainer
import com.vaultnote.core.common.model.AgendaEntry
import com.vaultnote.databinding.FragmentAgendaBinding
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.launch

class AgendaFragment : Fragment() {
    private var binding: FragmentAgendaBinding? = null
    private val viewModel: AgendaViewModel by viewModels {
        AgendaViewModel.Factory(requireContext().appContainer().vaultRepository)
    }
    private val adapter = AgendaAdapter(
        onOpen = { row -> (activity as? MainNavigator)?.openNoteEditor(row.entry.itemId) },
        onExport = ::confirmCalendarExport,
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FragmentAgendaBinding.inflate(inflater, container, false).also {
        binding = it
    }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val current = requireNotNull(binding)
        current.toolbar.setNavigationOnClickListener {
            (activity as? MainNavigator)?.navigateBack()
        }
        current.agendaList.layoutManager = LinearLayoutManager(requireContext())
        current.agendaList.adapter = adapter
        current.calendar.setOnDateChangeListener { _, year, month, day ->
            viewModel.selectDate(LocalDate.of(year, month + 1, day))
        }
        current.showAllButton.setOnClickListener { viewModel.selectDate(null) }
        current.includeCompleted.setOnCheckedChangeListener { _, checked ->
            viewModel.setIncludeCompleted(checked)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(current, state) }
            }
        }
    }

    override fun onDestroyView() {
        binding?.agendaList?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private fun render(current: FragmentAgendaBinding, state: AgendaUiState) {
        current.loadingIndicator.isVisible = state is AgendaUiState.Loading
        val content = state as? AgendaUiState.Content
        adapter.submitList(content?.entries.orEmpty())
        current.emptyMessage.isVisible =
            state is AgendaUiState.Error || (content != null && content.entries.isEmpty())
        if (content != null && current.includeCompleted.isChecked != content.includeCompleted) {
            current.includeCompleted.isChecked = content.includeCompleted
        }
        current.showAllButton.isEnabled = content?.selectedDate != null
    }

    private fun confirmCalendarExport(row: AgendaEntry) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.calendar_export_disclosure_title)
            .setMessage(R.string.calendar_export_disclosure)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.calendar_export_continue) { _, _ ->
                launchCalendarInsert(row)
            }
            .show()
    }

    private fun launchCalendarInsert(row: AgendaEntry) {
        val entry = row.entry
        val title = entry.label.ifBlank { row.noteTitle.ifBlank { getString(R.string.app_name) } }
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
        if (entry.isAllDay) {
            val localDate = Instant.ofEpochMilli(entry.occurrenceAtEpochMillis)
                .atZone(ZoneId.of(entry.timeZoneId))
                .toLocalDate()
            val start = localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                .putExtra(
                    CalendarContract.EXTRA_EVENT_END_TIME,
                    localDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                )
                .putExtra(CalendarContract.Events.ALL_DAY, true)
        } else {
            intent.putExtra(
                CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                entry.occurrenceAtEpochMillis,
            ).putExtra(
                CalendarContract.EXTRA_EVENT_END_TIME,
                entry.occurrenceAtEpochMillis + ONE_HOUR_MILLIS,
            )
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Calendar export is optional; keep the entry safely inside VaultNote.
        }
    }

    companion object {
        const val BACK_STACK_NAME = "agenda"
        private const val ONE_HOUR_MILLIS = 60L * 60L * 1_000L
        fun newInstance(): AgendaFragment = AgendaFragment()
    }
}
