import type { ConfirmChannel } from "amqplib";
import type { EventEnvelope, IEventPublisher } from "./IEventPublisher";
import { EVENTS_EXCHANGE, RIDE_EVENTS_EXCHANGE } from "./RabbitMQConnection";
import type { Message } from "amqplib";

export class RabbitMQEventPublisher implements IEventPublisher {
  /** Uses a channel with publisher confirmations enabled. */
  constructor(private readonly channel: ConfirmChannel) {}

  /** Resolves only when RabbitMQ confirms the persistent event. */
  async publish<T>(envelope: EventEnvelope<T>): Promise<void> {
    const integrated = /^[A-Z]/.test(envelope.eventType);
    const exchange = integrated ? RIDE_EVENTS_EXCHANGE : EVENTS_EXCHANGE;
    const body = integrated ? { ...envelope, timestamp: envelope.occurredAt } : envelope;
    await new Promise<void>((resolve, reject) => {
      let unrouted = false;
      const onReturn = (message: Message): void => {
        if (message.properties.messageId === envelope.eventId) unrouted = true;
      };
      if (integrated) this.channel.on("return", onReturn);
      try {
        this.channel.publish(exchange, envelope.eventType, Buffer.from(JSON.stringify(body)), {
          persistent: true, contentType: "application/json", messageId: envelope.eventId,
          correlationId: envelope.correlationId, type: envelope.eventType,
          ...(integrated ? { mandatory: true } : {}),
        }, (error: Error | null) => {
          if (integrated) this.channel.off("return", onReturn);
          if (error || unrouted) reject(error ?? new Error(`No binding for ${envelope.eventType}`));
          else resolve();
        });
      } catch (error) {
        if (integrated) this.channel.off("return", onReturn);
        reject(error);
      }
    });
  }
}
