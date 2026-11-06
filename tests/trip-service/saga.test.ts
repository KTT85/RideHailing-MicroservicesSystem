import { tripPaymentSaga } from "../../src/trip-service/src/saga/tripPaymentSaga";
import { InMemoryOutboxRepository } from "../../src/trip-service/src/outbox/InMemoryOutboxRepository";
import { OutboxEventPublisher } from "../../src/trip-service/src/outbox/OutboxEventPublisher";

const event = { eventId: "event-1", eventType: "payment.failed", correlationId: "original-correlation",
  source: "payment-service", occurredAt: "2026-10-30T00:00:00.000Z", payload: { tripId: "trip-1", reason: "declined" } };

test("payment failure compensates in order and preserves correlation", () => {
  expect(tripPaymentSaga(event)).toEqual([
    { kind: "update", tripId: "trip-1", status: "COMPENSATING", patch: {} },
    { kind: "update", tripId: "trip-1", status: "CANCELLED", patch: { cancelReason: "payment_failed", cancelledAt: event.occurredAt } },
    { kind: "publish", tripId: "trip-1", eventType: "trip.cancelled", correlationId: event.correlationId,
      payload: { tripId: "trip-1", reason: "payment_failed" } },
  ]);
});

test("payment completion stores paymentId without emitting trip.completed again", () => {
  expect(tripPaymentSaga({ ...event, eventType: "payment.completed", payload: { tripId: "trip-1", paymentId: "payment-1", amount: 10 } }))
    .toEqual([{ kind: "update", tripId: "trip-1", status: "COMPLETED", patch: { paymentId: "payment-1" } }]);
});

test("completion adapter queues trip.completed and payment.requested", async () => {
  const outbox = new InMemoryOutboxRepository();
  await new OutboxEventPublisher(outbox).publish({ ...event, eventType: "trip.completed", payload: { id: "trip-1", fare: 50 } });
  const events = await outbox.listUnpublished(10);
  expect(events.map(item => item.eventType)).toEqual(["trip.completed", "payment.requested"]);
  expect(events[1]).toMatchObject({ correlationId: event.correlationId, payload: { tripId: "trip-1", amount: 50 } });
});

test("invalid completion amount leaves outbox empty", async () => {
  const outbox = new InMemoryOutboxRepository();
  await expect(new OutboxEventPublisher(outbox).publish({ ...event, eventType: "trip.completed", payload: { id: "trip-1", fare: null } })).rejects.toThrow();
  expect(await outbox.listUnpublished(10)).toEqual([]);
});
