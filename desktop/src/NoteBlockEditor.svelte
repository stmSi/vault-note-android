<script lang="ts">
  import type { NoteBlock, NoteBlockType, NoteBodyDocument } from './lib/models';
  import { newBlock, normalizeNoteBodyDocument } from './lib/noteBody';

  export let document: NoteBodyDocument;
  export let readonly = false;
  export let dateCount = 0;
  export let onDocumentChange: (document: NoteBodyDocument) => void;
  export let onDates: () => void;

  let root: HTMLDivElement;

  function replaceBlocks(blocks: NoteBlock[]): void {
    onDocumentChange(normalizeNoteBodyDocument({
      version: 1,
      blocks: blocks.length === 0 ? [newBlock()] : blocks,
    }));
  }

  function updateBlock(index: number, patch: Partial<NoteBlock>): void {
    replaceBlocks(
      document.blocks.map((block, blockIndex) =>
        blockIndex === index ? { ...block, ...patch } : block,
      ),
    );
  }

  function addBlock(type: NoteBlockType): void {
    replaceBlocks([...document.blocks, newBlock(type)]);
  }

  function splitBlock(index: number, target: HTMLTextAreaElement): void {
    const block = document.blocks[index];
    const position = target.selectionStart ?? block.text.length;
    const before = block.text.slice(0, position);
    const after = block.text.slice(position);
    const nextType =
      block.type === 'CHECKLIST_ITEM' && before.length === 0 && after.length === 0
        ? 'PARAGRAPH'
        : block.type;
    const blocks = [...document.blocks];
    blocks.splice(
      index,
      1,
      { ...block, text: before },
      newBlock(nextType, after),
    );
    replaceBlocks(blocks);
    requestAnimationFrame(() => {
      const inputs = root.querySelectorAll<HTMLTextAreaElement>('[data-block-input]');
      inputs[index + 1]?.focus();
      inputs[index + 1]?.setSelectionRange(0, 0);
    });
  }

  function handleKeydown(
    event: KeyboardEvent,
    index: number,
    target: HTMLTextAreaElement,
  ): void {
    if (
      event.key === 'Enter' &&
      !event.shiftKey &&
      document.blocks[index]?.type === 'CHECKLIST_ITEM'
    ) {
      event.preventDefault();
      splitBlock(index, target);
      return;
    }
    if (
      event.key === 'Backspace' &&
      target.value.length === 0 &&
      document.blocks.length > 1
    ) {
      event.preventDefault();
      const blocks = document.blocks.filter((_, blockIndex) => blockIndex !== index);
      replaceBlocks(blocks);
      requestAnimationFrame(() => {
        const inputs = root.querySelectorAll<HTMLTextAreaElement>('[data-block-input]');
        const previous = inputs[Math.max(0, index - 1)];
        previous?.focus();
        previous?.setSelectionRange(previous.value.length, previous.value.length);
      });
    }
  }

</script>

<div class="block-editor" bind:this={root}>
  <div class="blocks" aria-label="Note body">
    {#each document.blocks as block, index (block.id)}
      <div class:checklist={block.type === 'CHECKLIST_ITEM'} class="block-row">
        {#if block.type === 'CHECKLIST_ITEM'}
          <input
            class="block-check"
            type="checkbox"
            aria-label="Toggle checklist item"
            checked={block.checked}
            disabled={readonly}
            onchange={(event) =>
              updateBlock(index, { checked: event.currentTarget.checked })}
          />
        {/if}
        <textarea
          data-block-input
          aria-label={block.type === 'CHECKLIST_ITEM' ? 'Checklist item' : 'Text block'}
          placeholder={block.type === 'CHECKLIST_ITEM' ? 'Checklist item' : 'Start writing…'}
          rows={Math.max(1, block.text.split('\n').length)}
          maxlength="100000"
          value={block.text}
          {readonly}
          oninput={(event) => updateBlock(index, { text: event.currentTarget.value })}
          onkeydown={(event) => handleKeydown(event, index, event.currentTarget)}
        ></textarea>
      </div>
    {/each}
  </div>

  {#if !readonly}
    <div class="block-toolbar" aria-label="Note actions">
      <button onclick={() => addBlock('PARAGRAPH')}>Text</button>
      <button onclick={() => addBlock('CHECKLIST_ITEM')}>☐ Checklist</button>
      <button onclick={onDates}>◷ Dates{dateCount > 0 ? ` ${dateCount}` : ''}</button>
    </div>
  {/if}
</div>

<style>
  .block-editor {
    display: grid;
    grid-template-rows: minmax(0, 1fr) auto;
    min-height: 0;
  }

  .blocks {
    min-height: 0;
    padding: 2px 2px 10px;
    overflow: auto;
  }

  .block-row {
    display: flex;
    align-items: flex-start;
    gap: 6px;
    min-height: 28px;
  }

  .block-row textarea {
    width: 100%;
    min-height: 28px;
    padding: 2px;
    overflow: hidden;
    line-height: 1.45;
    color: var(--vn-text);
    resize: none;
    background: transparent;
    border: 0;
  }

  .block-row textarea:focus {
    outline: none;
  }

  .block-check {
    width: 18px;
    height: 18px;
    margin: 4px 0 0;
    accent-color: var(--vn-accent);
  }

  .checklist textarea {
    color: var(--vn-text);
  }

  .checklist:has(.block-check:checked) textarea {
    color: var(--vn-text-muted);
    text-decoration: line-through;
  }

  .block-toolbar {
    display: flex;
    gap: 6px;
    padding: 7px 8px;
    overflow-x: auto;
    background: var(--vn-surface-muted);
    border: 1px solid var(--vn-border);
    border-radius: 9px;
  }

  .block-toolbar button {
    flex: 0 0 auto;
    min-height: 34px;
    padding: 5px 10px;
    color: var(--vn-control-text);
    background: var(--vn-control-background);
    border-color: var(--vn-border);
  }
</style>
