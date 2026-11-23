import type { ConfirmChannel, ConsumeMessage } from "amqplib";
import type { ITripRepository } from "../repositories/ITripRepository";
import type { IProcessedEventRepository } from "../repositories/InMemoryProcessedEventRepository";
import type { IOutboxRepository } from "../outbox/IOutboxRepository";
import type { EventEnvelope } from "./IEventPublisher";
import { parseTripEvent, tripPaymentSaga, type SagaAction } from "../saga/tripPaymentSaga";
import { CONSUMED_EVENTS, messagingLogger, RETRY_QUEUES, TRIP_QUEUE } from "./RabbitMQConnection";

/** Serial consumer with in-process checkpoints for partially completed actions. */
export class TripEventConsumer {
  private tail: Promise<void> = Promise.resolve();
  private readonly progress = new Map<string, { actions: SagaAction[]; next: number }>();
  private consumerTag?: string;

  /** Injects transport and replaceable persistence contracts. */
  constructor(private readonly channel: ConfirmChannel, private readonly trips: ITripRepository,
    private readonly outbox: IOutboxRepository, private readonly processed: IProcessedEventRepository,
    private readonly durableHandler?: (event: EventEnvelope<Record<string, unknown>>) => Promise<void>) {}

  /** Registers a manually acknowledged consumer. */
  async start(): Promise<void> {
    if (this.consumerTag) return;
    const result = await this.channel.consume(TRIP_QUEUE, message => {
      if (message) void this.handleMessage(message).catch(error => {
        messagingLogger.error({ err: error }, "Consumer transport failed");
      });
    }, { noAck: false });
    this.consumerTag = result.consumerTag;
  }

  /** Cancels delivery and drains the active handler. */
  async stop(): Promise<void> {
    if (this.consumerTag) await this.channel.cancel(this.consumerTag);
    this.consumerTag = undefined;
    await this.tail;
  }

  /** Serializes deliveries, including duplicate events received concurrently. */
  handleMessage(message: ConsumeMessage): Promise<void> {
    const task = this.tail.then(() => this.processMessage(message));
    this.tail = task.catch(() => undefined);
    return task;
  }

  private async processMessage(message: ConsumeMessage): Promise<void> {
    let correlationId = "unknown";
    try {
      const event = parseTripEvent(JSON.parse(message.content.toString("utf8")) as unknown);
      correlationId = event.correlationId;
      if (!(CONSUMED_EVENTS as readonly string[]).includes(event.eventType)) throw new Error("Unsupported incoming event");
      if (this.durableHandler) {
        await this.durableHandler(event);
      } else if (!await this.processed.has(event.eventId)) {
        let state = this.progress.get(event.eventId);
        if (!state) {
          const actions = tripPaymentSaga(event);
          const trip = await this.trips.findById(actions[0].tripId);
          if (!trip) throw new Error("Trip not found");
          const allowed = event.eventType === "driver.assigned"
            ? ["REQUESTED", "MATCHING", "DRIVER_ASSIGNED"]
            : ["COMPLETED", "PAYMENT_PENDING", "PAYMENT_FAILED", "COMPENSATING"];
          if (!allowed.includes(trip.status)) throw new Error(`Event incompatible with status ${trip.status}`);
          state = { actions, next: 0 };
          this.progress.set(event.eventId, state);
        }
        while (state.next < state.actions.length) {
          const action = state.actions[state.next];
          if (action.kind === "update") await this.trips.updateStatus(action.tripId, action.status, action.patch);
          else await this.outbox.add({ aggregateId: action.tripId, eventType: action.eventType,
            payload: action.payload, correlationId: action.correlationId });
          state.next += 1;
        }
        await this.processed.markProcessed(event.eventId);
        this.progress.delete(event.eventId);
      }
    } catch (error) {
      messagingLogger.error({ err: error, correlationId }, "Trip event failed");
      await this.retry(message);
      return;
    }
    this.channel.ack(message);
  }

  private async retry(message: ConsumeMessage): Promise<void> {
    const header: unknown = message.properties.headers?.["x-retry-count"];
    const retryCount = header === undefined ? 0 : header;
    if (typeof retryCount !== "number" || !Number.isInteger(retryCount) || retryCount < 0 || retryCount >= RETRY_QUEUES.length) {
      this.channel.nack(message, false, false);
      return;
    }
    try {
      await new Promise<void>((resolve, reject) => {
        // Do not carry an original per-message expiration into the retry queue.
        const { expiration: _expiration, ...properties } = message.properties;
        this.channel.sendToQueue(RETRY_QUEUES[retryCount], message.content, {
          ...properties, persistent: true,
          headers: { ...message.properties.headers, "x-retry-count": retryCount + 1 },
        }, (error: Error | null) => error ? reject(error) : resolve());
      });
    } catch (error) {
      this.channel.nack(message, false, true);
      throw error;
    }
    this.channel.ack(message);
  }
}
