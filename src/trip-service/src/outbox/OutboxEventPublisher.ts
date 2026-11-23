import type { EventEnvelope, IEventPublisher } from "../messaging/IEventPublisher";
import type { IOutboxRepository } from "./IOutboxRepository";
import { tripPaymentSaga } from "../saga/tripPaymentSaga";

/** Adapter for the existing REST service; use a transactional adapter in production. */
export class OutboxEventPublisher implements IEventPublisher {
  /** Uses the same outbox instance as the worker. */
  constructor(private readonly outbox: IOutboxRepository) {}

  /** Enqueues domain events and requests payment when a trip completes. */
  async publish<T>(event: EventEnvelope<T>): Promise<void> {
    if (!event.payload || typeof event.payload !== "object") throw new Error("Invalid trip payload");
    const payload = event.payload as Record<string, unknown>;
    const tripId = payload.tripId ?? payload.id;
    if (typeof tripId !== "string" || !tripId) throw new Error("Missing tripId");
    const paymentActions = event.eventType === "trip.completed" ? tripPaymentSaga({ ...event,
      payload: { tripId, amount: payload.amount ?? payload.fare } }) : [];
    await this.outbox.add({ aggregateId: tripId, eventType: event.eventType,
      correlationId: event.correlationId, payload: { ...payload, tripId } });
    for (const action of paymentActions) {
      if (action.kind === "publish") await this.outbox.add({ aggregateId: tripId,
        eventType: action.eventType, correlationId: action.correlationId, payload: action.payload });
    }
  }
}
