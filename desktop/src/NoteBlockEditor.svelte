<script lang="ts">
  import type { NoteBlock, NoteBlockType, NoteBodyDocument } from './lib/models';
  import { newBlock, normalizeNoteBodyDocument } from './lib/noteBody';

  export let document: NoteBodyDocument;
  export let readonly = false;
  export let dateCount = 0;
  export let onDocumentChange: (document: NoteBodyDocument) => void;
  export let onDates: () => void;

  let root: HTMLDivElement;
  let resetDocument: NoteBodyDocument | null = null;
  $: packingItems = document.blocks.filter((block) => block.type === 'CHECKLIST_ITEM' && block.text.trim());
  $: packedCount = packingItems.filter((block) => block.checked).length;

  function replaceBlocks(blocks: NoteBlock[]): void {
    onDocumentChange(normalizeNoteBodyDocument({
      ...document,
      blocks: blocks.length === 0 && !document.packingList ? [newBlock()] : blocks,
    }));
  }

  function updateBlock(index: number, patch: Partial<NoteBlock>): void {
    if (readonly || document.readOnly || document.blocks[index]?.readOnly) return;
    replaceBlocks(
      document.blocks.map((block, blockIndex) =>
        blockIndex === index ? { ...block, ...patch } : block,
      ),
    );
  }

  function addBlock(type: NoteBlockType): void {
    if (readonly || document.readOnly) return;
    replaceBlocks([...document.blocks, newBlock(type)]);
    requestAnimationFrame(() => {
      const inputs = root.querySelectorAll<HTMLTextAreaElement>('[data-block-input]');
      inputs[inputs.length - 1]?.focus();
    });
  }

  function removeItem(index: number): void {
    if (readonly || document.readOnly || document.blocks[index]?.readOnly) return;
    replaceBlocks(document.blocks.filter((_, blockIndex) => blockIndex !== index));
  }

  function resetChecks(): void {
    if (readonly || document.readOnly) return;
    replaceBlocks(document.blocks.map((block) => block.readOnly ? block : { ...block, checked: false }));
    resetDocument = null;
  }

  function splitBlock(index: number, target: HTMLTextAreaElement): void {
    const block = document.blocks[index];
    const position = target.selectionStart ?? block.text.length;
    const before = block.text.slice(0, position);
    const after = block.text.slice(position);
    const nextType =
      !document.packingList && block.type === 'CHECKLIST_ITEM' && before.length === 0 && after.length === 0
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
    if (readonly || document.readOnly || document.blocks[index]?.readOnly) return;
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

<div class="block-editor" class:packing={!!document.packingList} bind:this={root}>
{#if document.packingList}
  <div class="packing-status">
    <span role="status">{packedCount} of {packingItems.length} packed</span>
    {#if resetDocument === document}
      <span>Uncheck editable items?</span>
      <button onclick={resetChecks}>Reset checks</button>
      <button onclick={() => (resetDocument = null)}>Cancel</button>
    {:else}
      <button
        disabled={readonly || document.readOnly || !document.blocks.some((block) => block.checked && !block.readOnly)}
        onclick={() => (resetDocument = document)}
      >Reset checks</button>
    {/if}
  </div>
{/if}

  <div class="blocks" aria-label="Note body">
    {#each document.blocks as block, index (block.id)}
      <div class:checklist={block.type === 'CHECKLIST_ITEM'} class="block-row">
        {#if block.type === 'CHECKLIST_ITEM'}
          <input
            class="block-check"
            type="checkbox"
            aria-label={block.text || 'Toggle checklist item'}
            checked={block.checked}
            disabled={readonly || document.readOnly || block.readOnly}
            onchange={(event) =>
              updateBlock(index, { checked: event.currentTarget.checked })}
          />
        {/if}
        <textarea
          data-block-input
          aria-label={block.type === 'CHECKLIST_ITEM' ? 'Checklist item' : 'Text block'}
          placeholder={document.packingList ? 'Item to bring' : block.type === 'CHECKLIST_ITEM' ? 'Checklist item' : 'Start writing…'}
          rows={Math.max(1, block.text.split('\n').length)}
          maxlength="100000"
          value={block.text}
          readonly={readonly || document.readOnly || block.readOnly}
          oninput={(event) => updateBlock(index, { text: event.currentTarget.value })}
          onkeydown={(event) => handleKeydown(event, index, event.currentTarget)}
        ></textarea>
        {#if document.packingList && !readonly && !document.readOnly}
          <button
            class="remove-item"
            aria-label={`Remove ${block.text || 'item'}`}
            disabled={block.readOnly}
            onclick={() => removeItem(index)}
          >×</button>
        {/if}
      </div>
    {/each}
  </div>

  {#if !readonly && !document.readOnly}
    <div class="block-toolbar" aria-label="Note actions">
      {#if !document.packingList}
        <button onclick={() => addBlock('PARAGRAPH')}>Text</button>
      {/if}
      <button onclick={() => addBlock('CHECKLIST_ITEM')}>{document.packingList ? '+ Add item' : '☐ Checklist'}</button>
      {#if !document.packingList}
        <button onclick={onDates}>◷ Dates{dateCount > 0 ? ` ${dateCount}` : ''}</button>
      {/if}
    </div>
  {/if}
</div>

<style>
  .packing-status {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
    padding: 8px 0 14px;
    color: var(--vn-text-muted);
  }

  .packing-status > span:first-child {
    margin-right: auto;
  }

  .remove-item {
    flex: 0 0 auto;
    min-width: 32px;
    min-height: 32px;
    padding: 2px 8px;
  }

  .block-editor {
    display: grid;
    grid-template-rows: minmax(0, 1fr) auto;
    min-height: 0;
  }

  .block-editor.packing {
    grid-template-rows: auto minmax(0, 1fr) auto;
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
