import type { ConfirmChannel } from "amqplib";
import type { EventEnvelope, IEventPublisher } from "./IEventPublisher";
import { EVENTS_EXCHANGE } from "./RabbitMQConnection";

export class RabbitMQEventPublisher implements IEventPublisher {
  /** Uses a channel with publisher confirmations enabled. */
  constructor(private readonly channel: ConfirmChannel) {}

  /** Resolves only when RabbitMQ confirms the persistent event. */
  async publish<T>(envelope: EventEnvelope<T>): Promise<void> {
    await new Promise<void>((resolve, reject) => {
      this.channel.publish(EVENTS_EXCHANGE, envelope.eventType, Buffer.from(JSON.stringify(envelope)), {
        persistent: true, contentType: "application/json", messageId: envelope.eventId,
        correlationId: envelope.correlationId, type: envelope.eventType,
      }, (error: Error | null) => error ? reject(error) : resolve());
    });
  }
}
