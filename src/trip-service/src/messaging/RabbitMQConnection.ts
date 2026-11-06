import * as amqp from "amqplib";
import type { ChannelModel, ConfirmChannel } from "amqplib";
import pino from "pino";

export const EVENTS_EXCHANGE = "ridehailing.events";
export const DEAD_LETTER_EXCHANGE = "ridehailing.dlx";
export const TRIP_QUEUE = "trip-service.queue";
export const DEAD_LETTER_QUEUE = "trip-service.dlq";
export const RETRY_DELAYS = [5_000, 15_000, 60_000] as const;
export const RETRY_QUEUES = RETRY_DELAYS.map(delay => `trip-service.retry.${delay / 1000}s`);
export const CONSUMED_EVENTS = ["driver.assigned", "payment.completed", "payment.failed"] as const;
export const messagingLogger = pino({ base: { service: "trip-service" }, mixin: () => ({ correlationId: "system" }) });

/** Owns one connection and a confirm channel. */
export class RabbitMQConnection {
  private connection?: ChannelModel;
  private channel?: ConfirmChannel;

  /** Opens the broker connection and declares the durable topology. */
  async connect(url = process.env.RABBITMQ_URL): Promise<ConfirmChannel> {
    if (this.channel) return this.channel;
    if (!url) throw new Error("RABBITMQ_URL is required");
    const connection = await amqp.connect(url);
    this.connection = connection;
    connection.on("error", error => messagingLogger.error({ err: error }, "RabbitMQ connection error"));
    connection.on("close", () => { this.channel = undefined; this.connection = undefined; });
    try {
      const channel = await connection.createConfirmChannel();
      channel.on("error", error => messagingLogger.error({ err: error }, "RabbitMQ channel error"));
      channel.on("close", () => { this.channel = undefined; });
      await channel.assertExchange(EVENTS_EXCHANGE, "topic", { durable: true });
      await channel.assertExchange(DEAD_LETTER_EXCHANGE, "direct", { durable: true });
      await channel.assertQueue(DEAD_LETTER_QUEUE, { durable: true });
      await channel.bindQueue(DEAD_LETTER_QUEUE, DEAD_LETTER_EXCHANGE, DEAD_LETTER_QUEUE);
      await channel.assertQueue(TRIP_QUEUE, { durable: true, arguments: {
        "x-dead-letter-exchange": DEAD_LETTER_EXCHANGE,
        "x-dead-letter-routing-key": DEAD_LETTER_QUEUE,
      } });
      for (const eventType of CONSUMED_EVENTS) await channel.bindQueue(TRIP_QUEUE, EVENTS_EXCHANGE, eventType);
      for (const [index, queue] of RETRY_QUEUES.entries()) {
        await channel.assertQueue(queue, { durable: true, arguments: {
          "x-message-ttl": RETRY_DELAYS[index],
          "x-dead-letter-exchange": "",
          "x-dead-letter-routing-key": TRIP_QUEUE,
        } });
      }
      await channel.prefetch(1);
      this.channel = channel;
      return channel;
    } catch (error) {
      await connection.close().catch(() => undefined);
      this.connection = undefined;
      throw error;
    }
  }

  /** Closes the connection and its channels. */
  async close(): Promise<void> {
    const connection = this.connection;
    this.channel = undefined;
    this.connection = undefined;
    if (connection) await connection.close();
  }
}
