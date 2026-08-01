<script lang="ts">
  import { onMount } from 'svelte';
  import type { VaultAttachment } from './lib/models';

  export let attachment: VaultAttachment;
  export let source: string;
  export let busy = false;
  export let hasPrevious = false;
  export let hasNext = false;
  export let onClose: () => void;
  export let onPrevious: () => void;
  export let onNext: () => void;
  export let onOpen: () => void;
  export let onSave: () => void;
  export let onImageError: () => void;

  let closeButton: HTMLButtonElement;

  onMount(() => closeButton.focus());

  function handleKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
    } else if (event.key === 'ArrowLeft' && hasPrevious) {
      event.preventDefault();
      onPrevious();
    } else if (event.key === 'ArrowRight' && hasNext) {
      event.preventDefault();
      onNext();
    }
  }
</script>

<svelte:window onkeydown={handleKeydown} />

<div
  class="attachment-preview-backdrop"
  role="presentation"
  onclick={(event) => event.target === event.currentTarget && onClose()}
>
  <div class="attachment-preview-dialog" role="dialog" aria-modal="true" aria-label={`Preview ${attachment.displayName}`}>
    <header>
      <div>
        <span>Image preview</span>
        <strong>{attachment.displayName}</strong>
      </div>
      <button bind:this={closeButton} class="icon-button" aria-label="Close image preview" onclick={onClose}>×</button>
    </header>
    <div class="attachment-preview-canvas">
      <img src={source} alt={attachment.displayName} onerror={onImageError} />
      <button class="preview-arrow previous" aria-label="Previous image" disabled={!hasPrevious || busy} onclick={onPrevious}>‹</button>
      <button class="preview-arrow next" aria-label="Next image" disabled={!hasNext || busy} onclick={onNext}>›</button>
    </div>
    <footer>
      <span>Use ← and → to browse images</span>
      <div>
        <button disabled={busy} onclick={onOpen}>Open in default app</button>
        <button disabled={busy} onclick={onSave}>Save copy</button>
      </div>
    </footer>
  </div>
</div>
