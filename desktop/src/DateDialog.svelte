<script lang="ts">
  import type {
    DatedEntry,
    DatedEntryDraft,
    DatedEntryType,
    RecurrenceUnit,
  } from './lib/models';
  import { partsInTimeZone, zonedLocalToEpochMillis } from './lib/dates';

  export let entry: DatedEntry | null = null;
  export let onSave: (draft: DatedEntryDraft) => void;
  export let onDelete: (id: string) => void;
  export let onClose: () => void;

  const systemTimeZone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  const timeZone = entry?.timeZoneId || systemTimeZone;
  const initialEpoch = entry?.occurrenceAtEpochMillis ?? Date.now() + 24 * 60 * 60 * 1000;
  const initialParts = partsInTimeZone(initialEpoch, timeZone);
  let type: DatedEntryType = entry?.type ?? 'REMINDER';
  let label = entry?.label ?? '';
  let date = initialParts.date;
  let time = entry?.isAllDay ? '09:00' : initialParts.time;
  let allDay = entry?.isAllDay ?? false;
  let recurrenceUnit: RecurrenceUnit | '' = entry?.recurrenceUnit ?? '';
  let recurrenceInterval = entry?.recurrenceInterval ?? 1;
  let alertLeads = new Set(entry?.alerts.map((alert) => alert.leadTimeMinutes) ?? [0]);

  const alertOptions = [
    [0, 'At time'],
    [10, '10 minutes before'],
    [60, '1 hour before'],
    [1440, '1 day before'],
    [10080, '1 week before'],
  ] as const;

  function changeType(value: DatedEntryType): void {
    type = value;
    if (type === 'RENEWAL' && recurrenceUnit === '') {
      recurrenceUnit = 'YEAR';
      recurrenceInterval = 1;
    }
  }

  function toggleAlert(lead: number, checked: boolean): void {
    const next = new Set(alertLeads);
    if (checked) next.add(lead);
    else next.delete(lead);
    alertLeads = next;
  }

  function submit(): void {
    if (!date) return;
    onSave({
      id: entry?.id ?? null,
      type,
      label,
      occurrenceAtEpochMillis: zonedLocalToEpochMillis(
        date,
        allDay ? '09:00' : time || '09:00',
        timeZone,
      ),
      isAllDay: allDay,
      timeZoneId: timeZone,
      recurrenceUnit: recurrenceUnit || null,
      recurrenceInterval: recurrenceUnit ? recurrenceInterval : null,
      alertLeadTimesMinutes: [...alertLeads].sort((left, right) => left - right),
    });
  }
</script>

