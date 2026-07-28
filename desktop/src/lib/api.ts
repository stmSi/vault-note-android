import { invoke } from '@tauri-apps/api/core';
import type {
  AppCommandError,
  AgendaEntry,
  AuthStatus,
  BackupInspection,
  BackupSummary,
  DiscoveredRelay,
  EmbeddedRelayPairingDetails,
  EmbeddedRelayStatus,
  PairRelayInput,
  PendingNearbyPairing,
  SearchResult,
  SyncConnectionStatus,
  SyncQueueStatus,
  SyncRunReport,
  RestoreSummary,
  DatedEntryDraft,
  NoteBodyDocument,
  ScheduledAlert,
  VaultAttachment,
  VaultItemSummary,
  VaultNote,
  VaultSection,
} from './models';

const DEFAULT_LIMIT = 100;

export function listItems(section: VaultSection): Promise<VaultItemSummary[]> {
  return invoke('list_items', { request: { section, limit: DEFAULT_LIMIT } });
}

export function getNote(id: string): Promise<VaultNote> {
  return invoke('get_note', { request: { id } });
}

export function createNote(): Promise<VaultNote> {
  return invoke('create_note');
}

export function saveNote(id: string, title: string, body: string): Promise<VaultNote> {
  return invoke('save_note', { request: { id, title, body } });
}

export function saveStructuredNote(
  id: string,
  title: string,
  bodyDocument: NoteBodyDocument,
): Promise<VaultNote> {
  return invoke('save_structured_note', { request: { id, title, bodyDocument } });
}

export function saveDatedEntry(
  itemId: string,
  draft: DatedEntryDraft,
): Promise<VaultNote> {
  return invoke('save_dated_entry', { request: { itemId, draft } });
}

export function deleteDatedEntry(id: string): Promise<void> {
  return invoke('delete_dated_entry', { request: { id } });
}

export function completeDatedEntry(id: string): Promise<void> {
  return invoke('complete_dated_entry', { request: { id } });
}

export function snoozeDatedEntry(id: string, minutes: number): Promise<void> {
  return invoke('snooze_dated_entry', { request: { id, minutes } });
}

export function listAgenda(includeCompleted: boolean): Promise<AgendaEntry[]> {
  return invoke('list_agenda', {
    request: { includeCompleted, limit: DEFAULT_LIMIT },
  });
}

export function listScheduledAlerts(): Promise<ScheduledAlert[]> {
  return invoke('scheduled_alerts');
}

export function exportCalendarEntry(id: string): Promise<boolean> {
  return invoke('export_calendar_entry', { request: { id } });
}

export function setPinned(id: string, value: boolean): Promise<VaultNote> {
  return invoke('set_pinned', { request: { id, value } });
}

export function setFavorite(id: string, value: boolean): Promise<VaultNote> {
  return invoke('set_favorite', { request: { id, value } });
}

export function setArchived(id: string, value: boolean): Promise<VaultNote> {
  return invoke('set_archived', { request: { id, value } });
}

export function moveToTrash(id: string): Promise<VaultNote> {
  return invoke('move_to_trash', { request: { id } });
}

export function restore(id: string): Promise<VaultNote> {
  return invoke('restore', { request: { id } });
}

export function searchNotes(query: string): Promise<SearchResult[]> {
  return invoke('search_notes', { request: { query, limit: DEFAULT_LIMIT } });
}

export function getSyncQueueStatus(): Promise<SyncQueueStatus> {
  return invoke('sync_queue_status');
}

export function getSyncConnectionStatus(): Promise<SyncConnectionStatus> {
  return invoke('sync_connection_status');
}

export function discoverRelays(): Promise<DiscoveredRelay[]> {
  return invoke('discover_relays');
}

export function pairRelay(request: PairRelayInput): Promise<SyncConnectionStatus> {
  return invoke('pair_relay', { request });
}

export function unlockSync(password: string): Promise<SyncConnectionStatus> {
  return invoke('unlock_sync', { request: { password } });
}

