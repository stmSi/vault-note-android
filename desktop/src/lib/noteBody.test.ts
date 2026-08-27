import { describe, expect, it, vi } from 'vitest';
import {
  derivePlainText,
  documentFromPlainText,
  normalizeNoteBodyDocument,
} from './noteBody';

describe('note body documents', () => {
  it('keeps legacy text in one multiline paragraph', () => {
    vi.spyOn(crypto, 'randomUUID')
      .mockReturnValueOnce('00000000-0000-0000-0000-000000000001');
    const document = documentFromPlainText('First\nSecond');

    expect(document.blocks).toHaveLength(1);
    expect(document.blocks[0]?.text).toBe('First\nSecond');
    expect(derivePlainText(document)).toBe('First\nSecond');
  });

  it('merges adjacent legacy paragraphs without crossing checklist rows', () => {
    const document = normalizeNoteBodyDocument({
      version: 1,
      blocks: [
        { id: 'first', type: 'PARAGRAPH', text: 'First', checked: false },
        { id: 'second', type: 'PARAGRAPH', text: 'Second', checked: false },
        { id: 'check', type: 'CHECKLIST_ITEM', text: 'Pack', checked: true },
        { id: 'third', type: 'PARAGRAPH', text: 'Third', checked: false },
        { id: 'fourth', type: 'PARAGRAPH', text: 'Fourth', checked: false },
      ],
    });

    expect(document.blocks).toEqual([
      { id: 'first', type: 'PARAGRAPH', text: 'First\nSecond', checked: false },
      { id: 'check', type: 'CHECKLIST_ITEM', text: 'Pack', checked: true },
      { id: 'third', type: 'PARAGRAPH', text: 'Third\nFourth', checked: false },
    ]);
    expect(derivePlainText(document)).toBe('First\nSecond\n[x] Pack\nThird\nFourth');
  });
});
