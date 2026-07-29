import { describe, expect, it } from 'vitest';
import {
  parseThemePreference,
  previewGradient,
  THEME_OPTIONS,
} from './themes';

describe('theme catalog', () => {
  it('contains thirty unique dark visual themes including Tokyo Night', () => {
    expect(THEME_OPTIONS).toHaveLength(30);
    expect(new Set(THEME_OPTIONS.map(({ id }) => id)).size).toBe(30);
    expect(THEME_OPTIONS.some(({ id }) => id === 'tokyo_night')).toBe(true);
  });

  it('preserves dark preferences and migrates removed values', () => {
    expect(parseThemePreference('dark')).toBe('dark');
    expect(parseThemePreference('aurora')).toBe('aurora');
    expect(parseThemePreference('light')).toBe('dark');
    expect(parseThemePreference('system')).toBe('dark');
    expect(parseThemePreference('unknown')).toBe('dark');
    expect(parseThemePreference(null)).toBe('dark');
  });

  it('creates a bounded static gradient from catalog colors', () => {
    expect(previewGradient(THEME_OPTIONS[15].preview)).toBe(
      'linear-gradient(125deg, #1a1b26 0%, #24283b 52%, #1f2335 100%)',
    );
  });
});
