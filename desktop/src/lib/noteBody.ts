import type { NoteBlock, NoteBlockType, NoteBodyDocument } from './models';

export function newBlock(type: NoteBlockType = 'PARAGRAPH', text = '') {
  return {
    id: crypto.randomUUID(),
    type,
    text,
    checked: false,
  } as const;
}

export function documentFromPlainText(body: string): NoteBodyDocument {
  return {
    version: 1,
    blocks: [newBlock('PARAGRAPH', body)],
  };
}

export function normalizeNoteBodyDocument(document: NoteBodyDocument): NoteBodyDocument {
  if (document.blocks.length === 0) {
    return { version: 1, blocks: [newBlock()] };
  }

  const blocks: NoteBlock[] = [];
  for (const block of document.blocks) {
    const previous = blocks.at(-1);
    if (previous?.type === 'PARAGRAPH' && block.type === 'PARAGRAPH') {
      blocks[blocks.length - 1] = {
        ...previous,
        text: `${previous.text}\n${block.text}`,
      };
    } else {
      blocks.push(block);
    }
  }

  return blocks.length === document.blocks.length
    ? document
    : { version: 1, blocks };
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
