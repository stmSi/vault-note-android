import { describe, expect, it } from 'vitest';
import type { VaultAttachment } from './models';
import {
  attachmentKind,
  formattedFileSize,
  isPreviewableImage,
  previewableImages,
} from './attachments';

function attachment(id: string, mimeType: string): VaultAttachment {
  return {
    id,
    parentItemId: 'parent',
    displayName: `${id}.bin`,
    mimeType,
    fileSize: 1,
    sha256: '0'.repeat(64),
    createdAtEpochMillis: 1,
  };
}

describe('attachment presentation', () => {
  it('previews only passive raster image formats', () => {
    const files = [
      attachment('jpeg', 'image/jpeg'),
      attachment('png', 'image/png'),
      attachment('gif', 'image/gif'),
      attachment('webp', 'image/webp'),
      attachment('svg', 'image/svg+xml'),
      attachment('pdf', 'application/pdf'),
    ];

    expect(previewableImages(files).map((file) => file.id)).toEqual([
      'jpeg',
      'png',
      'gif',
      'webp',
    ]);
    expect(isPreviewableImage(files[4])).toBe(false);
  });

  it('uses recognizable labels and compact file sizes', () => {
    expect(attachmentKind('application/pdf')).toBe('PDF');
    expect(attachmentKind('application/vnd.openxmlformats-officedocument.wordprocessingml.document')).toBe('DOC');
    expect(attachmentKind('application/octet-stream')).toBe('FILE');
    expect(formattedFileSize(512)).toBe('512 B');
    expect(formattedFileSize(1536)).toBe('1.5 KiB');
  });
});
