import pino, { type Logger } from "pino";
import type { EventEnvelope, IEventPublisher } from "./IEventPublisher";

const SERVICE_NAME = "trip-service";

/** Logs published event envelopes without connecting to a broker. */
export class NoopEventPublisher implements IEventPublisher {
  /** Creates a publisher that writes JSON logs through pino. */
  constructor(private readonly logger: Logger = pino({ base: { service: SERVICE_NAME } })) {}

  /** Logs the event envelope; it intentionally performs no network I/O. */
  async publish<T>(envelope: EventEnvelope<T>): Promise<void> {
    this.logger.info(
      {
        correlationId: envelope.correlationId,
        eventId: envelope.eventId,
        eventType: envelope.eventType,
        source: envelope.source,
        occurredAt: envelope.occurredAt,
        payload: envelope.payload,
      },
      "Event published (noop)",
    );
  }
}
