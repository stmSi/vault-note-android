import { describe, expect, it, vi } from 'vitest';
import { derivePlainText, documentFromPlainText } from './noteBody';

describe('note body documents', () => {
  it('converts legacy text and derives searchable checklist markers', () => {
    vi.spyOn(crypto, 'randomUUID')
      .mockReturnValueOnce('00000000-0000-0000-0000-000000000001')
      .mockReturnValueOnce('00000000-0000-0000-0000-000000000002');
    const document = documentFromPlainText('First\nSecond');
    document.blocks[1] = {
      ...document.blocks[1],
      type: 'CHECKLIST_ITEM',
      checked: true,
    };

    expect(derivePlainText(document)).toBe('First\n[x] Second');
  });
});
