<script lang="ts">
  import {
    previewGradient,
    THEME_OPTIONS,
    type ThemePreference,
  } from './lib/themes';

  export let selected: ThemePreference;
  export let onSelect: (theme: ThemePreference) => void;
</script>

<div class="theme-picker" role="radiogroup" aria-label="Workspace theme">
  {#each THEME_OPTIONS as theme}
    <button
      type="button"
      class="theme-option"
      class:selected={selected === theme.id}
      role="radio"
      aria-checked={selected === theme.id}
      title={`Use ${theme.label}`}
      onclick={() => onSelect(theme.id)}
    >
      <span
        class="theme-swatch"
        style:background={previewGradient(theme.preview)}
        aria-hidden="true"
      ></span>
      <span>{theme.label}</span>
    </button>
  {/each}
</div>
