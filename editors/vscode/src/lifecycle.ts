export interface Client {
  start(): Promise<void>;
  stop(timeout: number): Promise<void>;
}

/** Serializes folder/config changes, including deactivation during a pending start. */
export class ClientLifecycle {
  private queue: Promise<void> = Promise.resolve();
  private client?: Client;
  private disposed = false;
  constructor(
    private readonly factory: () => Client,
    private readonly error: (error: unknown) => void,
  ) {}
  start(): Promise<void> {
    return this.enqueue(async () => {
      if (this.disposed || this.client) return;
      const client = this.factory();
      this.client = client;
      try {
        await client.start();
      } catch (error) {
        this.client = undefined;
        await client.stop(5000).catch(this.error);
        throw error;
      }
    });
  }
  restart(): Promise<void> {
    return this.enqueue(async () => {
      await this.stopCurrent();
      if (!this.disposed) {
        const client = this.factory();
        this.client = client;
        try {
          await client.start();
        } catch (error) {
          this.client = undefined;
          await client.stop(5000).catch(this.error);
          throw error;
        }
      }
    });
  }
  close(): Promise<void> {
    this.disposed = true;
    return this.enqueue(() => this.stopCurrent());
  }
  private async stopCurrent(): Promise<void> {
    const client = this.client;
    this.client = undefined;
    if (client) await client.stop(5000);
  }
  private enqueue(operation: () => Promise<void>): Promise<void> {
    const result = this.queue.then(operation);
    this.queue = result.catch(this.error);
    return result;
  }
}
