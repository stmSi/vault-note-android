import { invoke } from '@tauri-apps/api/core';
import type {
  AppCommandError,
  AuthStatus,
  BackupSummary,
  SearchResult,
  SyncQueueStatus,
  SyncReport,
  RestoreSummary,
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

export function runFakeSync(): Promise<SyncReport> {
  return invoke('run_fake_sync');
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
