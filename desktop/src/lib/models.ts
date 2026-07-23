export type VaultSection = 'active' | 'archived' | 'trash';
export type SyncStatus =
  | 'LOCAL_ONLY'
  | 'PENDING'
  | 'SYNCING'
  | 'SYNCED'
  | 'CONFLICT'
  | 'FAILED';

export interface VaultItemSummary {
  id: string;
  itemType: 'NOTE';
  color: 'DEFAULT' | 'RED' | 'ORANGE' | 'YELLOW' | 'GREEN' | 'BLUE' | 'PURPLE';
  title: string;
  bodyPreview: string;
  isPinned: boolean;
  isFavorite: boolean;
  isArchived: boolean;
  createdAtEpochMillis: number;
  updatedAtEpochMillis: number;
  syncStatus: SyncStatus;
  deletedAtEpochMillis: number | null;
  nextDatedEntryAtEpochMillis: number | null;
  hasOverdueEntry: boolean;
}

export type NoteBlockType = 'PARAGRAPH' | 'CHECKLIST_ITEM';

export interface NoteBlock {
  id: string;
  type: NoteBlockType;
  text: string;
  checked: boolean;
}

export interface NoteBodyDocument {
  version: 1;
  blocks: NoteBlock[];
}

export type DatedEntryType = 'REMINDER' | 'DEADLINE' | 'IMPORTANT_DATE' | 'RENEWAL';
export type RecurrenceUnit = 'DAY' | 'WEEK' | 'MONTH' | 'YEAR';

export interface DatedEntryAlert {
  id: string;
  leadTimeMinutes: number;
  snoozedUntilEpochMillis: number | null;
}

export interface DatedEntry {
  id: string;
  itemId: string;
  type: DatedEntryType;
  label: string;
  occurrenceAtEpochMillis: number;
  isAllDay: boolean;
  timeZoneId: string;
  recurrenceUnit: RecurrenceUnit | null;
  recurrenceInterval: number | null;
  completedAtEpochMillis: number | null;
  createdAtEpochMillis: number;
  updatedAtEpochMillis: number;
  alerts: DatedEntryAlert[];
}

export interface DatedEntryDraft {
  id: string | null;
  type: DatedEntryType;
  label: string;
  occurrenceAtEpochMillis: number;
  isAllDay: boolean;
  timeZoneId: string;
  recurrenceUnit: RecurrenceUnit | null;
  recurrenceInterval: number | null;
  alertLeadTimesMinutes: number[];
}

export interface AgendaEntry {
  entry: DatedEntry;
  noteTitle: string;
  isArchived: boolean;
}

export interface ScheduledAlert {
  notificationId: number;
  alertId: string;
  entryId: string;
  itemId: string;
  triggerAtEpochMillis: number;
}

export interface VaultNote {
  id: string;
  title: string;
  body: string;
  color: VaultItemSummary['color'];
  isPinned: boolean;
  isFavorite: boolean;
  isArchived: boolean;
  createdAtEpochMillis: number;
  updatedAtEpochMillis: number;
  localRevision: number;
  remoteRevision: number | null;
  lastSyncedRevision: number | null;
  syncStatus: SyncStatus;
  deletedAtEpochMillis: number | null;
  bodyDocument: NoteBodyDocument | null;
  datedEntries: DatedEntry[];
}

export interface SearchResult {
  item: VaultItemSummary;
  snippet: string;
}

export interface SyncQueueStatus {
  pendingCount: number;
  runningCount: number;
  retryCount: number;
  failedCount: number;
}

export interface SyncReport {
  processedCount: number;
  completedCount: number;
}

export interface AuthStatus {
  setupRequired: boolean;
  unlocked: boolean;
  encryptionMode: 'UNCONFIGURED' | 'PASSWORD' | 'UNENCRYPTED';
}

export interface VaultAttachment {
  id: string;
  parentItemId: string;
  displayName: string;
  mimeType: string;
  fileSize: number;
  sha256: string;
  createdAtEpochMillis: number;
}

export interface BackupSummary {
  itemCount: number;
  attachmentCount: number;
  createdAtEpochMillis: number;
}

export interface RestoreSummary {
  restoredItemCount: number;
  restoredAttachmentCount: number;
}

export interface AppCommandError {
  code: string;
  message: string;
  retryable: boolean;
}
