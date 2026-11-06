/** Event metadata shared by publishers and downstream consumers. */
export interface EventEnvelope<T = unknown> {
  eventId: string;
  eventType: string;
  correlationId: string;
  causationId?: string;
  source: string;
  occurredAt: string;
  payload: T;
}

/** Publishes a domain event to the configured messaging transport. */
export interface IEventPublisher {
  /** Publishes one event envelope. */
  publish<T>(envelope: EventEnvelope<T>): Promise<void>;
}
