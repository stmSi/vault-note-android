<script lang="ts">
  import type { AgendaEntry } from './lib/models';
  import { partsInTimeZone } from './lib/dates';

  export let entries: AgendaEntry[];
  export let includeCompleted: boolean;
  export let selectedDate = '';
  export let busy = false;
  export let onClose: () => void;
  export let onIncludeCompleted: (value: boolean) => void;
  export let onSelectedDate: (value: string) => void;
  export let onOpen: (itemId: string) => void;
  export let onEdit: (row: AgendaEntry) => void;
  export let onComplete: (id: string) => void;
  export let onSnooze: (id: string) => void;
  export let onExport: (id: string) => void;

  $: visibleEntries = selectedDate
    ? entries.filter(
        (row) =>
          partsInTimeZone(
            row.entry.occurrenceAtEpochMillis,
            row.entry.timeZoneId,
          ).date === selectedDate,
      )
    : entries;

  function formattedOccurrence(row: AgendaEntry): string {
    return new Intl.DateTimeFormat(undefined, {
      dateStyle: 'medium',
      ...(row.entry.isAllDay ? {} : { timeStyle: 'short' as const }),
      timeZone: row.entry.timeZoneId,
    }).format(new Date(row.entry.occurrenceAtEpochMillis));
  }

  function typeLabel(type: AgendaEntry['entry']['type']): string {
    return {
      REMINDER: 'Reminder',
      DEADLINE: 'Deadline',
      IMPORTANT_DATE: 'Important date',
      RENEWAL: 'Renewal',
    }[type];
  }
</script>

<section class="agenda-panel" aria-label="Calendar and agenda">
  <header>
    <div>
      <p>Dates across your vault</p>
      <h2>Calendar &amp; agenda</h2>
    </div>
    <button aria-label="Close calendar" onclick={onClose}>×</button>
  </header>

  <div class="calendar-controls">
    <label>
      <span>Filter by date</span>
      <input
        type="date"
        value={selectedDate}
        onchange={(event) => onSelectedDate(event.currentTarget.value)}
      />
    </label>
    <button disabled={!selectedDate} onclick={() => onSelectedDate('')}>Show all</button>
    <label class="completed-toggle">
      <input
        type="checkbox"
        checked={includeCompleted}
        onchange={(event) => onIncludeCompleted(event.currentTarget.checked)}
      />
      <span>Include completed</span>
    </label>
  </div>

  <div class="agenda-list">
    {#if visibleEntries.length === 0}
      <div class="agenda-empty">
        <strong>No dates here</strong>
        <span>Add reminders, deadlines, important dates, or renewals from a note.</span>
      </div>
    {:else}
      {#each visibleEntries as row (row.entry.id)}
        <article
          class:completed={row.entry.completedAtEpochMillis !== null}
          class:overdue={row.entry.type === 'DEADLINE' &&
            row.entry.completedAtEpochMillis === null &&
            row.entry.occurrenceAtEpochMillis < Date.now()}
        >
          <button class="agenda-main" onclick={() => onOpen(row.entry.itemId)}>
            <span class="date-type">{typeLabel(row.entry.type)}</span>
            <strong>{row.entry.label || typeLabel(row.entry.type)}</strong>
            <span>{formattedOccurrence(row)}</span>
            <small>{row.noteTitle || 'Untitled note'}{row.isArchived ? ' · archived' : ''}</small>
          </button>
          <div class="agenda-actions">
            <button disabled={busy} onclick={() => onEdit(row)}>Edit</button>
            {#if row.entry.alerts.length > 0 && row.entry.completedAtEpochMillis === null}
              <button disabled={busy} onclick={() => onSnooze(row.entry.id)}>Snooze 10m</button>
            {/if}
            {#if row.entry.type !== 'IMPORTANT_DATE' && row.entry.completedAtEpochMillis === null}
              <button disabled={busy} onclick={() => onComplete(row.entry.id)}>Done</button>
            {/if}
            <button disabled={busy} onclick={() => onExport(row.entry.id)}>Export .ics</button>
          </div>
        </article>
      {/each}
    {/if}
  </div>
</section>

<style>
  .agenda-panel {
    position: absolute;
    inset: 0;
    z-index: 12;
    display: grid;
    grid-template-rows: auto auto minmax(0, 1fr);
    color: var(--vn-text);
    background: var(--vn-surface-subtle);
  }

  header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 16px 20px;
    color: var(--vn-on-accent);
    background: var(--vn-accent-header);
  }

  header p,
  header h2 {
    margin: 0;
  }

  header p {
    font-size: 11px;
    color: var(--vn-on-accent-muted);
  }

  header h2 {
    font-size: 19px;
  }

  header button {
    width: 36px;
    height: 36px;
    font-size: 24px;
    color: var(--vn-on-accent);
    background: transparent;
    border-color: var(--vn-accent);
  }

  .calendar-controls {
    display: flex;
    align-items: end;
    gap: 10px;
    padding: 12px 20px;
    background: var(--vn-accent-soft);
    border-bottom: 1px solid var(--vn-border);
  }

  .calendar-controls label:not(.completed-toggle) {
    display: grid;
    gap: 3px;
    font-size: 11px;
    font-weight: 700;
  }

  .calendar-controls input[type='date'],
  .calendar-controls button {
    min-height: 36px;
    padding: 6px 10px;
    color: var(--vn-text);
    background: var(--vn-control-background);
    border: 1px solid var(--vn-border-strong);
    border-radius: 8px;
  }

  .completed-toggle {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-left: auto;
    padding-bottom: 8px;
    font-size: 12px;
  }

  .completed-toggle input {
    accent-color: var(--vn-accent);
  }

  .agenda-list {
    min-height: 0;
    padding: 14px 20px 28px;
    overflow: auto;
  }

  article {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 12px;
    margin-bottom: 10px;
    background: var(--vn-surface);
    border: 1px solid var(--vn-border);
    border-left: 4px solid var(--vn-accent);
    border-radius: 10px;
  }

  article.overdue {
    border-left-color: var(--vn-danger);
  }

  article.completed {
    opacity: 0.58;
  }

  .agenda-main {
    display: grid;
    gap: 2px;
    padding: 11px 13px;
    text-align: left;
    background: transparent;
    border: 0;
  }

  .agenda-main span,
  .agenda-main small {
    color: var(--vn-text-muted);
  }

  .date-type {
    font-size: 10px;
    font-weight: 800;
    letter-spacing: 0.07em;
    text-transform: uppercase;
  }

  .agenda-actions {
    display: flex;
    align-items: center;
    gap: 5px;
    padding: 8px 10px 8px 0;
  }

  .agenda-actions button {
    padding: 5px 8px;
    font-size: 11px;
  }

  .agenda-empty {
    display: grid;
    gap: 5px;
    place-content: center;
    min-height: 220px;
    color: var(--vn-text-muted);
    text-align: center;
  }

  @media (max-width: 760px) {
    .calendar-controls,
    .agenda-actions {
      flex-wrap: wrap;
    }

    article {
      grid-template-columns: 1fr;
    }

    .agenda-actions {
      padding: 0 10px 10px;
    }
  }
</style>
