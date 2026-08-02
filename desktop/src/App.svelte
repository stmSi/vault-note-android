<script lang="ts">
  import { getCurrentWebview } from '@tauri-apps/api/webview';
  import type { UnlistenFn } from '@tauri-apps/api/event';
  import { onDestroy, onMount } from 'svelte';
  import AgendaPanel from './AgendaPanel.svelte';
  import AttachmentPanel from './AttachmentPanel.svelte';
  import AttachmentPreview from './AttachmentPreview.svelte';
  import DateDialog from './DateDialog.svelte';
  import DatePanel from './DatePanel.svelte';
  import NoteBlockEditor from './NoteBlockEditor.svelte';
  import ThemeDrawer from './ThemeDrawer.svelte';
  import {
    commandError,
    approveNearbyPairing,
    completeDatedEntry,
    createNote,
    deleteDatedEntry,
    deleteAttachment,
    disconnectRelay,
    discoverRelays,
    enableEmbeddedRelay,
    exportCalendarEntry,
    exportAttachment,
    exportBackup,
    exportPlaintextBackup,
    getNote,
    getAuthStatus,
    getEmbeddedRelayPairingDetails,
    getEmbeddedRelayStatus,
    getPendingNearbyPairings,
    getSyncConnectionStatus,
    getSyncQueueStatus,
    importAttachment,
    importAttachmentPath,
    initializeUnencryptedVault,
    initializeVault,
    listAgenda,
    listItems,
    listScheduledAlerts,
    listAttachments,
    lock,
    moveToTrash,
    openAttachment,
    pairRelay,
    previewAttachment,
    restore,
    restoreBackup,
    restoreBackupPath,
    restorePlaintextBackup,
    resetEmbeddedRelayAccess,
    rejectNearbyPairing,
    runSync,
    saveDatedEntry,
    saveStructuredNote,
    searchNotes,
    setArchived,
    setFavorite,
    setPinned,
    snoozeDatedEntry,
    unlock,
    unlockSync,
    inspectBackupPath,
  } from './lib/api';
  import { DebouncedAutosaver, type AutosaveStatus } from './lib/autosave';
  import { previewableImages } from './lib/attachments';
  import { derivePlainText, documentFromPlainText } from './lib/noteBody';
  import { reconcileReminderNotifications } from './lib/reminders';
  import { parseThemePreference, type ThemePreference } from './lib/themes';
  import type {
    AgendaEntry,
    AppCommandError,
    AuthStatus,
    BackupInspection,
    DatedEntry,
    DatedEntryDraft,
    DiscoveredRelay,
    EmbeddedRelayPairingDetails,
    EmbeddedRelayStatus,
    PendingNearbyPairing,
    NoteBodyDocument,
    SyncQueueStatus,
    SyncConnectionStatus,
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

  interface AttachmentPreviewState {
    attachment: VaultAttachment;
    objectUrl: string;
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
  let syncSettingsOpen = false;
  let themeDrawerOpen = false;
  let themeButton: HTMLButtonElement;
  let attachments: VaultAttachment[] = [];
  let attachmentBusy = false;
  let attachmentPreview: AttachmentPreviewState | null = null;
  let attachmentPreviewRequest = 0;
  $: imageAttachments = previewableImages(attachments);
  $: attachmentPreviewIndex = attachmentPreview === null
    ? -1
    : imageAttachments.findIndex((attachment) => attachment.id === attachmentPreview?.attachment.id);
  let backupPassword = '';
  let backupBusy = false;
  let backupMessage = '';
  let droppedBackup: { path: string; inspection: BackupInspection } | null = null;
  let droppedBackupPassword = '';
  let droppedPlaintextConfirmed = false;
  let dropActive = false;
  let dropBusy = false;
  let feedbackMessage = '';
  let feedbackTimer: ReturnType<typeof setTimeout> | undefined;
  let dragDropUnlisten: UnlistenFn | undefined;
  let componentDestroyed = false;
  let searchInput: HTMLInputElement;
  let titleInput: HTMLInputElement;
  let themePreference: ThemePreference = 'dark';
  let syncConnection: SyncConnectionStatus | null = null;
  let discoveredRelays: DiscoveredRelay[] = [];
  let relayHostAddress = '';
  let relayPort = 8787;
  let relayVaultId = '';
  let relayFingerprint = '';
  let relayToken = '';
  let relayPassword = '';
  let relayFingerprintConfirmed = false;
  let relayBusy = false;
  let syncMessage = '';
  let automaticSyncTimer: ReturnType<typeof setInterval> | undefined;
  let embeddedRelay: EmbeddedRelayStatus | null = null;
  let embeddedPairing: EmbeddedRelayPairingDetails | null = null;
  let embeddedPassword = '';
  let embeddedBusy = false;
  let embeddedPairingTimer: ReturnType<typeof setTimeout> | undefined;
  let nearbyPairingTimer: ReturnType<typeof setInterval> | undefined;
  let pendingNearbyPairings: PendingNearbyPairing[] = [];
  let nearbyPairingBusy = '';
  let nearbyPairingPolling = false;

  onMount(() => {
    themePreference = storedThemePreference();
    applyTheme(themePreference);
    void initializeAuthentication();
    window.addEventListener('keydown', handleGlobalShortcut);
    void getCurrentWebview()
      .onDragDropEvent((event) => {
        const payload = event.payload;
        if (payload.type === 'enter' || payload.type === 'over') {
          dropActive = authentication?.unlocked === true;
        } else if (payload.type === 'leave') {
          dropActive = false;
        } else {
          dropActive = false;
          if (authentication?.unlocked) {
            void handleDroppedPaths(payload.paths);
          }
        }
      })
      .then((unlisten) => {
        if (componentDestroyed) {
          unlisten();
        } else {
          dragDropUnlisten = unlisten;
        }
      })
      .catch((error: unknown) => {
        actionError = commandError(error);
      });
    reminderRefreshTimer = setInterval(() => {
      void refreshReminderSchedule(false);
    }, 60_000);
    automaticSyncTimer = setInterval(() => {
      if (authentication?.unlocked && syncConnection?.unlocked && !syncRunning) {
        void synchronize(false);
      }
    }, 45_000);
    nearbyPairingTimer = setInterval(() => {
      if (authentication?.unlocked && embeddedRelay?.running) {
        void refreshNearbyPairings();
      }
    }, 1_200);
  });

  async function initializeAuthentication(): Promise<void> {
    try {
      authentication = await getAuthStatus();
      if (authentication.unlocked) {
        await Promise.all([
          loadVisibleItems(),
          refreshQueueStatus(),
          refreshSyncConnection(),
          refreshEmbeddedRelay(),
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
        refreshSyncConnection(),
        refreshEmbeddedRelay(),
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
      closeAttachmentPreview();
      authentication = await lock();
      autosaver = null;
      selected = null;
      editorTitle = '';
      editorDocument = { version: 1, blocks: [] };
      agendaOpen = false;
      metadataPanel = null;
      listState = { kind: 'loading' };
      syncSettingsOpen = false;
      themeDrawerOpen = false;
      syncConnection = null;
      embeddedPairing = null;
      pendingNearbyPairings = [];
      embeddedPassword = '';
      relayPassword = '';
      relayToken = '';
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
        refreshSyncConnection(),
        refreshEmbeddedRelay(),
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
        refreshSyncConnection(),
        refreshEmbeddedRelay(),
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
        showFeedback('Encrypted backup exported');
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
        await finishRestore(result);
      }
    } catch (error) {
      actionError = commandError(error);
    } finally {
      backupPassword = '';
      backupBusy = false;
    }
  }

  async function createPlaintextBackup(): Promise<void> {
    if (
      !window.confirm(
        'Export a readable backup? Anyone with this file can read every note and attachment.',
      ) ||
      !(await flushEditor())
    ) {
      return;
    }
    backupBusy = true;
    backupMessage = '';
    try {
      const result = await exportPlaintextBackup();
      if (result !== null) {
        backupMessage = `Readable backup created: ${result.itemCount} notes and ${result.attachmentCount} files.`;
        showFeedback('Readable backup exported');
      }
    } catch (error) {
      actionError = commandError(error);
    } finally {
      backupBusy = false;
    }
  }

  async function importPlaintextBackup(): Promise<void> {
    if (
      !window.confirm(
        'Restore a readable backup? Validate that the file came from a source you trust.',
      ) ||
      !(await flushEditor())
    ) {
      return;
    }
    backupBusy = true;
    backupMessage = '';
    try {
      const result = await restorePlaintextBackup();
      if (result !== null) {
        await finishRestore(result);
      }
    } catch (error) {
      actionError = commandError(error);
    } finally {
      backupBusy = false;
    }
  }

  function storedThemePreference(): ThemePreference {
    return parseThemePreference(window.localStorage.getItem('vaultnote.theme'));
  }

  function applyTheme(theme: ThemePreference): void {
    themePreference = theme;
    document.documentElement.dataset.theme = theme;
    window.localStorage.setItem('vaultnote.theme', theme);
  }

  function openThemeDrawer(): void {
    syncSettingsOpen = false;
    themeDrawerOpen = true;
  }

  function closeThemeDrawer(restoreFocus = true): void {
    themeDrawerOpen = false;
    if (restoreFocus) {
      requestAnimationFrame(() => themeButton?.focus());
    }
  }

  function toggleSyncSettings(): void {
    themeDrawerOpen = false;
    syncSettingsOpen = !syncSettingsOpen;
  }

  function showFeedback(message: string): void {
    feedbackMessage = message;
    if (feedbackTimer !== undefined) {
      clearTimeout(feedbackTimer);
    }
    if (embeddedPairingTimer !== undefined) {
      clearTimeout(embeddedPairingTimer);
    }
    feedbackTimer = setTimeout(() => {
      feedbackMessage = '';
      feedbackTimer = undefined;
    }, 2_800);
  }

  function handleGlobalShortcut(event: KeyboardEvent): void {
    if (!authentication?.unlocked) {
      return;
    }
    const modifier = event.ctrlKey || event.metaKey;
    if (modifier && event.key.toLowerCase() === 'k') {
      event.preventDefault();
      searchInput?.focus();
      searchInput?.select();
      return;
    }
    if (modifier && event.key.toLowerCase() === 'n') {
      event.preventDefault();
      void newNote();
      return;
    }
    if (modifier && event.key.toLowerCase() === 's') {
      event.preventDefault();
      if (event.shiftKey) {
        void synchronize(true);
      } else {
        void flushEditor();
      }
      return;
    }
    if (event.key === 'Escape') {
      if (attachmentPreview !== null) {
        closeAttachmentPreview();
      } else if (droppedBackup !== null) {
        closeDroppedBackup();
      } else if (agendaOpen) {
        agendaOpen = false;
      } else if (metadataPanel !== null) {
        metadataPanel = null;
      } else if (themeDrawerOpen) {
        closeThemeDrawer();
      } else if (syncSettingsOpen) {
        syncSettingsOpen = false;
      }
    }
  }

  function filenameFromPath(path: string): string {
    const candidate = path.split(/[\\/]/).pop()?.trim() ?? '';
    return candidate || 'Dropped file';
  }

  function titleFromPath(path: string): string {
    const filename = filenameFromPath(path);
    const withoutExtension = filename.replace(/\.[^.]{1,16}$/u, '').trim();
    return (withoutExtension || filename).slice(0, 500);
  }

  async function handleDroppedPaths(paths: string[]): Promise<void> {
    if (dropBusy || paths.length === 0) {
      return;
    }
    const backups = paths.filter((path) => path.toLocaleLowerCase().endsWith('.vnb'));
    if (backups.length > 0) {
      if (paths.length !== 1) {
        actionError = {
          code: 'mixed_backup_drop',
          message: 'Drop one VaultNote backup at a time, without other files.',
          retryable: false,
        };
        return;
      }
      dropBusy = true;
      try {
        droppedBackup = {
          path: backups[0],
          inspection: await inspectBackupPath(backups[0]),
        };
        droppedBackupPassword = '';
        droppedPlaintextConfirmed = false;
      } catch (error) {
        actionError = commandError(error);
      } finally {
        dropBusy = false;
      }
      return;
    }

    if (!(await flushEditor())) {
      return;
    }
    dropBusy = true;
    attachmentBusy = true;
    actionError = null;
    try {
      let target = selected;
      if (target === null || target.deletedAtEpochMillis !== null) {
        searchQuery = '';
        section = 'active';
        const created = await createNote();
        const titled = await saveStructuredNote(
          created.id,
          titleFromPath(paths[0]),
          created.bodyDocument ?? documentFromPlainText(created.body),
        );
        installNote(titled);
        target = titled;
      }

      let importedCount = 0;
      let lastFailure: AppCommandError | null = null;
      for (const path of paths) {
        try {
          await importAttachmentPath(target.id, path);
          importedCount += 1;
        } catch (error) {
          lastFailure = commandError(error);
        }
      }

      installNote(await getNote(target.id));
      await Promise.all([
        loadNoteAttachments(target.id),
        loadVisibleItems(false),
        refreshQueueStatus(),
      ]);
      if (importedCount > 0) {
        showFeedback(
          `${importedCount} ${importedCount === 1 ? 'file' : 'files'} added to ${
            target.title || 'Untitled note'
          }`,
        );
      }
      if (lastFailure !== null) {
        actionError = {
          ...lastFailure,
          message:
            importedCount > 0
              ? `${importedCount} file${importedCount === 1 ? '' : 's'} imported, but another file could not be added. ${lastFailure.message}`
              : lastFailure.message,
        };
      }
    } catch (error) {
      actionError = commandError(error);
    } finally {
      attachmentBusy = false;
      dropBusy = false;
    }
  }

  function closeDroppedBackup(): void {
    droppedBackup = null;
    droppedBackupPassword = '';
    droppedPlaintextConfirmed = false;
  }

  async function restoreDroppedBackup(): Promise<void> {
    if (droppedBackup === null || !(await flushEditor())) {
      return;
    }
    const encrypted = droppedBackup.inspection.protection === 'ENCRYPTED';
    if (encrypted && droppedBackupPassword.length < 12) {
      return;
    }
    if (!encrypted && !droppedPlaintextConfirmed) {
      return;
    }
    backupBusy = true;
    actionError = null;
    try {
      const result = await restoreBackupPath(
        droppedBackup.path,
        encrypted ? droppedBackupPassword : null,
        droppedPlaintextConfirmed,
      );
      closeDroppedBackup();
      await finishRestore(result);
    } catch (error) {
      droppedBackupPassword = '';
      actionError = commandError(error);
    } finally {
      backupBusy = false;
    }
  }

  async function finishRestore(result: {
    restoredItemCount: number;
    restoredAttachmentCount: number;
  }): Promise<void> {
    selected = null;
    autosaver = null;
    backupMessage = `Restored ${result.restoredItemCount} notes and ${result.restoredAttachmentCount} files as new local copies.`;
    showFeedback('Backup restored and validated');
    await Promise.all([
      loadVisibleItems(),
      refreshQueueStatus(),
      refreshReminderSchedule(false),
    ]);
  }

  onDestroy(() => {
    componentDestroyed = true;
    closeAttachmentPreview();
    dragDropUnlisten?.();
    window.removeEventListener('keydown', handleGlobalShortcut);
    if (searchTimer !== undefined) {
      clearTimeout(searchTimer);
    }
    if (reminderRefreshTimer !== undefined) {
      clearInterval(reminderRefreshTimer);
    }
    if (automaticSyncTimer !== undefined) {
      clearInterval(automaticSyncTimer);
    }
    if (nearbyPairingTimer !== undefined) {
      clearInterval(nearbyPairingTimer);
    }
    if (feedbackTimer !== undefined) {
      clearTimeout(feedbackTimer);
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

  async function refreshSyncConnection(): Promise<void> {
    try {
      syncConnection = await getSyncConnectionStatus();
      if (syncConnection.configured) {
        relayHostAddress ||= syncConnection.hostAddress ?? '';
        relayPort = syncConnection.port ?? relayPort;
        relayVaultId ||= syncConnection.vaultId ?? '';
        relayFingerprint ||= syncConnection.certificateSha256 ?? '';
      }
    } catch (error) {
      actionError = commandError(error);
    }
  }

  async function refreshEmbeddedRelay(): Promise<void> {
    try {
      embeddedRelay = await getEmbeddedRelayStatus();
      if (embeddedRelay.running) {
        await refreshNearbyPairings();
      }
    } catch (error) {
      actionError = commandError(error);
    }
  }

  async function refreshNearbyPairings(): Promise<void> {
    if (nearbyPairingPolling || !authentication?.unlocked || !embeddedRelay?.running) {
      return;
    }
    nearbyPairingPolling = true;
    try {
      const requests = await getPendingNearbyPairings();
      const hadNoRequests = pendingNearbyPairings.length === 0;
      pendingNearbyPairings = requests;
      if (hadNoRequests && requests.length > 0) {
        themeDrawerOpen = false;
        syncSettingsOpen = true;
      }
    } catch (error) {
      actionError = commandError(error);
    } finally {
      nearbyPairingPolling = false;
    }
  }

  async function approvePhonePairing(requestId: string): Promise<void> {
    if (nearbyPairingBusy !== '') {
      return;
    }
    if (syncConnection?.requiresPassword && embeddedPassword.length < 8) {
      actionError = {
        code: 'sync_password_required',
        message: 'Enter the sync password above before approving this phone.',
        retryable: true,
      };
      return;
    }
    nearbyPairingBusy = requestId;
    actionError = null;
    try {
      if (syncConnection?.requiresPassword) {
        syncConnection = await unlockSync(embeddedPassword);
        embeddedPassword = '';
      }
      await approveNearbyPairing(requestId);
      pendingNearbyPairings = pendingNearbyPairings.filter(
        (request) => request.requestId !== requestId,
      );
      syncMessage = 'Android approved. It will finish pairing and start encrypted sync automatically.';
      showFeedback('Android paired securely');
    } catch (error) {
      actionError = commandError(error);
      await refreshNearbyPairings();
    } finally {
      nearbyPairingBusy = '';
    }
  }

  async function rejectPhonePairing(requestId: string): Promise<void> {
    if (nearbyPairingBusy !== '') {
      return;
    }
    nearbyPairingBusy = requestId;
    try {
      await rejectNearbyPairing(requestId);
      pendingNearbyPairings = pendingNearbyPairings.filter(
        (request) => request.requestId !== requestId,
      );
      showFeedback('Pairing request rejected');
    } catch (error) {
      actionError = commandError(error);
    } finally {
      nearbyPairingBusy = '';
    }
  }

  function retainEmbeddedPairing(details: EmbeddedRelayPairingDetails): void {
    embeddedPairing = details;
    if (embeddedPairingTimer !== undefined) {
      clearTimeout(embeddedPairingTimer);
    }
    embeddedPairingTimer = setTimeout(() => {
      embeddedPairing = null;
      embeddedPairingTimer = undefined;
    }, 120_000);
  }

  async function startEmbeddedRelay(): Promise<void> {
    if (embeddedPassword.length < 8) {
      return;
    }
    embeddedBusy = true;
    actionError = null;
    syncMessage = 'Starting private sync hosting on this computer…';
    try {
      const status = await enableEmbeddedRelay(embeddedPassword);
      embeddedPassword = '';
      embeddedRelay = status;
      embeddedPairing = null;
      syncMessage = 'Local sync is ready. On Android, tap Find VaultNote Desktop, then approve the matching code here.';
      await synchronize(true);
    } catch (error) {
      embeddedPassword = '';
      actionError = commandError(error);
      syncMessage = '';
      await refreshEmbeddedRelay();
    } finally {
      embeddedBusy = false;
    }
  }

  async function revealEmbeddedPairing(): Promise<void> {
    embeddedBusy = true;
    actionError = null;
    try {
      const details = await getEmbeddedRelayPairingDetails(embeddedPassword);
      embeddedPassword = '';
      embeddedRelay = details.status;
      retainEmbeddedPairing(details);
    } catch (error) {
      embeddedPassword = '';
      actionError = commandError(error);
    } finally {
      embeddedBusy = false;
    }
  }

  async function copyEmbeddedToken(): Promise<void> {
    if (embeddedPairing === null) {
      return;
    }
    try {
      await navigator.clipboard.writeText(embeddedPairing.authenticationToken);
      showFeedback('Phone pairing token copied');
    } catch {
      actionError = {
        code: 'clipboard_unavailable',
        message: 'Clipboard access is unavailable. Select and copy the token manually.',
        retryable: false,
      };
    }
  }

  async function resetEmbeddedAccess(): Promise<void> {
    if (
      embeddedPassword.length < 8 ||
      !window.confirm('Replace the phone access token? Existing phones must pair again.')
    ) {
      return;
    }
    embeddedBusy = true;
    actionError = null;
    try {
      const details = await resetEmbeddedRelayAccess(embeddedPassword);
      embeddedPassword = '';
      embeddedRelay = details.status;
      retainEmbeddedPairing(details);
      syncMessage = 'Phone access was replaced. Pair Android again with the new token.';
      await synchronize(true);
    } catch (error) {
      embeddedPassword = '';
      actionError = commandError(error);
    } finally {
      embeddedBusy = false;
    }
  }

  async function findRelays(): Promise<void> {
    relayBusy = true;
    syncMessage = 'Looking for VaultNote relays on this network…';
    try {
      discoveredRelays = await discoverRelays();
      syncMessage =
        discoveredRelays.length === 0
          ? 'No relay was discovered. You can enter its address manually.'
          : `${discoveredRelays.length} relay${discoveredRelays.length === 1 ? '' : 's'} found.`;
      if (discoveredRelays.length === 1) {
        selectRelay(discoveredRelays[0]);
      }
    } catch (error) {
      actionError = commandError(error);
      syncMessage = '';
    } finally {
      relayBusy = false;
    }
  }

  function selectRelay(relay: DiscoveredRelay): void {
    relayHostAddress = relay.hostAddress;
    relayPort = relay.port;
    relayVaultId = relay.vaultId;
    relayFingerprint = relay.certificateSha256;
    relayFingerprintConfirmed = false;
  }

  async function connectRelay(): Promise<void> {
    relayBusy = true;
    actionError = null;
    syncMessage = 'Verifying the relay and encrypted vault key…';
    try {
      syncConnection = await pairRelay({
        hostAddress: relayHostAddress.trim(),
        port: relayPort,
        certificateSha256: relayFingerprint.trim().toLowerCase(),
        authenticationToken: relayToken,
        syncPassword: relayPassword,
        expectedVaultId: relayVaultId.trim() || null,
        fingerprintConfirmed: relayFingerprintConfirmed,
      });
      relayToken = '';
      relayPassword = '';
      syncMessage = 'Relay paired. Changes remain end-to-end encrypted through the relay.';
      await synchronize(true);
    } catch (error) {
      relayToken = '';
      relayPassword = '';
      actionError = commandError(error);
      syncMessage = '';
    } finally {
      relayBusy = false;
    }
  }

  async function unlockRelayConnection(): Promise<void> {
    relayBusy = true;
    actionError = null;
    try {
      syncConnection = await unlockSync(relayPassword);
      relayPassword = '';
      syncMessage = 'LAN sync unlocked for this session.';
      await synchronize(true);
    } catch (error) {
      relayPassword = '';
      actionError = commandError(error);
    } finally {
      relayBusy = false;
    }
  }

  async function forgetRelay(): Promise<void> {
    if (!window.confirm('Disconnect this relay? Local notes and files will remain on this computer.')) {
      return;
    }
    relayBusy = true;
    try {
      syncConnection = await disconnectRelay();
      discoveredRelays = [];
      relayToken = '';
      relayPassword = '';
      relayFingerprintConfirmed = false;
      syncMessage = 'Relay disconnected. Local content was not removed.';
      await Promise.all([loadVisibleItems(false), refreshQueueStatus()]);
    } catch (error) {
      actionError = commandError(error);
    } finally {
      relayBusy = false;
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
    closeAttachmentPreview();
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
        if (
          attachmentPreview !== null &&
          !loaded.some((attachment) => attachment.id === attachmentPreview?.attachment.id)
        ) {
          closeAttachmentPreview();
        }
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
        showFeedback('File added securely');
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

  function closeAttachmentPreview(): void {
    attachmentPreviewRequest += 1;
    if (attachmentPreview !== null) {
      URL.revokeObjectURL(attachmentPreview.objectUrl);
      attachmentPreview = null;
    }
  }

  async function showAttachmentPreview(attachment: VaultAttachment): Promise<void> {
    const request = ++attachmentPreviewRequest;
    attachmentBusy = true;
    try {
      const bytes = await previewAttachment(attachment.id);
      if (
        request !== attachmentPreviewRequest ||
        componentDestroyed ||
        !attachments.some((candidate) => candidate.id === attachment.id)
      ) {
        return;
      }
      const objectUrl = URL.createObjectURL(new Blob([bytes], { type: attachment.mimeType }));
      if (attachmentPreview !== null) {
        URL.revokeObjectURL(attachmentPreview.objectUrl);
      }
      attachmentPreview = { attachment, objectUrl };
    } catch (error) {
      actionError = commandError(error);
    } finally {
      attachmentBusy = false;
    }
  }

  async function openAttachmentExternally(attachment: VaultAttachment): Promise<void> {
    attachmentBusy = true;
    try {
      await openAttachment(attachment.id);
      showFeedback('Opened in default app · temporary copy clears when VaultNote locks');
    } catch (error) {
      actionError = commandError(error);
    } finally {
      attachmentBusy = false;
    }
  }

  function showAdjacentImage(offset: -1 | 1): void {
    if (attachmentPreviewIndex < 0) return;
    const next = imageAttachments[attachmentPreviewIndex + offset];
    if (next !== undefined) {
      void showAttachmentPreview(next);
    }
  }

  function attachmentImageFailed(): void {
    actionError = {
      code: 'attachment_preview_failed',
      message: 'This image could not be decoded. Open it with your default app instead.',
      retryable: false,
    };
    closeAttachmentPreview();
  }

  async function removeAttachment(id: string): Promise<void> {
    if (selected === null) {
      return;
    }
    attachmentBusy = true;
    try {
      if (attachmentPreview?.attachment.id === id) {
        closeAttachmentPreview();
      }
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
      requestAnimationFrame(() => titleInput?.focus());
      showFeedback('New note ready');
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

  async function synchronize(interactive = true): Promise<void> {
    if (!syncConnection?.unlocked) {
      if (interactive) {
        themeDrawerOpen = false;
        syncSettingsOpen = true;
        syncMessage = syncConnection?.requiresPassword
          ? 'Enter the sync password to continue.'
          : 'Pair a LAN relay to sync with Android.';
      }
      return;
    }
    if (!(await flushEditor())) {
      return;
    }
    syncRunning = true;
    actionError = null;
    try {
      const report = await runSync();
      syncMessage =
        `Sync complete: ${report.uploadedItems} items sent, ${report.pulledChanges} changes received` +
        (report.conflictCopies > 0 ? `, ${report.conflictCopies} conflict copies preserved.` : '.');
      showFeedback('Vault synchronized');
      if (selected !== null) {
        installNote(await getNote(selected.id));
      }
      await Promise.all([
        loadVisibleItems(false),
        refreshQueueStatus(),
        refreshSyncConnection(),
      ]);
    } catch (error) {
      if (interactive) {
        actionError = commandError(error);
      }
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
      <button
        class="secondary-button"
        title="Sync now (Ctrl+Shift+S)"
        disabled={syncRunning || !authentication?.unlocked}
        onclick={() => void synchronize(true)}
      >
        {syncRunning ? 'Syncing…' : syncConnection?.configured ? 'Sync now' : 'LAN sync'}
      </button>
      {#if authentication?.unlocked}
        <button class="secondary-button" onclick={showAgenda}>Calendar</button>
        <button
          class="secondary-button"
          aria-expanded={syncSettingsOpen}
          onclick={toggleSyncSettings}
        >
          Sync settings
        </button>
        <button
          bind:this={themeButton}
          class="theme-toolbar-button"
          aria-label="Choose theme"
          aria-expanded={themeDrawerOpen}
          title="Choose theme"
          onclick={openThemeDrawer}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M19.2 15.5A8 8 0 0 1 8.5 4.8a8.1 8.1 0 1 0 10.7 10.7Z"></path>
            <circle cx="17.7" cy="5.2" r="1.1"></circle>
          </svg>
        </button>
        {#if authentication.encryptionMode === 'PASSWORD'}
          <button class="secondary-button" onclick={lockVault}>Lock</button>
        {/if}
      {/if}
    </div>
  </header>

  {#if syncSettingsOpen && authentication?.unlocked}
    <section class="security-panel" aria-label="Sync, protection, and backup settings">
      <div class="security-group vault-protection">
        {#if authentication.encryptionMode === 'PASSWORD'}
          <p><strong>Local encryption is on.</strong> Your password protects the database and files and is never recoverable.</p>
        {:else}
          <p><strong>Encryption is off.</strong> Other programs with filesystem access can read this vault.</p>
        {/if}
      </div>
      <div class="security-group relay-controls">
        <div class="section-heading">
          <div>
            <strong>Android ↔ desktop LAN sync</strong>
            <p>This computer can host encrypted sync. No separate server or relay setup is required.</p>
          </div>
          <span class:connected={embeddedRelay?.running || syncConnection?.unlocked} class="connection-badge">
            {embeddedRelay?.running
              ? 'Hosting'
              : syncConnection?.unlocked
                ? 'Connected'
              : syncConnection?.configured
                ? 'Locked'
                : 'Not paired'}
          </span>
        </div>

        <div class="embedded-host-card" class:hosting={embeddedRelay?.running}>
          {#if embeddedRelay === null}
            <p>Checking local sync hosting…</p>
          {:else if !embeddedRelay.enabled}
            <div class="embedded-host-copy">
              <strong>Sync directly with Android</strong>
              <p>Choose one sync password. VaultNote starts and advertises the private host automatically whenever the desktop app is open.</p>
            </div>
            <form
              class="embedded-host-actions"
              onsubmit={(event) => {
                event.preventDefault();
                void startEmbeddedRelay();
              }}
            >
              <label>
                <span>Sync password</span>
                <input
                  required
                  type="password"
                  minlength="8"
                  maxlength="1024"
                  autocomplete="new-password"
                  bind:value={embeddedPassword}
                />
              </label>
              <button disabled={embeddedBusy || embeddedPassword.length < 8}>
                {embeddedBusy ? 'Starting…' : 'Start phone sync'}
              </button>
            </form>
          {:else}
            <div class="embedded-host-copy">
              <strong>{embeddedRelay.running ? 'This computer is discoverable' : 'Local sync host is unavailable'}</strong>
              <p>
                {embeddedRelay.running
                  ? `Listening on ${embeddedRelay.lanAddress ?? 'all LAN interfaces'}:${embeddedRelay.port ?? 'active port'}. On Android, tap Find VaultNote Desktop. A matching code appears on both devices; approve it here. Hosting continues while this app is open, even when the vault is locked.`
                  : 'Retry starting the host, and allow VaultNote through the desktop firewall if prompted.'}
              </p>
            </div>
            {#if syncConnection?.requiresPassword}
              <label class="embedded-password">
                <span>Sync password</span>
                <input
                  type="password"
                  minlength="8"
                  maxlength="1024"
                  autocomplete="current-password"
                  bind:value={embeddedPassword}
                />
              </label>
            {/if}
            <div class="embedded-host-buttons">
              <details class="manual-phone-access">
                <summary>Manual pairing and recovery</summary>
                <p>Use this only when nearby approval is unavailable on the network.</p>
                <button
                  disabled={embeddedBusy ||
                    !embeddedRelay.running ||
                    (syncConnection?.requiresPassword === true && embeddedPassword.length < 8)}
                  onclick={() => void revealEmbeddedPairing()}
                >
                  {embeddedBusy ? 'Loading…' : 'Show manual details'}
                </button>
                <details class="reset-phone-access">
                  <summary>Replace phone access</summary>
                  <p>This disconnects previously paired phones.</p>
                  <label>
                    <span>Sync password</span>
                    <input
                      type="password"
                      minlength="8"
                      maxlength="1024"
                      autocomplete="current-password"
                      bind:value={embeddedPassword}
                    />
                  </label>
                  <button
                    class="danger-button"
                    disabled={embeddedBusy || embeddedPassword.length < 8}
                    onclick={() => void resetEmbeddedAccess()}
                  >
                    Replace token
                  </button>
                </details>
              </details>
            </div>
          {/if}
        </div>

        {#if pendingNearbyPairings.length > 0}
          <div class="nearby-pairing-requests" aria-live="assertive">
            {#each pendingNearbyPairings as request (request.requestId)}
              <article class="nearby-pairing-request">
                <div>
                  <span class="eyebrow">Nearby pairing request</span>
                  <strong>{request.deviceName}</strong>
                  <p>Check that this same code is visible on Android, then approve.</p>
                </div>
                <code class="nearby-pairing-code">{request.verificationCode}</code>
                <div class="nearby-pairing-actions">
                  <button
                    class="secondary-button"
                    disabled={nearbyPairingBusy !== ''}
                    onclick={() => void rejectPhonePairing(request.requestId)}
                  >
                    Not my phone
                  </button>
                  <button
                    disabled={nearbyPairingBusy !== '' ||
                      (syncConnection?.requiresPassword === true && embeddedPassword.length < 8)}
                    onclick={() => void approvePhonePairing(request.requestId)}
                  >
                    {nearbyPairingBusy === request.requestId ? 'Approving…' : 'Approve phone'}
                  </button>
                </div>
              </article>
            {/each}
          </div>
        {/if}

        {#if embeddedPairing !== null}
          <div class="phone-pairing-details" role="status">
            <div>
              <strong>Manual connection details</strong>
              <ol>
                <li>Open VaultNote → Sync and open manual pairing.</li>
                <li>Confirm this certificate fingerprint matches the phone.</li>
                <li>Paste the token and enter the same sync password.</li>
              </ol>
            </div>
            <label class="phone-token">
              <span>Phone pairing token · hidden again in two minutes</span>
              <input
                readonly
                spellcheck="false"
                value={embeddedPairing.authenticationToken}
                onclick={(event) => event.currentTarget.select()}
              />
            </label>
            <button onclick={() => void copyEmbeddedToken()}>Copy token</button>
            <div class="phone-fingerprint">
              <span>LAN endpoint</span>
              <code>{embeddedPairing.status.lanAddress ?? 'Automatic discovery'}:{embeddedPairing.status.port ?? 'active port'}</code>
            </div>
            <div class="phone-fingerprint">
              <span>Certificate SHA-256</span>
              <code>{embeddedPairing.status.certificateSha256}</code>
            </div>
          </div>
        {/if}

        {#if syncConnection?.configured}
          <div class="relay-summary">
            <span><strong>Vault</strong> {syncConnection.vaultId}</span>
            {#if embeddedRelay?.enabled && embeddedRelay.vaultId === syncConnection.vaultId}
              <span>
                <strong>Relay</strong> This computer · {embeddedRelay.lanAddress ?? 'all LAN interfaces'}:{embeddedRelay.port ?? 'active port'}
              </span>
            {:else}
              <span><strong>Relay</strong> {syncConnection.hostAddress}:{syncConnection.port}</span>
            {/if}
            <span class="fingerprint-line">
              <strong>Certificate</strong>
              <code>{syncConnection.certificateSha256}</code>
            </span>
            {#if syncConnection.lastSuccessAtEpochMillis !== null}
              <span><strong>Last sync</strong> {formattedDate(syncConnection.lastSuccessAtEpochMillis)}</span>
            {/if}
            {#if syncConnection.serverRevision !== null}
              <span><strong>Relay revision</strong> {syncConnection.serverRevision}</span>
            {/if}
          </div>
          {#if syncConnection.requiresPassword}
            <form
              class="inline-secret-form"
              onsubmit={(event) => {
                event.preventDefault();
                void unlockRelayConnection();
              }}
            >
              <label>
                <span>Sync password</span>
                <input
                  type="password"
                  minlength="8"
                  maxlength="1024"
                  autocomplete="current-password"
                  bind:value={relayPassword}
                />
              </label>
              <button disabled={relayBusy || relayPassword.length < 8}>
                {relayBusy ? 'Unlocking…' : 'Unlock sync'}
              </button>
            </form>
          {:else if syncConnection.unlocked}
            <div class="relay-actions">
              <button disabled={syncRunning} onclick={() => void synchronize(true)}>
                {syncRunning ? 'Syncing…' : 'Sync now'}
              </button>
              {#if !embeddedRelay?.enabled}
                <button class="danger-button" disabled={relayBusy} onclick={forgetRelay}>
                  Disconnect
                </button>
              {/if}
            </div>
          {:else}
            <p role="alert">The saved pairing could not be unlocked. Pair this relay again.</p>
            <button class="danger-button" disabled={relayBusy} onclick={forgetRelay}>
              Clear saved pairing
            </button>
          {/if}
        {:else}
          <details class="advanced-relay">
            <summary>Advanced: connect through another host</summary>
            <div class="relay-discovery">
              <button class="secondary-button" disabled={relayBusy} onclick={findRelays}>
                {relayBusy ? 'Discovering…' : 'Find relays automatically'}
              </button>
              {#if discoveredRelays.length > 0}
                <div class="relay-results" aria-label="Discovered relays">
                  {#each discoveredRelays as relay (`${relay.vaultId}-${relay.hostAddress}-${relay.port}`)}
                    <button type="button" onclick={() => selectRelay(relay)}>
                      <strong>{relay.instanceName}</strong>
                      <span>{relay.hostAddress}:{relay.port} · {relay.vaultId}</span>
                    </button>
                  {/each}
                </div>
              {/if}
            </div>
            <form
              class="relay-pair-form"
              onsubmit={(event) => {
                event.preventDefault();
                void connectRelay();
              }}
            >
              <label>
                <span>Relay address</span>
                <input
                  required
                  maxlength="253"
                  autocomplete="off"
                  placeholder="192.168.1.20"
                  bind:value={relayHostAddress}
                />
              </label>
              <label>
                <span>Port</span>
                <input required type="number" min="1" max="65535" bind:value={relayPort} />
              </label>
              <label>
                <span>Vault ID</span>
                <input maxlength="128" autocomplete="off" bind:value={relayVaultId} />
              </label>
              <label class="wide-field">
                <span>Certificate SHA-256 fingerprint</span>
                <input
                  required
                  minlength="64"
                  maxlength="64"
                  spellcheck="false"
                  autocomplete="off"
                  bind:value={relayFingerprint}
                />
              </label>
              <label class="wide-field">
                <span>Relay token</span>
                <input
                  required
                  type="password"
                  maxlength="128"
                  autocomplete="off"
                  bind:value={relayToken}
                />
              </label>
              <label class="wide-field">
                <span>Sync password</span>
                <input
                  required
                  type="password"
                  minlength="8"
                  maxlength="1024"
                  autocomplete="new-password"
                  bind:value={relayPassword}
                />
              </label>
              <label class="fingerprint-confirm wide-field">
                <input type="checkbox" bind:checked={relayFingerprintConfirmed} />
                <span>I compared and trust this relay certificate fingerprint.</span>
              </label>
              <button
                class="wide-field"
                disabled={relayBusy ||
                  relayHostAddress.trim().length === 0 ||
                  relayFingerprint.trim().length !== 64 ||
                  relayToken.length === 0 ||
                  relayPassword.length < 8 ||
                  !relayFingerprintConfirmed}
              >
                {relayBusy ? 'Verifying…' : 'Pair securely'}
              </button>
            </form>
          </details>
        {/if}
        {#if syncMessage}<p class="sync-message" role="status">{syncMessage}</p>{/if}
      </div>
      <div class="security-group backup-controls">
        <div class="backup-heading">
          <strong>Portable backups</strong>
          <span>Encrypted is recommended. You can also drop a .vnb anywhere.</span>
        </div>
        <div class="backup-actions">
          <label>
            <span>Backup password (12–128 characters)</span>
            <input type="password" minlength="12" maxlength="128" autocomplete="new-password" bind:value={backupPassword} />
          </label>
          <button disabled={backupBusy || backupPassword.length < 12} onclick={createBackup}>Export encrypted</button>
          <button disabled={backupBusy || backupPassword.length < 12} onclick={importBackup}>Restore encrypted</button>
          <button class="warning-button" disabled={backupBusy} onclick={createPlaintextBackup}>Export readable</button>
          <button class="warning-button" disabled={backupBusy} onclick={importPlaintextBackup}>Restore readable</button>
        </div>
        {#if backupMessage}<p role="status">{backupMessage}</p>{/if}
      </div>
    </section>
  {/if}

  {#if themeDrawerOpen && authentication?.unlocked}
    <ThemeDrawer
      selected={themePreference}
      onSelect={applyTheme}
      onClose={() => closeThemeDrawer()}
    />
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
      <button class="new-note-button" title="New note (Ctrl+N)" onclick={newNote}>
        <span>+ New note</span><kbd>Ctrl N</kbd>
      </button>

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
          bind:this={searchInput}
          type="search"
          placeholder="Search notes  ·  Ctrl K"
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
          <span
            class:save-error={autosaveStatus === 'error'}
            class:saving={autosaveStatus === 'saving'}
            class="save-status"
          >
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
            bind:this={titleInput}
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
          <AttachmentPanel
            {attachments}
            busy={attachmentBusy}
            readonly={selected.deletedAtEpochMillis !== null}
            encryptionMode={authentication?.encryptionMode ?? 'UNCONFIGURED'}
            onAdd={() => void addAttachment()}
            onPreview={(attachment) => void showAttachmentPreview(attachment)}
            onOpen={(attachment) => void openAttachmentExternally(attachment)}
            onSave={(id) => void saveAttachment(id)}
            onDelete={(id) => void removeAttachment(id)}
          />
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
      {#if attachmentPreview !== null}
        <AttachmentPreview
          attachment={attachmentPreview.attachment}
          source={attachmentPreview.objectUrl}
          busy={attachmentBusy}
          hasPrevious={attachmentPreviewIndex > 0}
          hasNext={attachmentPreviewIndex >= 0 && attachmentPreviewIndex < imageAttachments.length - 1}
          onClose={closeAttachmentPreview}
          onPrevious={() => showAdjacentImage(-1)}
          onNext={() => showAdjacentImage(1)}
          onOpen={() => {
            if (attachmentPreview !== null) void openAttachmentExternally(attachmentPreview.attachment);
          }}
          onSave={() => {
            if (attachmentPreview !== null) void saveAttachment(attachmentPreview.attachment.id);
          }}
          onImageError={attachmentImageFailed}
        />
      {/if}
    </section>
  </main>

  {#if dropActive}
    <div class="drop-overlay" aria-hidden="true">
      <div>
        <span class="drop-glyph">↓</span>
        <strong>Drop to add files</strong>
        <p>A .vnb opens secure restore; other files attach to this note.</p>
      </div>
    </div>
  {/if}

  {#if feedbackMessage}
    <div class="feedback-toast" role="status">
      <span aria-hidden="true">✓</span>
      {feedbackMessage}
    </div>
  {/if}

  {#if droppedBackup !== null}
    <div
      class="dialog-backdrop backup-dialog-backdrop"
      role="presentation"
      onclick={(event) => {
        if (event.currentTarget === event.target && !backupBusy) closeDroppedBackup();
      }}
    >
      <div
        class="backup-drop-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="backup-drop-title"
      >
        <header>
          <div class="backup-dialog-icon" class:readable={droppedBackup.inspection.protection === 'PLAINTEXT'}>
            {droppedBackup.inspection.protection === 'ENCRYPTED' ? '◇' : '!'}
          </div>
          <div>
            <p class="eyebrow">
              {droppedBackup.inspection.protection === 'ENCRYPTED'
                ? 'Encrypted VaultNote backup'
                : 'Readable VaultNote backup'}
            </p>
            <h2 id="backup-drop-title">Restore this vault?</h2>
          </div>
          <button
            class="icon-button"
            aria-label="Close backup restore"
            disabled={backupBusy}
            onclick={closeDroppedBackup}
          >×</button>
        </header>
        <p class="backup-file-name" title={droppedBackup.path}>
          {filenameFromPath(droppedBackup.path)}
        </p>
        <p>
          Created {formattedDate(droppedBackup.inspection.createdAtEpochMillis)}. Restore is validated
          in a staging area and imported as new local copies.
        </p>
        {#if droppedBackup.inspection.protection === 'ENCRYPTED'}
          <label>
            <span>Backup password</span>
            <input
              type="password"
              minlength="12"
              maxlength="128"
              autocomplete="current-password"
              bind:value={droppedBackupPassword}
            />
          </label>
        {:else}
          <div class="plaintext-warning">
            <strong>This backup is not encrypted.</strong>
            <span>Anyone with the file can read its notes and attachments.</span>
          </div>
          <label class="plaintext-confirm">
            <input type="checkbox" bind:checked={droppedPlaintextConfirmed} />
            <span>I trust this readable backup and want to restore it.</span>
          </label>
        {/if}
        <footer>
          <button class="secondary-action" disabled={backupBusy} onclick={closeDroppedBackup}>Cancel</button>
          <button
            disabled={backupBusy ||
              (droppedBackup.inspection.protection === 'ENCRYPTED'
                ? droppedBackupPassword.length < 12
                : !droppedPlaintextConfirmed)}
            onclick={restoreDroppedBackup}
          >
            {backupBusy ? 'Validating…' : 'Validate and restore'}
          </button>
        </footer>
      </div>
    </div>
  {/if}

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
