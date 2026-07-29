import { mount } from 'svelte';
import App from './App.svelte';
import './styles.css';
import { parseThemePreference } from './lib/themes';

document.documentElement.dataset.theme = parseThemePreference(
  window.localStorage.getItem('vaultnote.theme'),
);

const target = document.getElementById('app');

if (target === null) {
  throw new Error('Application mount point is unavailable');
}

mount(App, { target });
