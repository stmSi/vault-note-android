<script lang="ts">
  import type { DatedEntry } from './lib/models';

  export let entries: DatedEntry[];
  export let busy = false;
  export let onAdd: () => void;
  export let onEdit: (entry: DatedEntry) => void;
  export let onComplete: (id: string) => void;
  export let onSnooze: (id: string) => void;
  export let onExport: (id: string) => void;
  export let onClose: () => void;

  function formatted(entry: DatedEntry): string {
    return new Intl.DateTimeFormat(undefined, {
      dateStyle: 'medium',
      ...(entry.isAllDay ? {} : { timeStyle: 'short' as const }),
      timeZone: entry.timeZoneId,
    }).format(new Date(entry.occurrenceAtEpochMillis));
  }

  function typeLabel(type: DatedEntry['type']): string {
    return {
      REMINDER: 'Reminder',
      DEADLINE: 'Deadline',
      IMPORTANT_DATE: 'Important date',
      RENEWAL: 'Renewal',
    }[type];
  }
</script>

<section class="date-panel" aria-label="Note dates">
  <header>
    <div>
      <strong>Dates &amp; alerts</strong>
      <small>{entries.length} {entries.length === 1 ? 'entry' : 'entries'}</small>
    </div>
    <button class="add-button" onclick={onAdd}>+ Add date</button>
    <button class="close-button" aria-label="Close dates" onclick={onClose}>×</button>
  </header>

  <div class="date-list">
    {#if entries.length === 0}
      <p>No dates yet. Add a reminder, deadline, important date, or renewal.</p>
    {:else}
      {#each entries as entry (entry.id)}
        <article class:completed={entry.completedAtEpochMillis !== null}>
          <button class="date-main" onclick={() => onEdit(entry)}>
            <span>{typeLabel(entry.type)}</span>
            <strong>{entry.label || typeLabel(entry.type)}</strong>
            <small>
              {formatted(entry)}
              {entry.recurrenceUnit ? ` · repeats ${entry.recurrenceUnit.toLowerCase()}` : ''}
              {entry.alerts.length > 0 ? ` · ${entry.alerts.length} alert${entry.alerts.length === 1 ? '' : 's'}` : ''}
            </small>
          </button>
          <div class="date-actions">
            {#if entry.alerts.length > 0 && entry.completedAtEpochMillis === null}
              <button disabled={busy} onclick={() => onSnooze(entry.id)}>Snooze 10m</button>
            {/if}
            {#if entry.type !== 'IMPORTANT_DATE' && entry.completedAtEpochMillis === null}
              <button disabled={busy} onclick={() => onComplete(entry.id)}>Done</button>
            {/if}
            <button disabled={busy} onclick={() => onExport(entry.id)}>.ics</button>
          </div>
        </article>
      {/each}
    {/if}
  </div>
</section>

<style>
  .date-panel {
    position: absolute;
    right: 18px;
    bottom: 58px;
    z-index: 8;
    display: grid;
    grid-template-rows: auto minmax(0, 1fr);
    width: min(620px, calc(100% - 36px));
    max-height: min(430px, calc(100% - 100px));
    color: var(--vn-text);
    background: var(--vn-surface);
    border: 1px solid var(--vn-border);
    border-radius: 12px;
    box-shadow: var(--vn-panel-shadow);
  }

  header {
    display: grid;
    grid-template-columns: 1fr auto auto;
    align-items: center;
    gap: 8px;
    padding: 10px 12px;
    background: var(--vn-surface-muted);
    border-bottom: 1px solid var(--vn-border);
    border-radius: 12px 12px 0 0;
  }

  header div {
    display: grid;
  }

  header small {
    color: var(--vn-text-muted);
  }

  header button {
    min-height: 32px;
  }

  .add-button {
    padding: 5px 9px;
    color: var(--vn-control-text);
    border-color: var(--vn-border-strong);
  }

  .close-button {
    width: 32px;
    font-size: 20px;
    background: transparent;
    border: 0;
  }

  .date-list {
    min-height: 0;
    padding: 10px;
    overflow: auto;
  }

  .date-list > p {
    padding: 18px;
    color: var(--vn-text-muted);
    text-align: center;
  }

  article {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    margin-bottom: 7px;
    border: 1px solid var(--vn-border);
    border-left: 3px solid var(--vn-accent);
    border-radius: 8px;
  }

  article.completed {
    opacity: 0.55;
  }

  .date-main {
    display: grid;
    gap: 1px;
    padding: 8px 10px;
    text-align: left;
    background: transparent;
    border: 0;
  }

  .date-main span {
    font-size: 9px;
    font-weight: 800;
    color: var(--vn-control-text);
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }

  .date-main small {
    color: var(--vn-text-muted);
  }

  .date-actions {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 6px 8px;
  }

  .date-actions button {
    padding: 4px 7px;
    font-size: 10px;
  }
</style>
