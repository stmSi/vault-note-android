export type AutosaveStatus = 'saved' | 'dirty' | 'saving' | 'error';

interface VersionedValue<T> {
  version: number;
  value: T;
}

export class DebouncedAutosaver<T> {
  private generation = 0;
  private savedGeneration = 0;
  private latest: VersionedValue<T> | undefined;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private queue: Promise<void> = Promise.resolve();

  constructor(
    private readonly save: (value: T) => Promise<void>,
    private readonly onStatus: (status: AutosaveStatus) => void,
    private readonly delayMillis = 400,
  ) {}

  submit(value: T): number {
    this.generation += 1;
    const version = this.generation;
    this.latest = { version, value };
    this.onStatus('dirty');
    this.clearTimer();
    this.timer = setTimeout(() => {
      this.timer = undefined;
      void this.enqueue(() => this.persistScheduled(version));
    }, this.delayMillis);
    return version;
  }

  async flush(): Promise<boolean> {
    this.clearTimer();
    return this.enqueue(async () => {
      while (this.latest !== undefined && this.latest.version > this.savedGeneration) {
        const succeeded = await this.persist(this.latest);
        if (!succeeded) {
          return false;
        }
      }
      return true;
    });
  }

  cancelPending(): void {
    this.clearTimer();
  }

  private clearTimer(): void {
    if (this.timer !== undefined) {
      clearTimeout(this.timer);
      this.timer = undefined;
    }
  }

  private enqueue<R>(operation: () => Promise<R>): Promise<R> {
    const result = this.queue.then(operation, operation);
    this.queue = result.then(
      () => undefined,
      () => undefined,
    );
    return result;
  }

  private async persistScheduled(version: number): Promise<boolean> {
    const snapshot = this.latest;
    if (
      snapshot === undefined ||
      snapshot.version !== version ||
      snapshot.version <= this.savedGeneration
    ) {
      return true;
    }
    return this.persist(snapshot);
  }

  private async persist(snapshot: VersionedValue<T>): Promise<boolean> {
    this.onStatus('saving');
    try {
      await this.save(snapshot.value);
      this.savedGeneration = Math.max(this.savedGeneration, snapshot.version);
      const hasNewerDraft = (this.latest?.version ?? snapshot.version) > this.savedGeneration;
      this.onStatus(hasNewerDraft ? 'dirty' : 'saved');
      return true;
    } catch {
      this.onStatus('error');
      return false;
    }
  }
}
