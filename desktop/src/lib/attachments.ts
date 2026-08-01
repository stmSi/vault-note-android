import type { VaultAttachment } from './models';

const PREVIEWABLE_IMAGE_TYPES = new Set([
  'image/jpeg',
  'image/png',
  'image/gif',
  'image/webp',
]);

export function isPreviewableImage(attachment: VaultAttachment): boolean {
  return PREVIEWABLE_IMAGE_TYPES.has(attachment.mimeType);
}

export function previewableImages(attachments: VaultAttachment[]): VaultAttachment[] {
  return attachments.filter(isPreviewableImage);
}

export function attachmentKind(mimeType: string): string {
  if (PREVIEWABLE_IMAGE_TYPES.has(mimeType)) return 'IMG';
  if (mimeType === 'application/pdf') return 'PDF';
  if (mimeType.startsWith('text/')) return 'TXT';
  if (mimeType.includes('word') || mimeType === 'application/msword') return 'DOC';
  if (mimeType.includes('sheet') || mimeType === 'application/vnd.ms-excel') return 'XLS';
  if (mimeType.includes('presentation') || mimeType === 'application/vnd.ms-powerpoint') {
    return 'PPT';
  }
  if (mimeType.startsWith('audio/')) return 'AUD';
  if (mimeType.startsWith('video/')) return 'VID';
  if (mimeType === 'application/zip') return 'ZIP';
  return 'FILE';
}

export function formattedFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}
