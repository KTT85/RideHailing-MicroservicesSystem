export interface OutboxInput {
  aggregateId: string;
  eventType: string;
  payload: unknown;
  correlationId: string;
}

export interface OutboxEvent extends OutboxInput {
  id: string;
  createdAt?: string;
}

export interface IOutboxRepository {
  /** Stores an event for later publication. */
  add(event: OutboxInput): Promise<void>;
  /** Returns at most limit pending events in insertion order. */
  listUnpublished(limit: number): Promise<OutboxEvent[]>;
  /** Marks an event published only after broker confirmation. */
  markPublished(id: string): Promise<void>;
}
