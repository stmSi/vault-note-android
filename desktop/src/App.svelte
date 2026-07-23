<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import AgendaPanel from './AgendaPanel.svelte';
  import DateDialog from './DateDialog.svelte';
  import DatePanel from './DatePanel.svelte';
  import NoteBlockEditor from './NoteBlockEditor.svelte';
  import {
    commandError,
    completeDatedEntry,
    createNote,
    deleteDatedEntry,
    deleteAttachment,
    exportCalendarEntry,
    exportAttachment,
    exportBackup,
    getNote,
    getAuthStatus,
    getSyncQueueStatus,
    importAttachment,
    initializeUnencryptedVault,
    initializeVault,
    listAgenda,
    listItems,
    listScheduledAlerts,
    listAttachments,
    lock,
    moveToTrash,
    restore,
    restoreBackup,
    runFakeSync,
    saveDatedEntry,
    saveStructuredNote,
    searchNotes,
    setArchived,
    setFavorite,
    setPinned,
    snoozeDatedEntry,
    unlock,
  } from './lib/api';
  import { DebouncedAutosaver, type AutosaveStatus } from './lib/autosave';
  import { derivePlainText, documentFromPlainText } from './lib/noteBody';
  import { reconcileReminderNotifications } from './lib/reminders';
  import type {
    AgendaEntry,
    AppCommandError,
    AuthStatus,
    DatedEntry,
    DatedEntryDraft,
    NoteBodyDocument,
    SyncQueueStatus,
    VaultItemSummary,
    VaultAttachment,
    VaultNote,
    VaultSection,
  } from './lib/models';

  interface DisplayItem {
    item: VaultItemSummary;
    snippet: string | null;
  }

  interface Draft {
    id: string;
    title: string;
    bodyDocument: NoteBodyDocument;
  }

  type ListState =
    | { kind: 'loading' }
    | { kind: 'empty' }
    | { kind: 'content'; items: DisplayItem[] }
    | { kind: 'error'; error: AppCommandError };

  const emptyQueue: SyncQueueStatus = {
    pendingCount: 0,
    runningCount: 0,
    retryCount: 0,
    failedCount: 0,
  };

  let section: VaultSection = 'active';
  let listState: ListState = { kind: 'loading' };
  let selected: VaultNote | null = null;
  let editorTitle = '';
  let editorDocument: NoteBodyDocument = { version: 1, blocks: [] };
  let metadataPanel: 'dates' | null = null;
  let dateDialogOpen = false;
  let editingDate: DatedEntry | null = null;
  let agendaOpen = false;
  let agendaEntries: AgendaEntry[] = [];
  let agendaIncludeCompleted = false;
  let agendaSelectedDate = '';
  let dateBusy = false;
  let reminderMessage = '';
  let searchQuery = '';
  let autosaveStatus: AutosaveStatus = 'saved';
  let autosaver: DebouncedAutosaver<Draft> | null = null;
  let actionError: AppCommandError | null = null;
  let queueStatus = emptyQueue;
  let syncRunning = false;
  let loadGeneration = 0;
  let searchTimer: ReturnType<typeof setTimeout> | undefined;
  let reminderRefreshTimer: ReturnType<typeof setInterval> | undefined;
  let authentication: AuthStatus | null = null;
  let unlockPassword = '';
  let newPassword = '';
  let confirmPassword = '';
  let authenticationBusy = false;
  let securityPanelOpen = false;
  let attachments: VaultAttachment[] = [];
  let attachmentBusy = false;
  let backupPassword = '';
  let backupBusy = false;
  let backupMessage = '';

  onMount(() => {
    void initializeAuthentication();
    reminderRefreshTimer = setInterval(() => {
      void refreshReminderSchedule(false);
    }, 60_000);
  });

  async function initializeAuthentication(): Promise<void> {
    try {
      authentication = await getAuthStatus();
      if (authentication.unlocked) {
        await Promise.all([
          loadVisibleItems(),
          refreshQueueStatus(),
          refreshReminderSchedule(false),
        ]);
      }
    } catch (error) {
      actionError = commandError(error);
    }
  }

  async function unlockVault(): Promise<void> {
    authenticationBusy = true;
    actionError = null;
    try {
      authentication = await unlock(unlockPassword);
      unlockPassword = '';
      await Promise.all([
        loadVisibleItems(),
        refreshQueueStatus(),
        refreshReminderSchedule(false),
      ]);
    } catch (error) {
      unlockPassword = '';
      actionError = commandError(error);
    } finally {
      authenticationBusy = false;
    }
  }

  async function lockVault(): Promise<void> {
    if (!(await flushEditor())) {
      return;
    }
    try {
      authentication = await lock();
      autosaver = null;
      selected = null;
      editorTitle = '';
      editorDocument = { version: 1, blocks: [] };
      agendaOpen = false;
      metadataPanel = null;
      listState = { kind: 'loading' };
      securityPanelOpen = false;
    } catch (error) {
      actionError = commandError(error);
    }
  }

  async function createVaultPassword(): Promise<void> {
    if (newPassword !== confirmPassword) {
      actionError = {
        code: 'password_mismatch',
        message: 'The passwords do not match.',
        retryable: false,
      };
      return;
    }
    authenticationBusy = true;
    try {
      authentication = await initializeVault(newPassword);
      newPassword = '';
      confirmPassword = '';
      await Promise.all([
        loadVisibleItems(),
        refreshQueueStatus(),
        refreshReminderSchedule(false),
      ]);
    } catch (error) {
      actionError = commandError(error);
      try {
        authentication = await getAuthStatus();
      } catch {
        // Preserve the initialization error, which is more actionable here.
      }
    } finally {
      newPassword = '';
      confirmPassword = '';
      authenticationBusy = false;
    }
  }

  async function createUnencryptedVault(): Promise<void> {
    authenticationBusy = true;
    actionError = null;
    try {
      authentication = await initializeUnencryptedVault();
      await Promise.all([
        loadVisibleItems(),
        refreshQueueStatus(),
        refreshReminderSchedule(false),
      ]);
    } catch (error) {
      actionError = commandError(error);
      try {
        authentication = await getAuthStatus();
      } catch {
        // Preserve the initialization error, which is more actionable here.
      }
    } finally {
      authenticationBusy = false;
    }
  }

  async function createBackup(): Promise<void> {
    if (!(await flushEditor())) return;
    backupBusy = true;
    backupMessage = '';
    try {
      const result = await exportBackup(backupPassword);
      if (result !== null) {
        backupMessage = `Encrypted backup created: ${result.itemCount} notes and ${result.attachmentCount} files.`;
      }
    } catch (error) {
      actionError = commandError(error);
    } finally {
      backupPassword = '';
      backupBusy = false;
    }
  }

  async function importBackup(): Promise<void> {
    if (!(await flushEditor())) return;
    backupBusy = true;
    backupMessage = '';
    try {
      const result = await restoreBackup(backupPassword);
      if (result !== null) {
        selected = null;
        autosaver = null;
        backupMessage = `Restored ${result.restoredItemCount} notes and ${result.restoredAttachmentCount} files as new local copies.`;
        await Promise.all([
          loadVisibleItems(),
          refreshQueueStatus(),
          refreshReminderSchedule(false),
        ]);
      }
    } catch (error) {
      actionError = commandError(error);
    } finally {
      backupPassword = '';
      backupBusy = false;
    }
  }

  onDestroy(() => {
    if (searchTimer !== undefined) {
      clearTimeout(searchTimer);
    }
    if (reminderRefreshTimer !== undefined) {
      clearInterval(reminderRefreshTimer);
    }
    void autosaver?.flush();
  });

  async function loadVisibleItems(showLoading = true): Promise<void> {
    const generation = ++loadGeneration;
    const requestedQuery = searchQuery.trim();
    if (showLoading) {
      listState = { kind: 'loading' };
    }
    try {
      const items: DisplayItem[] = requestedQuery
        ? (await searchNotes(requestedQuery)).map((result) => ({
            item: result.item,
            snippet: result.snippet,
          }))
        : (await listItems(section)).map((item) => ({ item, snippet: null }));
      if (generation !== loadGeneration) {
        return;
      }
      listState = items.length === 0 ? { kind: 'empty' } : { kind: 'content', items };
    } catch (error) {
      if (generation === loadGeneration) {
        listState = { kind: 'error', error: commandError(error) };
      }
    }
  }

  async function refreshQueueStatus(): Promise<void> {
    try {
      queueStatus = await getSyncQueueStatus();
    } catch (error) {
      actionError = commandError(error);
    }
  }

  async function flushEditor(): Promise<boolean> {
    if (autosaver === null) {
      return true;
    }
    const saved = await autosaver.flush();
    if (!saved && actionError === null) {
      actionError = {
        code: 'autosave_failed',
        message: 'The latest changes are still only in this window. Retry saving before leaving.',
        retryable: true,
      };
    }
    return saved;
  }

  function installNote(note: VaultNote): void {
    autosaver?.cancelPending();
    selected = note;
    editorTitle = note.title;
    editorDocument = note.bodyDocument ?? documentFromPlainText(note.body);
    autosaveStatus = 'saved';
    actionError = null;
    attachments = [];
    metadataPanel = null;
    void loadNoteAttachments(note.id);
    if (note.deletedAtEpochMillis !== null) {
      autosaver = null;
      return;
    }
    autosaver = new DebouncedAutosaver<Draft>(
      async (draft) => {
        try {
          const updated = await saveStructuredNote(
            draft.id,
            draft.title,
            draft.bodyDocument,
          );
          if (selected?.id === updated.id) {
            selected = {
              ...updated,
              title: editorTitle,
              body: derivePlainText(editorDocument),
              bodyDocument: editorDocument,
            };
          }
          await Promise.all([loadVisibleItems(false), refreshQueueStatus()]);
        } catch (error) {
          actionError = commandError(error);
          throw error;
        }
      },
      (status) => {
        autosaveStatus = status;
      },
    );
  }

  async function loadNoteAttachments(noteId: string): Promise<void> {
    try {
      const loaded = await listAttachments(noteId);
      if (selected?.id === noteId) {
        attachments = loaded;
      }
    } catch (error) {
      actionError = commandError(error);
    }
  }

  async function addAttachment(): Promise<void> {
    if (selected === null || !(await flushEditor())) {
      return;
    }
    attachmentBusy = true;
    try {
      const imported = await importAttachment(selected.id);
      if (imported !== null) {
        await Promise.all([
          loadNoteAttachments(selected.id),
          loadVisibleItems(false),
          refreshQueueStatus(),
        ]);
        installNote(await getNote(selected.id));
      }
    } catch (error) {
      actionError = commandError(error);
    } finally {
      attachmentBusy = false;
    }
  }

  async function saveAttachment(id: string): Promise<void> {
    attachmentBusy = true;
    try {
      await exportAttachment(id);
    } catch (error) {
      actionError = commandError(error);
    } finally {
      attachmentBusy = false;
    }
  }

  async function removeAttachment(id: string): Promise<void> {
    if (selected === null) {
      return;
    }
    attachmentBusy = true;
    try {
      await deleteAttachment(id);
      await Promise.all([
        loadNoteAttachments(selected.id),
        loadVisibleItems(false),
        refreshQueueStatus(),
      ]);
      installNote(await getNote(selected.id));
    } catch (error) {
      actionError = commandError(error);
    } finally {
      attachmentBusy = false;
    }
  }

  function formattedFileSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
  }

  async function openNote(id: string): Promise<void> {
    if (selected?.id === id) {
      return;
    }
    if (!(await flushEditor())) {
      return;
    }
    try {
      installNote(await getNote(id));
    } catch (error) {
      actionError = commandError(error);
    }
  }

  async function newNote(): Promise<void> {
    if (!(await flushEditor())) {
      return;
    }
    try {
      searchQuery = '';
      section = 'active';
      const note = await createNote();
      installNote(note);
      await Promise.all([
        loadVisibleItems(false),
        refreshQueueStatus(),
        refreshReminderSchedule(false),
      ]);
    } catch (error) {
      actionError = commandError(error);
    }
  }

  function draftChanged(): void {
    if (selected === null || autosaver === null) {
      return;
    }
    autosaver.submit({
      id: selected.id,
      title: editorTitle,
      bodyDocument: editorDocument,
    });
  }

  function bodyDocumentChanged(document: NoteBodyDocument): void {
    editorDocument = document;
    draftChanged();
  }

  async function chooseSection(nextSection: VaultSection): Promise<void> {
    if (section === nextSection && searchQuery.length === 0) {
      return;
    }
    if (!(await flushEditor())) {
      return;
    }
    selected = null;
    autosaver = null;
    section = nextSection;
    searchQuery = '';
    await loadVisibleItems();
  }

  function searchChanged(): void {
    if (searchTimer !== undefined) {
      clearTimeout(searchTimer);
    }
    searchTimer = setTimeout(() => {
      searchTimer = undefined;
      void loadVisibleItems();
    }, 150);
  }

  async function updateMetadata(
    operation: (id: string) => Promise<VaultNote>,
    removeFromView = false,
  ): Promise<void> {
    if (selected === null || !(await flushEditor())) {
      return;
    }
    try {
      const updated = await operation(selected.id);
      if (removeFromView) {
        selected = null;
        autosaver = null;
      } else {
        installNote(updated);
      }
      await Promise.all([
        loadVisibleItems(false),
        refreshQueueStatus(),
        refreshReminderSchedule(false),
      ]);
    } catch (error) {
      actionError = commandError(error);
    }
  }

  async function synchronize(): Promise<void> {
    if (!(await flushEditor())) {
      return;
    }
    syncRunning = true;
    actionError = null;
    try {
      await runFakeSync();
      if (selected !== null) {
        installNote(await getNote(selected.id));
      }
      await Promise.all([loadVisibleItems(false), refreshQueueStatus()]);
    } catch (error) {
      actionError = commandError(error);
    } finally {
      syncRunning = false;
    }
  }

  async function refreshReminderSchedule(requestAccess: boolean): Promise<void> {
    if (!authentication?.unlocked) return;
    try {
      const result = await reconcileReminderNotifications(
        await listScheduledAlerts(),
        requestAccess,
      );
      reminderMessage =
        requestAccess && !result.permissionGranted
            ? 'Notification access is off. Dates remain available in the agenda.'
          : result.scheduledCount > 0
            ? `${result.scheduledCount} private alert${result.scheduledCount === 1 ? '' : 's'} active while VaultNote is running.`
            : '';
    } catch {
      reminderMessage = 'System notifications are unavailable. Dates remain saved in the agenda.';
    }
  }

  async function showAgenda(): Promise<void> {
    if (!(await flushEditor())) return;
    agendaOpen = true;
    await refreshAgenda();
  }

  async function refreshAgenda(): Promise<void> {
    try {
      agendaEntries = await listAgenda(agendaIncludeCompleted);
    } catch (error) {
      actionError = commandError(error);
    }
  }

  async function setAgendaIncludeCompleted(value: boolean): Promise<void> {
    agendaIncludeCompleted = value;
    await refreshAgenda();
  }

  function addDate(): void {
    editingDate = null;
    dateDialogOpen = true;
  }

  function editDate(entry: DatedEntry): void {
    editingDate = entry;
    dateDialogOpen = true;
  }

  async function persistDate(draft: DatedEntryDraft): Promise<void> {
    const itemId = editingDate?.itemId ?? selected?.id;
    if (itemId === undefined || !(await flushEditor())) return;
    dateBusy = true;
    try {
      const updated = await saveDatedEntry(itemId, draft);
      if (selected?.id === updated.id) {
        installNote(updated);
        metadataPanel = 'dates';
      }
      dateDialogOpen = false;
      editingDate = null;
      await Promise.all([
        loadVisibleItems(false),
        refreshQueueStatus(),
        refreshAgenda(),
        refreshReminderSchedule(true),
      ]);
    } catch (error) {
      actionError = commandError(error);
    } finally {
      dateBusy = false;
    }
  }

  async function removeDate(id: string): Promise<void> {
    if (!window.confirm('Delete this date and its alerts?')) return;
    dateBusy = true;
    try {
      await deleteDatedEntry(id);
      dateDialogOpen = false;
      editingDate = null;
      await refreshAfterDateChange(true);
    } catch (error) {
      actionError = commandError(error);
    } finally {
      dateBusy = false;
    }
  }

  async function finishDate(id: string): Promise<void> {
    dateBusy = true;
    try {
      await completeDatedEntry(id);
      await refreshAfterDateChange(false);
    } catch (error) {
      actionError = commandError(error);
    } finally {
      dateBusy = false;
    }
  }

  async function snoozeDate(id: string): Promise<void> {
    dateBusy = true;
    try {
      await snoozeDatedEntry(id, 10);
      await refreshAfterDateChange(true);
      reminderMessage = 'Alert snoozed for 10 minutes.';
    } catch (error) {
      actionError = commandError(error);
    } finally {
      dateBusy = false;
    }
  }

  async function refreshAfterDateChange(requestAccess: boolean): Promise<void> {
    const selectedId = selected?.id;
    if (selectedId !== undefined) {
      const panel = metadataPanel;
      installNote(await getNote(selectedId));
      metadataPanel = panel;
    }
    await Promise.all([
      loadVisibleItems(false),
      refreshQueueStatus(),
      refreshAgenda(),
      refreshReminderSchedule(requestAccess),
    ]);
  }

  async function exportDate(id: string): Promise<void> {
    if (
      !window.confirm(
        'Exporting creates a readable calendar file containing this date label and note title. Continue?',
      )
    ) {
      return;
    }
    dateBusy = true;
    try {
      await exportCalendarEntry(id);
    } catch (error) {
      actionError = commandError(error);
    } finally {
      dateBusy = false;
    }
  }

  async function openAgendaNote(itemId: string): Promise<void> {
    agendaOpen = false;
    await openNote(itemId);
  }

  function toggleDatesPanel(): void {
    metadataPanel = metadataPanel === 'dates' ? null : 'dates';
  }

  function formattedDate(epochMillis: number): string {
    return new Intl.DateTimeFormat(undefined, {
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date(epochMillis));
  }
</script>

<div class="app-shell">
  <header class="app-header">
    <div class="brand-block">
      <div class="brand-mark" aria-hidden="true">V</div>
      <div>
        <h1>VaultNote</h1>
        <p>Local-first desktop notes</p>
      </div>
    </div>
    <div class="sync-block">
      <span class="queue-label">
        {queueStatus.pendingCount + queueStatus.retryCount} queued
      </span>
      <button class="secondary-button" disabled={syncRunning} onclick={synchronize}>
        {syncRunning ? 'Syncing…' : 'Run fake sync'}
      </button>
      {#if authentication?.unlocked}
        <button class="secondary-button" onclick={showAgenda}>Calendar</button>
        <button class="secondary-button" onclick={() => (securityPanelOpen = !securityPanelOpen)}>
          Security
        </button>
        {#if authentication.encryptionMode === 'PASSWORD'}
          <button class="secondary-button" onclick={lockVault}>Lock</button>
        {/if}
      {/if}
    </div>
  </header>

  {#if securityPanelOpen && authentication?.unlocked}
    <section class="security-panel" aria-label="Vault security">
      <div class="security-group">
        {#if authentication.encryptionMode === 'PASSWORD'}
          <p>Your password derives the SQLCipher and attachment key in Rust. It is required after every launch and cannot be recovered.</p>
        {:else}
          <p><strong>Encryption is disabled.</strong> The SQLite database and attachments are stored as readable plaintext files.</p>
        {/if}
      </div>
      <div class="security-group backup-controls">
        <label>
          <span>Backup password (12–128 characters)</span>
          <input type="password" minlength="12" maxlength="128" autocomplete="new-password" bind:value={backupPassword} />
        </label>
        <button disabled={backupBusy || backupPassword.length < 12} onclick={createBackup}>Export .vnb</button>
        <button disabled={backupBusy || backupPassword.length < 12} onclick={importBackup}>Restore .vnb</button>
        {#if backupMessage}<p role="status">{backupMessage}</p>{/if}
      </div>
    </section>
  {/if}

  {#if actionError !== null}
    <div class="error-banner" role="alert">
      <span>{actionError.message}</span>
      {#if actionError.retryable && autosaveStatus === 'error'}
        <button onclick={() => void flushEditor()}>Retry save</button>
      {/if}
      <button aria-label="Dismiss error" onclick={() => (actionError = null)}>×</button>
    </div>
  {/if}
  {#if reminderMessage}
    <div class="reminder-banner" role="status">
      <span>{reminderMessage}</span>
      <button aria-label="Dismiss reminder status" onclick={() => (reminderMessage = '')}>×</button>
    </div>
  {/if}

  <main class="workspace">
    <aside class="sidebar" aria-label="Vault navigation">
      <button class="new-note-button" onclick={newNote}>+ New note</button>

      <nav class="section-tabs" aria-label="Note sections">
        <button class:active={section === 'active'} onclick={() => chooseSection('active')}>
          Notes
        </button>
        <button class:active={section === 'archived'} onclick={() => chooseSection('archived')}>
          Archive
        </button>
        <button class:active={section === 'trash'} onclick={() => chooseSection('trash')}>
          Trash
        </button>
      </nav>

      <label class="search-field">
        <span class="visually-hidden">Search notes</span>
        <input
          type="search"
          placeholder="Search notes"
          maxlength="200"
          bind:value={searchQuery}
          oninput={searchChanged}
        />
      </label>

      <section class="note-list" aria-live="polite">
        {#if listState.kind === 'loading'}
          <div class="state-card">
            <div class="spinner" aria-hidden="true"></div>
            <p>Loading local notes…</p>
          </div>
        {:else if listState.kind === 'empty'}
          <div class="state-card">
            <p class="state-title">Nothing here yet</p>
            <p>{searchQuery.trim() ? 'Try a different search.' : 'Create a note to get started.'}</p>
          </div>
        {:else if listState.kind === 'error'}
          <div class="state-card error-state" role="alert">
            <p class="state-title">Could not load notes</p>
            <p>{listState.error.message}</p>
            <button onclick={() => loadVisibleItems()}>Retry</button>
          </div>
        {:else}
          {#each listState.items as display (display.item.id)}
            <button
              class="note-row"
              class:selected={selected?.id === display.item.id}
              onclick={() => openNote(display.item.id)}
            >
              <span class="note-title-line">
                <strong>{display.item.title || 'Untitled note'}</strong>
                <span class="row-flags" aria-label="Note flags">
                  {display.item.isPinned ? '●' : ''}{display.item.isFavorite ? '★' : ''}
                </span>
              </span>
              <span class="note-preview">{display.snippet || display.item.bodyPreview || 'Empty note'}</span>
              <span class="note-meta">
                {formattedDate(display.item.updatedAtEpochMillis)} · {display.item.syncStatus.toLowerCase()}
              </span>
              {#if display.item.hasOverdueEntry}
                <span class="date-badge overdue">Overdue deadline</span>
              {:else if display.item.nextDatedEntryAtEpochMillis !== null}
                <span class="date-badge">
                  Next {formattedDate(display.item.nextDatedEntryAtEpochMillis)}
                </span>
              {/if}
            </button>
          {/each}
        {/if}
      </section>
    </aside>

    <section class="editor-pane" aria-label="Note editor">
      {#if selected === null}
        <div class="editor-empty">
          <div class="empty-glyph" aria-hidden="true">✦</div>
          <h2>Select a note</h2>
          <p>Notes are stored locally in SQLite and remain available offline.</p>
        </div>
      {:else}
        <header class="editor-toolbar">
          <div class="editor-actions">
            {#if selected.deletedAtEpochMillis !== null}
              <button onclick={() => updateMetadata(restore, true)}>Restore</button>
            {:else}
              <button
                class:enabled={selected.isPinned}
                aria-pressed={selected.isPinned}
                onclick={() => updateMetadata((id) => setPinned(id, !selected!.isPinned))}
              >Pin</button>
              <button
                class:enabled={selected.isFavorite}
                aria-pressed={selected.isFavorite}
                onclick={() => updateMetadata((id) => setFavorite(id, !selected!.isFavorite))}
              >Favorite</button>
              <button onclick={() => updateMetadata((id) => setArchived(id, !selected!.isArchived), true)}>
                {selected.isArchived ? 'Unarchive' : 'Archive'}
              </button>
              <button class="danger-button" onclick={() => updateMetadata(moveToTrash, true)}>
                Move to trash
              </button>
            {/if}
          </div>
          <span class:save-error={autosaveStatus === 'error'} class="save-status">
            {autosaveStatus === 'saved'
              ? 'Saved locally'
              : autosaveStatus === 'dirty'
                ? 'Unsaved changes'
                : autosaveStatus === 'saving'
                  ? 'Saving…'
                  : 'Save failed'}
          </span>
        </header>

        <div class="editor-content">
          <input
            class="title-input"
            aria-label="Note title"
            placeholder="Untitled note"
            maxlength="500"
            readonly={selected.deletedAtEpochMillis !== null}
            bind:value={editorTitle}
            oninput={draftChanged}
          />
          <NoteBlockEditor
            document={editorDocument}
            readonly={selected.deletedAtEpochMillis !== null}
            dateCount={selected.datedEntries.length}
            onDocumentChange={bodyDocumentChanged}
            onDates={toggleDatesPanel}
          />
          <section class="attachment-section" aria-label="Attachments">
            <header>
              <div>
                <strong>Attachments</strong>
                <small>
                  {attachments.length} {attachments.length === 1 ? 'file' : 'files'}
                </small>
              </div>
              {#if selected.deletedAtEpochMillis === null}
                <button disabled={attachmentBusy} onclick={addAttachment}>+ Add file</button>
              {/if}
            </header>
            <div class="attachment-list">
              {#if attachments.length === 0}
                <p>No attachments yet.</p>
              {/if}
              {#each attachments as attachment (attachment.id)}
                <div class="attachment-row">
                  <span>
                    <strong>{attachment.displayName}</strong>
                    <small>
                      {formattedFileSize(attachment.fileSize)} · {authentication?.encryptionMode === 'PASSWORD'
                        ? 'encrypted locally'
                        : 'stored without encryption'}
                    </small>
                  </span>
                  <button disabled={attachmentBusy} onclick={() => saveAttachment(attachment.id)}>Save copy</button>
                  {#if selected.deletedAtEpochMillis === null}
                    <button class="danger-button" disabled={attachmentBusy} onclick={() => removeAttachment(attachment.id)}>Delete</button>
                  {/if}
                </div>
              {/each}
            </div>
          </section>
          {#if metadataPanel === 'dates' && selected.deletedAtEpochMillis === null}
            <DatePanel
              entries={selected.datedEntries}
              busy={dateBusy}
              onAdd={addDate}
              onEdit={editDate}
              onComplete={finishDate}
              onSnooze={snoozeDate}
              onExport={exportDate}
              onClose={() => (metadataPanel = null)}
            />
          {/if}
          <footer class="editor-footer">
            <span>Revision {selected.localRevision}</span>
            <span>Updated {formattedDate(selected.updatedAtEpochMillis)}</span>
          </footer>
        </div>
      {/if}
      {#if agendaOpen}
        <AgendaPanel
          entries={agendaEntries}
          includeCompleted={agendaIncludeCompleted}
          selectedDate={agendaSelectedDate}
          busy={dateBusy}
          onClose={() => (agendaOpen = false)}
          onIncludeCompleted={setAgendaIncludeCompleted}
          onSelectedDate={(value) => (agendaSelectedDate = value)}
          onOpen={openAgendaNote}
          onEdit={(row) => editDate(row.entry)}
          onComplete={finishDate}
          onSnooze={snoozeDate}
          onExport={exportDate}
        />
      {/if}
    </section>
  </main>

  {#if dateDialogOpen}
    <DateDialog
      entry={editingDate}
      onSave={persistDate}
      onDelete={removeDate}
      onClose={() => {
        dateDialogOpen = false;
        editingDate = null;
      }}
    />
  {/if}

  {#if authentication === null}
    <div class="lock-overlay" aria-live="polite">
      <div class="lock-card">
        <div class="spinner" aria-hidden="true"></div>
        <h2>Opening encrypted vault…</h2>
      </div>
    </div>
  {:else if authentication.setupRequired}
    <div class="lock-overlay">
      <form class="lock-card" onsubmit={(event) => { event.preventDefault(); void createVaultPassword(); }}>
        <div class="brand-mark" aria-hidden="true">V</div>
        <h2>Create vault password</h2>
        <p>This password encrypts your database and files. VaultNote cannot recover it.</p>
        <label>
          <span>Vault password</span>
          <input
            type="password"
            minlength="12"
            maxlength="128"
            autocomplete="new-password"
            bind:value={newPassword}
          />
        </label>
        <label>
          <span>Confirm password</span>
          <input
            type="password"
            minlength="12"
            maxlength="128"
            autocomplete="new-password"
            bind:value={confirmPassword}
          />
        </label>
        {#if actionError !== null}<p class="lock-error" role="alert">{actionError.message}</p>{/if}
        <button
          class="new-note-button"
          type="submit"
          disabled={authenticationBusy || newPassword.length < 12 || confirmPassword.length < 12}
        >
          {authenticationBusy ? 'Encrypting vault…' : 'Create and unlock'}
        </button>
        <div class="setup-divider"><span>or</span></div>
        <p class="unencrypted-warning">
          Without encryption, anyone or any program that can read your files can read your notes and attachments.
        </p>
        <button
          class="secondary-setup-button"
          type="button"
          disabled={authenticationBusy}
          onclick={createUnencryptedVault}
        >
          Continue without password or encryption
        </button>
      </form>
    </div>
  {:else if !authentication.unlocked}
    <div class="lock-overlay">
      <form class="lock-card" onsubmit={(event) => { event.preventDefault(); void unlockVault(); }}>
        <div class="brand-mark" aria-hidden="true">V</div>
        <h2>Vault locked</h2>
        <p>Enter your local VaultNote password.</p>
        <label>
          <span class="visually-hidden">Password</span>
          <input
            type="password"
            autocomplete="current-password"
            minlength="12"
            maxlength="128"
            bind:value={unlockPassword}
          />
        </label>
        {#if actionError !== null}<p class="lock-error" role="alert">{actionError.message}</p>{/if}
        <button class="new-note-button" type="submit" disabled={authenticationBusy || unlockPassword.length === 0}>
          {authenticationBusy ? 'Unlocking…' : 'Unlock'}
        </button>
      </form>
    </div>
  {/if}
</div>
