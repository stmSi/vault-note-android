import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DebouncedAutosaver } from './autosave';

describe('DebouncedAutosaver', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('saves only after 400 milliseconds without edits', async () => {
    const saved: string[] = [];
    const autosaver = new DebouncedAutosaver<string>(
      async (value) => {
        saved.push(value);
      },
      () => undefined,
    );

    autosaver.submit('a');
    await vi.advanceTimersByTimeAsync(399);
    expect(saved).toEqual([]);
    autosaver.submit('ab');
    await vi.advanceTimersByTimeAsync(399);
    expect(saved).toEqual([]);
    await vi.advanceTimersByTimeAsync(1);
    expect(saved).toEqual(['ab']);
  });

  it('flushes immediately without a delayed duplicate', async () => {
    const saved: string[] = [];
    const autosaver = new DebouncedAutosaver<string>(
      async (value) => {
        saved.push(value);
      },
      () => undefined,
    );

    autosaver.submit('draft');
    expect(await autosaver.flush()).toBe(true);
    await vi.runAllTimersAsync();
    expect(saved).toEqual(['draft']);
  });

  it('serializes a newer draft behind an active save', async () => {
    let releaseFirst: (() => void) | undefined;
    let activeSaves = 0;
    let maximumActiveSaves = 0;
    const saved: string[] = [];
    const firstBlocked = new Promise<void>((resolve) => {
      releaseFirst = resolve;
    });
    const autosaver = new DebouncedAutosaver<string>(
      async (value) => {
        activeSaves += 1;
        maximumActiveSaves = Math.max(maximumActiveSaves, activeSaves);
        if (value === 'first') {
          await firstBlocked;
        }
        saved.push(value);
        activeSaves -= 1;
      },
      () => undefined,
    );

    autosaver.submit('first');
    await vi.advanceTimersByTimeAsync(400);
    autosaver.submit('second');
    const flush = autosaver.flush();
    releaseFirst?.();

    expect(await flush).toBe(true);
    expect(saved).toEqual(['first', 'second']);
    expect(maximumActiveSaves).toBe(1);
  });

  it('retains a failed draft so flush can retry it', async () => {
    let attempts = 0;
    const autosaver = new DebouncedAutosaver<string>(
      async () => {
        attempts += 1;
        if (attempts === 1) {
          throw new Error('expected test failure');
        }
      },
      () => undefined,
    );

    autosaver.submit('draft');
    await vi.advanceTimersByTimeAsync(400);
    expect(attempts).toBe(1);
    expect(await autosaver.flush()).toBe(true);
    expect(attempts).toBe(2);
  });
});
