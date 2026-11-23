import { Prisma } from "@prisma/client";
import type { IOutboxRepository, OutboxInput, OutboxEvent } from "../outbox/IOutboxRepository";
import type { IProcessedEventRepository } from "./InMemoryProcessedEventRepository";

export class PrismaOutboxRepository implements IOutboxRepository {
  constructor(private readonly db: Prisma.TransactionClient) {}
  /** Inserts an event within the caller's transaction. */
  async add(event: OutboxInput): Promise<void> {
    const payload = JSON.parse(JSON.stringify(event.payload)) as Prisma.InputJsonValue;
    await this.db.outboxEvent.create({ data: { ...event, payload } });
  }
  /** Reads pending events in stable creation order. */
  async listUnpublished(limit: number): Promise<OutboxEvent[]> {
    const rows = await this.db.outboxEvent.findMany({ where: { publishedAt: null },
      orderBy: [{ createdAt: "asc" }, { id: "asc" }], take: limit });
    return rows.map(row => ({ ...row, createdAt: row.createdAt.toISOString() }));
  }
  /** Records broker confirmation. */
  async markPublished(id: string): Promise<void> {
    await this.db.outboxEvent.update({ where: { id }, data: { publishedAt: new Date() } });
  }
}

export class PrismaProcessedEventRepository implements IProcessedEventRepository {
  constructor(private readonly db: Prisma.TransactionClient) {}
  /** Checks the durable completion marker. */
  async has(eventId: string): Promise<boolean> {
    return await this.db.processedEvent.findUnique({ where: { eventId } }) !== null;
  }
  /** Inserts the completion marker in the saga transaction. */
  async markProcessed(eventId: string): Promise<void> {
    await this.db.processedEvent.create({ data: { eventId } });
  }
}