<div class="dialog-backdrop" role="presentation" onclick={(event) => {
  if (event.currentTarget === event.target) onClose();
}}>
  <div class="date-dialog" role="dialog" aria-modal="true" aria-labelledby="date-dialog-title">
    <header>
      <div>
        <p class="eyebrow">Calendar entry</p>
        <h2 id="date-dialog-title">{entry ? 'Edit date' : 'Add date'}</h2>
      </div>
      <button class="icon-button" aria-label="Close" onclick={onClose}>×</button>
    </header>

    <div class="date-form">
      <label>
        <span>Type</span>
        <select value={type} onchange={(event) => changeType(event.currentTarget.value as DatedEntryType)}>
          <option value="REMINDER">Reminder</option>
          <option value="DEADLINE">Deadline</option>
          <option value="IMPORTANT_DATE">Important date</option>
          <option value="RENEWAL">Renewal</option>
        </select>
      </label>

      <label>
        <span>Label</span>
        <input maxlength="500" placeholder="Optional private label" bind:value={label} />
      </label>

      <div class="date-time-row">
        <label>
          <span>Date</span>
          <input type="date" required bind:value={date} />
        </label>
        <label class:disabled={allDay}>
          <span>Time</span>
          <input type="time" disabled={allDay} bind:value={time} />
        </label>
        <label class="check-label">
          <input type="checkbox" bind:checked={allDay} />
          <span>All day</span>
        </label>
      </div>

      <div class="date-time-row">
        <label>
          <span>Repeat</span>
          <select bind:value={recurrenceUnit}>
            <option value="">Does not repeat</option>
            <option value="DAY">Daily</option>
            <option value="WEEK">Weekly</option>
            <option value="MONTH">Monthly</option>
            <option value="YEAR">Yearly</option>
          </select>
        </label>
        {#if recurrenceUnit}
          <label>
            <span>Every</span>
            <input type="number" min="1" max="999" bind:value={recurrenceInterval} />
          </label>
        {/if}
      </div>

      <fieldset>
        <legend>Alerts</legend>
        <div class="alert-options">
          {#each alertOptions as option}
            <label class="check-label">
              <input
                type="checkbox"
                checked={alertLeads.has(option[0])}
                onchange={(event) => toggleAlert(option[0], event.currentTarget.checked)}
              />
              <span>{option[1]}</span>
            </label>
          {/each}
        </div>
        <small>Notifications are private and never include the note title or date label.</small>
      </fieldset>
    </div>

    <footer>
      {#if entry}
        <button class="danger-button" onclick={() => onDelete(entry!.id)}>Delete</button>
      {/if}
      <span></span>
      <button onclick={onClose}>Cancel</button>
      <button class="primary-button" disabled={!date} onclick={submit}>Save date</button>
    </footer>
  </div>
</div>

<style>
  .dialog-backdrop {
    position: fixed;
    inset: 0;
    z-index: 80;
    display: grid;
    place-items: center;
    padding: 24px;
    background: var(--vn-overlay);
  }

  .date-dialog {
    width: min(100%, 620px);
    max-height: calc(100vh - 48px);
    overflow: auto;
    color: var(--vn-text);
    background: var(--vn-surface);
    border-radius: 16px;
    box-shadow: 0 22px 70px rgb(0 0 0 / 30%);
  }

  header,
  footer {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 16px 18px;
  }

  header {
    justify-content: space-between;
    border-bottom: 1px solid var(--vn-border);
  }

  header h2,
  header p {
    margin: 0;
  }

  .eyebrow {
    font-size: 11px;
    font-weight: 700;
    color: var(--vn-control-text);
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }

  .icon-button {
    width: 34px;
    height: 34px;
    font-size: 22px;
    background: transparent;
    border: 0;
  }

  .date-form {
    display: grid;
    gap: 14px;
    padding: 18px;
  }

  label {
    display: grid;
    gap: 5px;
    font-size: 12px;
    font-weight: 600;
  }

  input,
  select {
    min-height: 38px;
    padding: 7px 9px;
    color: var(--vn-text);
    background: var(--vn-control-background);
    border: 1px solid var(--vn-border-strong);
    border-radius: 8px;
  }

  .date-time-row {
    display: flex;
    align-items: end;
    gap: 12px;
  }

  .date-time-row > label {
    flex: 1;
  }

  .date-time-row .check-label {
    flex: 0 0 auto;
    padding-bottom: 9px;
  }

  .check-label {
    display: flex;
    align-items: center;
    gap: 7px;
  }

  .check-label input {
    width: 17px;
    min-height: 17px;
    margin: 0;
    accent-color: var(--vn-accent);
  }

  .disabled {
    opacity: 0.55;
  }

  fieldset {
    padding: 12px;
    border: 1px solid var(--vn-border);
    border-radius: 10px;
  }

  legend {
    padding: 0 5px;
    font-size: 12px;
    font-weight: 700;
  }

  .alert-options {
    display: flex;
    flex-wrap: wrap;
    gap: 10px 18px;
  }

  fieldset small {
    display: block;
    margin-top: 10px;
    color: var(--vn-text-muted);
  }

  footer {
    display: grid;
    grid-template-columns: auto 1fr auto auto;
    border-top: 1px solid var(--vn-border);
  }

  footer button {
    min-height: 38px;
    padding: 7px 13px;
  }

  .primary-button {
    color: var(--vn-on-accent);
    background: var(--vn-accent-strong);
    border-color: var(--vn-accent-strong);
  }

  @media (max-width: 560px) {
    .date-time-row {
      align-items: stretch;
      flex-direction: column;
    }

    .date-time-row .check-label {
      padding: 0;
    }
  }
</style>
