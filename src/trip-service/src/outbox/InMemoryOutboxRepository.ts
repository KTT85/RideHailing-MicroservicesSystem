import { randomUUID } from "node:crypto";
import type { IOutboxRepository, OutboxEvent, OutboxInput } from "./IOutboxRepository";

/** Development-only outbox; restart discards all records. */
export class InMemoryOutboxRepository implements IOutboxRepository {
  private readonly events = new Map<string, OutboxEvent & { publishedAt: string | null }>();

  /** Copies the event into a new pending record. */
  async add(event: OutboxInput): Promise<void> {
    const id = randomUUID();
    this.events.set(id, { ...structuredClone(event), id, createdAt: new Date().toISOString(), publishedAt: null });
  }

  /** Returns detached copies of pending records. */
  async listUnpublished(limit: number): Promise<OutboxEvent[]> {
    if (!Number.isInteger(limit) || limit < 1) throw new Error("limit must be a positive integer");
    return structuredClone([...this.events.values()].filter(event => event.publishedAt === null).slice(0, limit));
  }

  /** Marks a known record as published. */
  async markPublished(id: string): Promise<void> {
    const event = this.events.get(id);
    if (!event) throw new Error(`Outbox event ${id} not found`);
    event.publishedAt ??= new Date().toISOString();
  }
}
