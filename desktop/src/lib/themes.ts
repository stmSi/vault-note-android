export const THEME_OPTIONS = [
  { id: 'dark', label: 'Midnight Dark', preview: ['#111318', '#151c2a', '#1a1723'] },
  { id: 'obsidian', label: 'Obsidian', preview: ['#0d0f12', '#171a1f', '#111419'] },
  { id: 'graphite', label: 'Graphite', preview: ['#181a1d', '#23262a', '#1a1e21'] },
  { id: 'aurora', label: 'Aurora', preview: ['#111923', '#17313a', '#232543'] },
  { id: 'nebula', label: 'Nebula', preview: ['#14121f', '#282044', '#17273a'] },
  { id: 'amethyst', label: 'Amethyst', preview: ['#18121f', '#30213c', '#21172b'] },
  { id: 'emerald', label: 'Emerald', preview: ['#0d1916', '#15352a', '#102521'] },
  { id: 'forest', label: 'Forest', preview: ['#101810', '#22321c', '#16231a'] },
  { id: 'cobalt', label: 'Cobalt', preview: ['#0e1726', '#142d52', '#151e35'] },
  { id: 'indigo', label: 'Indigo', preview: ['#121426', '#242953', '#191b36'] },
  { id: 'crimson', label: 'Crimson', preview: ['#1d1115', '#3a1d27', '#25141b'] },
  { id: 'ember', label: 'Ember', preview: ['#1c1410', '#3b2416', '#261812'] },
  { id: 'mocha', label: 'Mocha', preview: ['#1a1513', '#30231e', '#231b18'] },
  { id: 'dusk', label: 'Dusk', preview: ['#171521', '#30283d', '#1d2432'] },
  { id: 'cyber', label: 'Cyber', preview: ['#07191c', '#0b3035', '#10232d'] },
  { id: 'tokyo_night', label: 'Tokyo Night', preview: ['#1a1b26', '#24283b', '#1f2335'] },
  { id: 'dracula', label: 'Dracula', preview: ['#181820', '#282a36', '#34283f'] },
  { id: 'monokai', label: 'Monokai', preview: ['#1e1f1c', '#2d2e28', '#272822'] },
  { id: 'nord_night', label: 'Nord Night', preview: ['#1b202c', '#2e3440', '#242b38'] },
  { id: 'gruvbox', label: 'Gruvbox', preview: ['#1d2021', '#3c3836', '#282828'] },
  { id: 'solarized', label: 'Solarized Dark', preview: ['#002b36', '#073642', '#0b3440'] },
  { id: 'catppuccin', label: 'Catppuccin', preview: ['#1e1e2e', '#313244', '#29273c'] },
  { id: 'everforest', label: 'Everforest', preview: ['#1e2326', '#2d353b', '#26322c'] },
  { id: 'material_ocean', label: 'Material Ocean', preview: ['#0f111a', '#1a2233', '#101827'] },
  { id: 'night_owl', label: 'Night Owl', preview: ['#011627', '#0b2942', '#132336'] },
  { id: 'moonlight', label: 'Moonlight', preview: ['#1e2030', '#2a2e47', '#22243a'] },
  { id: 'deep_space', label: 'Deep Space', preview: ['#0b1320', '#14243a', '#171a2e'] },
  { id: 'blue_hour', label: 'Blue Hour', preview: ['#111827', '#1e3a5f', '#25233c'] },
  { id: 'terminal', label: 'Terminal', preview: ['#07130c', '#0f2418', '#101a14'] },
  { id: 'velvet', label: 'Velvet', preview: ['#1b111c', '#362139', '#25162b'] },
] as const;

export type ThemePreference = (typeof THEME_OPTIONS)[number]['id'];

const THEME_IDS = new Set<string>(THEME_OPTIONS.map(({ id }) => id));

export function parseThemePreference(value: string | null): ThemePreference {
  return value !== null && THEME_IDS.has(value) ? (value as ThemePreference) : 'dark';
}

export function previewGradient(preview: readonly string[]): string {
  return `linear-gradient(125deg, ${preview[0]} 0%, ${preview[1]} 52%, ${preview[2]} 100%)`;
}
