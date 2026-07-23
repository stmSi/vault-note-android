import type { NoteBlockType, NoteBodyDocument } from './models';

export function newBlock(type: NoteBlockType = 'PARAGRAPH', text = '') {
  return {
    id: crypto.randomUUID(),
    type,
    text,
    checked: false,
  } as const;
}

export function documentFromPlainText(body: string): NoteBodyDocument {
  const lines = body.split('\n');
  return {
    version: 1,
    blocks: (lines.length === 0 ? [''] : lines).map((line) => newBlock('PARAGRAPH', line)),
  };
}

export function derivePlainText(document: NoteBodyDocument): string {
  return document.blocks
    .map((block) => {
      if (block.type === 'CHECKLIST_ITEM') {
        return `${block.checked ? '[x]' : '[ ]'} ${block.text}`;
      }
      return block.text;
    })
    .join('\n');
}
