import type { EventEnvelope } from "../messaging/IEventPublisher";
import type { Trip, TripStatus } from "../types/trip";

export type SagaAction =
  | { kind: "update"; tripId: string; status: TripStatus; patch: Partial<Trip> }
  | { kind: "publish"; tripId: string; eventType: string; correlationId: string; payload: Record<string, unknown> };

/** Validates an incoming JSON envelope without trusting broker input. */
export function parseTripEvent(value: unknown): EventEnvelope<Record<string, unknown>> {
  if (!value || typeof value !== "object") throw new Error("Invalid event envelope");
  const record = value as Record<string, unknown>;
  for (const key of ["eventId", "eventType", "correlationId", "source", "occurredAt"]) {
    if (typeof record[key] !== "string" || !record[key]) throw new Error(`Invalid ${key}`);
  }
  if (Number.isNaN(Date.parse(record.occurredAt as string))) throw new Error("Invalid occurredAt");
  if (record.causationId !== undefined && typeof record.causationId !== "string") throw new Error("Invalid causationId");
  if (!record.payload || typeof record.payload !== "object" || Array.isArray(record.payload)) throw new Error("Invalid payload");
  return record as unknown as EventEnvelope<Record<string, unknown>>;
}

/** Returns deterministic actions; performs no I/O and reads no clock. */
export function tripPaymentSaga(event: EventEnvelope): SagaAction[] {
  const parsed = parseTripEvent(event);
  const payload = parsed.payload;
  const requiredString = (key: string): string => {
    const value = payload[key];
    if (typeof value !== "string" || !value) throw new Error(`Invalid payload.${key}`);
    return value;
  };
  const tripId = requiredString("tripId");
  const publish = (eventType: string, body: Record<string, unknown>): SagaAction => ({
    kind: "publish", tripId, eventType, correlationId: event.correlationId, payload: { tripId, ...body },
  });
  switch (event.eventType) {
    case "driver.assigned": {
      const driverId = requiredString("driverId");
      const assignedAt = requiredString("assignedAt");
      if (Number.isNaN(Date.parse(assignedAt))) throw new Error("Invalid assignedAt");
      return [{ kind: "update", tripId, status: "DRIVER_ASSIGNED", patch: { driverId } }];
    }
    case "trip.completed":
      if (typeof payload.amount !== "number" || !Number.isFinite(payload.amount) || payload.amount < 0) throw new Error("Invalid amount");
      return [publish("payment.requested", { amount: payload.amount })];
    case "payment.completed": {
      const paymentId = requiredString("paymentId");
      if (typeof payload.amount !== "number" || !Number.isFinite(payload.amount) || payload.amount < 0) throw new Error("Invalid amount");
      return [{ kind: "update", tripId, status: "COMPLETED", patch: { paymentId } }];
    }
    case "payment.failed":
      requiredString("reason");
      return [
        { kind: "update", tripId, status: "COMPENSATING", patch: {} },
        { kind: "update", tripId, status: "CANCELLED", patch: { cancelReason: "payment_failed", cancelledAt: event.occurredAt } },
        publish("trip.cancelled", { reason: "payment_failed" }),
      ];
    default: throw new Error(`Unsupported event type: ${event.eventType}`);
  }
}
