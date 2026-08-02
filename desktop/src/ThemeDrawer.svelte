<script lang="ts">
  import { onMount } from 'svelte';
  import ThemePicker from './ThemePicker.svelte';
  import type { ThemePreference } from './lib/themes';

  export let selected: ThemePreference;
  export let onSelect: (theme: ThemePreference) => void;
  export let onClose: () => void;

  let closeButton: HTMLButtonElement;

  onMount(() => closeButton.focus());
</script>

<div class="theme-drawer-layer">
  <button
    class="theme-drawer-backdrop"
    type="button"
    aria-label="Close theme settings"
    onclick={onClose}
  ></button>
  <div
    class="theme-drawer"
    role="dialog"
    aria-modal="true"
    aria-labelledby="theme-drawer-title"
  >
    <header class="theme-drawer-header">
      <div>
        <span class="theme-drawer-eyebrow">Appearance</span>
        <h2 id="theme-drawer-title">Choose a theme</h2>
        <p>Preview and apply any dark gradient palette instantly.</p>
      </div>
      <button
        bind:this={closeButton}
        class="theme-drawer-close"
        type="button"
        aria-label="Close theme settings"
        title="Close"
        onclick={onClose}
      >
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="m6.8 6.8 10.4 10.4M17.2 6.8 6.8 17.2"></path>
        </svg>
      </button>
    </header>
    <ThemePicker {selected} {onSelect} />
  </div>
</div>
