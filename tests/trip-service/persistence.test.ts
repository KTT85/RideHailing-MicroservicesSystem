import { randomUUID } from "node:crypto";
import { PrismaClient } from "@prisma/client";
import { createDurableTripService, handleDurableTripEvent, tripTransaction } from "../../src/trip-service/src/services/durableTripService";
import { PrismaTripRepository } from "../../src/trip-service/src/repositories/PrismaTripRepository";

const integration = process.env.TRIP_DB_TESTS === "1" ? describe : describe.skip;
integration("PostgreSQL integration (requires migrated test database)", () => {
  const db = new PrismaClient();
  const ids: string[] = [];
  const events: string[] = [];
  const input = () => ({ passengerId: randomUUID(), pickup: { lat: 10, lng: 106 },
    dropoff: { lat: 11, lng: 107 }, idempotencyKey: randomUUID() });
  afterAll(async () => {
    await db.processedEvent.deleteMany({ where: { eventId: { in: events } } });
    await db.outboxEvent.deleteMany({ where: { aggregateId: { in: ids } } });
    await db.trip.deleteMany({ where: { id: { in: ids } } });
    await db.$disconnect();
  });
  test("concurrent idempotency, persistent payment and duplicate delivery", async () => {
    const service = createDurableTripService(db);
    const body = input();
    const context = { correlationId: "integration-test" };
    const [trip, duplicate] = await Promise.all([service.createTrip(body, context),
      createDurableTripService(db).createTrip(body, context)]);
    ids.push(trip.id);
    expect(duplicate.id).toBe(trip.id);
    expect(await db.outboxEvent.count({ where: { aggregateId: trip.id } })).toBe(1);
    await service.acceptTrip(trip.id, context, randomUUID());
    await service.startTrip(trip.id, context);
    await expect(service.completeTrip(trip.id, context)).rejects.toThrow("fare");
    expect((await service.getTrip(trip.id)).status).toBe("IN_PROGRESS");
    await service.completeTrip(trip.id, context, 50000);
    expect(await db.outboxEvent.count({ where: { aggregateId: trip.id, eventType: "payment.requested" } })).toBe(1);
    const event = { eventId: randomUUID(), eventType: "payment.completed", correlationId: context.correlationId,
      source: "payment-service", occurredAt: new Date().toISOString(),
      payload: { tripId: trip.id, paymentId: randomUUID(), amount: 50000 } };
    events.push(event.eventId);
    await handleDurableTripEvent(db, event);
    await db.$disconnect();
    await handleDurableTripEvent(db, event);
    expect((await service.getTrip(trip.id)).paymentId).toBe(event.payload.paymentId);
    expect(await db.processedEvent.count({ where: { eventId: event.eventId } })).toBe(1);
  }, 30000);
  test("failure after trip insert rolls back database mutation", async () => {
    const body = input();
    await expect(tripTransaction(db, async tx => {
      const trip = await new PrismaTripRepository(tx).create(body);
      ids.push(trip.id);
      throw new Error("simulated outbox failure");
    })).rejects.toThrow("simulated outbox failure");
    expect(await db.trip.findUnique({ where: { idempotencyKey: body.idempotencyKey } })).toBeNull();
  });
  test("compensation is durable and emitted once", async () => {
    const service = createDurableTripService(db);
    const context = { correlationId: randomUUID() };
    const trip = await service.createTrip(input(), context);
    ids.push(trip.id);
    await service.acceptTrip(trip.id, context);
    await service.startTrip(trip.id, context);
    await service.completeTrip(trip.id, context, 100);
    const event = { eventId: randomUUID(), eventType: "payment.failed", correlationId: context.correlationId,
      source: "payment-service", occurredAt: new Date().toISOString(), payload: { tripId: trip.id, reason: "declined" } };
    events.push(event.eventId);
    await handleDurableTripEvent(db, event);
    await db.$disconnect();
    await handleDurableTripEvent(db, event);
    expect((await service.getTrip(trip.id)).status).toBe("CANCELLED");
    expect(await db.outboxEvent.count({ where: { aggregateId: trip.id, eventType: "trip.cancelled" } })).toBe(1);
  }, 30000);
});
