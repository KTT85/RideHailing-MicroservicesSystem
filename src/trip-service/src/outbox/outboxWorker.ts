import type { IEventPublisher } from "../messaging/IEventPublisher";
import { messagingLogger } from "../messaging/RabbitMQConnection";
import type { IOutboxRepository } from "./IOutboxRepository";

/** Polls sequentially so the same worker cannot publish overlapping batches. */
export class OutboxWorker {
  private timer?: ReturnType<typeof setTimeout>;
  private running?: Promise<void>;
  private stopped = true;

  /** Configures the outbox, publisher and batch size. */
  constructor(private readonly repository: IOutboxRepository, private readonly publisher: IEventPublisher,
    private readonly batchSize = 100) {}

  /** Publishes one batch; retains failed records for the next attempt. */
  async runOnce(): Promise<void> {
    if (this.running) return this.running;
    this.running = this.publishBatch();
    try { await this.running; } finally { this.running = undefined; }
  }

  /** Starts polling every two seconds. */
  start(): void {
    if (!this.stopped) return;
    this.stopped = false;
    const tick = async (): Promise<void> => {
      try { await this.runOnce(); }
      catch (error) { messagingLogger.error({ err: error }, "Outbox polling failed"); }
      finally { if (!this.stopped) this.timer = setTimeout(() => { void tick(); }, 2_000); }
    };
    this.timer = setTimeout(() => { void tick(); }, 2_000);
  }

  /** Stops polling and waits for any active batch. */
  async stop(): Promise<void> {
    this.stopped = true;
    if (this.timer) clearTimeout(this.timer);
    await this.running;
  }

  private async publishBatch(): Promise<void> {
    for (const event of await this.repository.listUnpublished(this.batchSize)) {
      try {
        await this.publisher.publish({ eventId: event.id, eventType: event.eventType,
          correlationId: event.correlationId, source: "trip-service",
          occurredAt: event.createdAt ?? new Date().toISOString(), payload: event.payload });
        await this.repository.markPublished(event.id);
      } catch (error) {
        messagingLogger.error({ err: error, correlationId: event.correlationId, eventId: event.id }, "Outbox publish failed");
      }
    }
  }
}
