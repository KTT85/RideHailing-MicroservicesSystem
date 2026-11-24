import { randomUUID } from "node:crypto";
import type { PrismaClient, Trip } from "@prisma/client";
import { changeRide, handleRideResult } from "../../src/trip-service/src/services/rideWorkflow";

function fixture(overrides: Partial<Trip>) {
  let row = {
    id: randomUUID(), passengerId: randomUUID(), driverId: randomUUID(), correlationId: randomUUID(),
    fare: 50000, paymentMethod: "TRANSFER", driverReleased: false, ...overrides,
  } as Trip;
  const commands: string[] = [];
  const processed = new Set<string>();
  const tx = {
    $queryRaw: jest.fn(async () => [{ id: 1 }]),
    trip: {
      findUnique: jest.fn(async () => row),
      update: jest.fn(async ({ data }: { data: Partial<Trip> }) => {
        row = { ...row, ...data };
        return row;
      }),
    },
    outboxEvent: { create: jest.fn(async ({ data }: { data: { eventType: string } }) => commands.push(data.eventType)) },
    processedEvent: {
      findUnique: jest.fn(async ({ where }: { where: { eventId: string } }) => processed.has(where.eventId)),
      create: jest.fn(async ({ data }: { data: { eventId: string } }) => processed.add(data.eventId)),
    },
  };
  const db = { $transaction: (work: (context: typeof tx) => unknown) => work(tx) } as unknown as PrismaClient;
  const passenger = { userId: row.passengerId, role: "PASSENGER" } as const;
  const driver = { driverId: row.driverId!, role: "DRIVER" } as const;
  const event = (eventType: string, payload: Record<string, unknown>, eventId = randomUUID()) => ({
    eventType, eventId, correlationId: row.correlationId!, source: "test",
    occurredAt: new Date().toISOString(), payload: { ...payload, tripId: row.id },
  });
  return { db, passenger, driver, event, tripId: row.id, current: () => row, commands };
}

test("late successful payment schedules one refund only after driver release", async () => {
  const { db, passenger, tripId, current, event, commands } = fixture({ status: "PAYMENT_PENDING", paymentState: "PENDING" });
  const payment = { passengerId: passenger.userId, method: "TRANSFER", amount: 50000, currency: "VND", paymentId: randomUUID() };
  const succeeded = event("PaymentSucceeded", { ...payment, operationType: "PAY" });
  await changeRide(db, tripId, passenger, "cancel");
  await handleRideResult(db, event("DriverReleased", { driverId: current().driverId }));
  expect(current()).toMatchObject({ status: "COMPENSATING", driverReleased: true, paymentState: "PENDING" });
  expect(commands).toEqual(["DriverReleaseRequested"]);

  await handleRideResult(db, succeeded);
  await handleRideResult(db, succeeded);
  await handleRideResult(db, event("PaymentSucceeded", { ...payment, operationType: "PAY" }));
  expect(commands).toEqual(["DriverReleaseRequested", "PaymentRefundRequested"]);
  expect(current()).toMatchObject({ status: "COMPENSATING", paymentState: "REFUND_PENDING" });

  await handleRideResult(db, event("PaymentRefunded", { ...payment, operationType: "REFUND", refundedAmount: 50000 }));
  expect(current()).toMatchObject({ status: "CANCELLED", paymentState: "REFUNDED" });
  expect(commands).toEqual(["DriverReleaseRequested", "PaymentRefundRequested", "TripCancelled"]);
});

test("Driver must confirm start before completion and cancellation cannot interrupt STARTING", async () => {
  const { db, passenger, driver, tripId, current, event, commands } = fixture({ status: "ACCEPTED", paymentState: "SUCCESS" });
  expect(await changeRide(db, tripId, driver, "start")).toMatchObject({ status: "STARTING" });
  await expect(changeRide(db, tripId, passenger, "cancel")).rejects.toMatchObject({ statusCode: 409 });
  await expect(changeRide(db, tripId, driver, "complete")).rejects.toMatchObject({ statusCode: 409 });
  await handleRideResult(db, event("DriverStarted", { driverId: randomUUID() }));
  expect(current().status).toBe("STARTING");

  await handleRideResult(db, event("DriverStarted", { driverId: driver.driverId }));
  expect(await changeRide(db, tripId, driver, "complete")).toMatchObject({ status: "COMPLETED" });
  expect(commands).toEqual(["DriverStartRequested", "TripStarted", "TripCompleted"]);
});
