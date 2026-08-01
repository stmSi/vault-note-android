<script lang="ts">
  import { attachmentKind, formattedFileSize, isPreviewableImage } from './lib/attachments';
  import type { AuthStatus, VaultAttachment } from './lib/models';

  export let attachments: VaultAttachment[];
  export let busy = false;
  export let readonly = false;
  export let encryptionMode: AuthStatus['encryptionMode'] = 'UNCONFIGURED';
  export let onAdd: () => void;
  export let onPreview: (attachment: VaultAttachment) => void;
  export let onOpen: (attachment: VaultAttachment) => void;
  export let onSave: (id: string) => void;
  export let onDelete: (id: string) => void;
</script>

<section class="attachment-section" aria-label="Attachments">
  <header>
    <div>
      <strong>Attachments</strong>
      <small>{attachments.length} {attachments.length === 1 ? 'file' : 'files'}</small>
    </div>
    {#if !readonly}
      <button disabled={busy} onclick={onAdd}>+ Add file</button>
    {/if}
  </header>
  <div class="attachment-list">
    {#if attachments.length === 0}
      <p>Drop files anywhere to add them here.</p>
    {/if}
    {#each attachments as attachment (attachment.id)}
      <article class="attachment-row">
        <span class="attachment-kind" aria-hidden="true">{attachmentKind(attachment.mimeType)}</span>
        <button
          class="attachment-primary"
          disabled={busy}
          title={isPreviewableImage(attachment) ? 'Preview image' : 'Open with default application'}
          onclick={() => isPreviewableImage(attachment) ? onPreview(attachment) : onOpen(attachment)}
        >
          <strong>{attachment.displayName}</strong>
          <small>
            {formattedFileSize(attachment.fileSize)} · {encryptionMode === 'PASSWORD'
              ? 'encrypted locally'
              : 'stored without encryption'}
          </small>
        </button>
        {#if isPreviewableImage(attachment)}
          <button disabled={busy} onclick={() => onPreview(attachment)}>Preview</button>
        {/if}
        <button disabled={busy} onclick={() => onOpen(attachment)}>Open</button>
        <button disabled={busy} onclick={() => onSave(attachment.id)}>Save copy</button>
        {#if !readonly}
          <button class="danger-button" disabled={busy} onclick={() => onDelete(attachment.id)}>Delete</button>
        {/if}
      </article>
    {/each}
  </div>
</section>