export function disconnectRelay(): Promise<SyncConnectionStatus> {
  return invoke('disconnect_relay');
}

export function runSync(): Promise<SyncRunReport> {
  return invoke('run_sync');
}

export function getEmbeddedRelayStatus(): Promise<EmbeddedRelayStatus> {
  return invoke('embedded_relay_status');
}

export function enableEmbeddedRelay(
  password: string,
): Promise<EmbeddedRelayStatus> {
  return invoke('enable_embedded_relay', { request: { password } });
}

export function getEmbeddedRelayPairingDetails(
  password: string,
): Promise<EmbeddedRelayPairingDetails> {
  return invoke('embedded_relay_pairing_details', { request: { password } });
}

export function resetEmbeddedRelayAccess(
  password: string,
): Promise<EmbeddedRelayPairingDetails> {
  return invoke('reset_embedded_relay_access', { request: { password } });
}

export function getPendingNearbyPairings(): Promise<PendingNearbyPairing[]> {
  return invoke('pending_nearby_pairings');
}

export function approveNearbyPairing(requestId: string): Promise<void> {
  return invoke('approve_nearby_pairing', { request: { requestId } });
}

export function rejectNearbyPairing(requestId: string): Promise<void> {
  return invoke('reject_nearby_pairing', { request: { requestId } });
}

export function getAuthStatus(): Promise<AuthStatus> {
  return invoke('auth_status');
}

export function unlock(password: string): Promise<AuthStatus> {
  return invoke('unlock', { request: { password } });
}

export function initializeVault(password: string): Promise<AuthStatus> {
  return invoke('initialize_vault', { request: { password } });
}

export function initializeUnencryptedVault(): Promise<AuthStatus> {
  return invoke('initialize_unencrypted_vault');
}

export function lock(): Promise<AuthStatus> {
  return invoke('lock');
}

export function listAttachments(id: string): Promise<VaultAttachment[]> {
  return invoke('list_attachments', { request: { id } });
}

export function importAttachment(id: string): Promise<VaultAttachment | null> {
  return invoke('import_attachment', { request: { id } });
}

export function importAttachmentPath(id: string, path: string): Promise<VaultAttachment> {
  return invoke('import_attachment_path', { request: { id, path } });
}

export function exportAttachment(id: string): Promise<boolean> {
  return invoke('export_attachment', { request: { id } });
}

export function deleteAttachment(id: string): Promise<void> {
  return invoke('delete_attachment', { request: { id } });
}

export function exportBackup(password: string): Promise<BackupSummary | null> {
  return invoke('export_backup', { request: { password } });
}

export function restoreBackup(password: string): Promise<RestoreSummary | null> {
  return invoke('restore_backup', { request: { password } });
}

export function exportPlaintextBackup(): Promise<BackupSummary | null> {
  return invoke('export_plaintext_backup');
}

export function restorePlaintextBackup(): Promise<RestoreSummary | null> {
  return invoke('restore_plaintext_backup');
}

export function inspectBackupPath(path: string): Promise<BackupInspection> {
  return invoke('inspect_backup_path', { request: { path } });
}

export function restoreBackupPath(
  path: string,
  password: string | null,
  plaintextConfirmed: boolean,
): Promise<RestoreSummary> {
  return invoke('restore_backup_path', {
    request: { path, password, plaintextConfirmed },
  });
}

export function commandError(error: unknown): AppCommandError {
  if (
    typeof error === 'object' &&
    error !== null &&
    'code' in error &&
    'message' in error &&
    'retryable' in error &&
    typeof error.code === 'string' &&
    typeof error.message === 'string' &&
    typeof error.retryable === 'boolean'
  ) {
    return {
      code: error.code,
      message: error.message,
      retryable: error.retryable,
    };
  }
  return {
    code: 'unexpected_error',
    message: 'VaultNote could not complete the operation.',
    retryable: true,
  };
}
