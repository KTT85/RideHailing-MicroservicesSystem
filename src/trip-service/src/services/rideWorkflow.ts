import { createHash, randomUUID } from "node:crypto";
import { Prisma, type PrismaClient, type Trip } from "@prisma/client";
import { z } from "zod";
import type { EventEnvelope } from "../messaging/IEventPublisher";
import { TripServiceError } from "../middleware/errorHandler";
import { tripTransaction } from "./durableTripService";

const location = z.object({
  lat: z.number().min(-90).max(90),
  lng: z.number().min(-180).max(180),
  address: z.string().trim().max(512).optional(),
});
export const rideInput = z.object({
  pickup: location,
  dropoff: location,
  vehicleType: z.enum(["BIKE", "CAR"]),
  paymentMethod: z.enum(["TRANSFER", "CASH"]),
}).strict().refine(input => input.pickup.lat !== input.dropoff.lat || input.pickup.lng !== input.dropoff.lng,
  { message: "Pickup and dropoff must differ" });
export const uuid = z.string().uuid().regex(/^[a-f0-9-]+$/);
export const rideActions = z.enum(["pay", "accept", "reject", "start", "complete", "cancel", "confirm-cash"]);
export const demoFaults = z.enum(["F01", "F02"]);
export type RideActor = { userId: string; role: "PASSENGER" } | { driverId: string; role: "DRIVER" };
type Tx = Prisma.TransactionClient;
type Ride = Trip & { correlationId: string; paymentMethod: string; fare: number };
const fare = 50_000;

function isRide(trip: Trip | null): trip is Ride {
  return Boolean(trip?.correlationId && trip.paymentMethod && trip.fare);
}

function notFound(): never {
  throw new TripServiceError(404, "RIDE_NOT_FOUND", "Ride was not found");
}

function conflict(trip: Ride): never {
  throw new TripServiceError(409, "RIDE_STATE_CONFLICT",
    "Operation is unavailable in state " + trip.status + " (payment " + trip.paymentState + ")");
}

function visible(trip: Ride, actor: RideActor): boolean {
  return actor.role === "PASSENGER" ? actor.userId === trip.passengerId : actor.driverId === trip.driverId;
}

export function rideView(trip: Ride) {
  const { idempotencyKey: _key, ...view } = trip;
  return view;
}

function emit(tx: Tx, trip: Ride, eventType: string, payload: Prisma.InputJsonObject) {
  return tx.outboxEvent.create({
    data: { aggregateId: trip.id, eventType, correlationId: trip.correlationId, payload: { tripId: trip.id, ...payload } },
  });
}

function payment(trip: Ride) {
  return { passengerId: trip.passengerId, method: trip.paymentMethod, amount: trip.fare, currency: "VND" };
}

function recipients(trip: Ride) {
  return { passengerId: trip.passengerId, driverId: trip.driverId, driverUserId: trip.driverUserId };
}

function paymentCommand(tx: Tx, trip: Ride, eventType: string, operation: string) {
  return emit(tx, trip, eventType, { ...payment(trip), idempotencyKey: trip.id + ":" + operation });
}

async function update(tx: Tx, trip: Ride, data: Prisma.TripUpdateInput): Promise<Ride> {
  const updated = await tx.trip.update({ where: { id: trip.id }, data });
  if (!isRide(updated)) throw new Error("Ride metadata is missing");
  return updated;
}

async function finishCancellation(tx: Tx, trip: Ride): Promise<Ride> {
  await emit(tx, trip, "TripCancelled", {
    ...recipients(trip), reason: trip.cancelReason,
  });
  return update(tx, trip, { status: "CANCELLED", cancelledAt: new Date() });
}

async function advanceCancellation(tx: Tx, trip: Ride): Promise<Ride> {
  if (!trip.driverReleased) return trip;
  switch (trip.paymentState) {
    case "SUCCESS":
      await paymentCommand(tx, trip, "PaymentRefundRequested", "REFUND");
      return update(tx, trip, { paymentState: "REFUND_PENDING" });
    case "PENDING":
    case "MANUAL_REVIEW":
    case "REFUND_PENDING":
    case "REFUND_FAILED":
      return trip;
    default:
      return finishCancellation(tx, trip);
  }
}

async function requestCancellation(tx: Tx, trip: Ride, reason: string): Promise<Ride> {
  if (trip.driverId) await emit(tx, trip, "DriverReleaseRequested", { driverId: trip.driverId, reason });
  return update(tx, trip, { status: "COMPENSATING", cancelReason: reason });
}

export function createRide(db: PrismaClient, passengerId: string, key: string, correlationId: string,
  body: z.infer<typeof rideInput>, demoFault?: z.infer<typeof demoFaults>) {
  return tripTransaction(db, async tx => {
    const digest = createHash("sha256").update(JSON.stringify([passengerId, key])).digest("hex");
    const idempotencyKey = "ride:" + digest;
    const old = await tx.trip.findUnique({ where: { idempotencyKey } });
    if (isRide(old)) {
      const sameLocation = (stored: Prisma.JsonValue, expected: z.infer<typeof location>) => {
        const parsed = location.parse(stored);
        return parsed.lat === expected.lat && parsed.lng === expected.lng && parsed.address === expected.address;
      };
      if (old.passengerId !== passengerId || old.demoFault !== (demoFault ?? null) ||
        old.vehicleType !== body.vehicleType || old.paymentMethod !== body.paymentMethod
        || !sameLocation(old.pickup, body.pickup) || !sameLocation(old.dropoff, body.dropoff)) {
        throw new TripServiceError(409, "IDEMPOTENCY_CONFLICT", "Key already belongs to a different request");
      }
      return { ride: old, created: false };
    }
    const row = await tx.trip.create({
      data: { id: randomUUID(), passengerId, idempotencyKey, correlationId, fare, status: "REQUESTED",
        paymentState: "NOT_REQUESTED", demoFault, ...body },
    });
    if (!isRide(row)) throw new Error("Ride metadata is missing");
    await emit(tx, row, "TripRequested", {
      passengerId, vehicleType: body.vehicleType, pickupLatitude: body.pickup.lat, pickupLongitude: body.pickup.lng,
      ...(demoFault === "F01" ? { demoFault } : {}),
    });
    return { ride: row, created: true };
  });
}

export async function readRide(db: PrismaClient, id: string, actor: RideActor): Promise<Ride> {
  const trip = await db.trip.findUnique({ where: { id } });
  if (!isRide(trip) || !visible(trip, actor)) notFound();
  return trip;
}

export function changeRide(db: PrismaClient, id: string, actor: RideActor,
  action: z.infer<typeof rideActions>): Promise<Ride> {
  return tripTransaction(db, async tx => {
    const trip = await tx.trip.findUnique({ where: { id } });
    if (!isRide(trip) || !visible(trip, actor)) notFound();
    if (["pay", "cancel"].includes(action) !== (actor.role === "PASSENGER")) {
      throw new TripServiceError(403, "RIDE_ROLE_REQUIRED", "Role cannot perform this action");
    }
    const now = new Date();
    switch (action) {
      case "pay":
        if (trip.paymentMethod !== "TRANSFER") conflict(trip);
        if (trip.status === "PAYMENT_PENDING" || trip.paymentState === "SUCCESS") return trip;
        if (trip.status !== "DRIVER_ASSIGNED" || trip.paymentState !== "NOT_REQUESTED") conflict(trip);
        await paymentCommand(tx, trip, "PaymentRequested", "PAY");
        return update(tx, trip, { status: "PAYMENT_PENDING", paymentState: "PENDING" });
      case "accept":
        if (trip.status === "ACCEPTED") return trip;
        if (trip.status !== "MATCHED") conflict(trip);
        return update(tx, trip, { status: "ACCEPTED", acceptedAt: now });
      case "reject":
        if (trip.cancelReason === "driver_rejected" && ["COMPENSATING", "CANCELLED"].includes(trip.status)) return trip;
        if (!["MATCHED", "ACCEPTED"].includes(trip.status)) conflict(trip);
        return requestCancellation(tx, trip, "driver_rejected");
      case "start":
        if (trip.status === "STARTING" || trip.status === "IN_PROGRESS") return trip;
        if (trip.status !== "ACCEPTED") conflict(trip);
        await emit(tx, trip, "DriverStartRequested", { driverId: trip.driverId });
        return update(tx, trip, { status: "STARTING" });
      case "complete":
        if (trip.status === "COMPLETED") return trip;
        if (trip.status !== "IN_PROGRESS") conflict(trip);
        await emit(tx, trip, "TripCompleted", { ...payment(trip), driverId: trip.driverId });
        return update(tx, trip, {
          status: "COMPLETED", completedAt: now,
          ...(trip.paymentMethod === "CASH" ? { paymentState: "AWAITING_CASH_CONFIRMATION" } : {}),
        });
      case "confirm-cash":
        if (trip.status !== "COMPLETED" || trip.paymentMethod !== "CASH") conflict(trip);
        if (trip.paymentState === "PENDING" || trip.paymentState === "SUCCESS") return trip;
        if (trip.paymentState !== "AWAITING_CASH_CONFIRMATION") conflict(trip);
        await paymentCommand(tx, trip, "CashPaymentConfirmationRequested", "CONFIRM_CASH");
        return update(tx, trip, { paymentState: "PENDING" });
      case "cancel":
        if (trip.status === "COMPENSATING" || trip.status === "CANCELLED") return trip;
        if (!["REQUESTED", "DRIVER_ASSIGNED", "PAYMENT_PENDING", "MATCHED", "ACCEPTED"].includes(trip.status)) conflict(trip);
        return requestCancellation(tx, trip, "passenger_cancelled");
    }
  });
}

export async function handleRideResult(db: PrismaClient, event: EventEnvelope<Record<string, unknown>>): Promise<void> {
  const eventId = uuid.parse(event.eventId);
  const id = uuid.parse(event.payload.tripId);
  await tripTransaction(db, async tx => {
    if (await tx.processedEvent.findUnique({ where: { eventId } })) return;
    const trip = await tx.trip.findUnique({ where: { id } });
    // The exchange is shared with standalone service tests.
    if (!isRide(trip) || trip.correlationId !== event.correlationId) return;
    await applyResult(tx, trip, event);
    await tx.processedEvent.create({ data: { eventId } });
  });
}

async function applyResult(tx: Tx, trip: Ride, event: EventEnvelope<Record<string, unknown>>): Promise<void> {
  const { payload } = event;
  const cancelling = trip.status === "COMPENSATING";
  if (event.eventType === "DriverAssigned") {
    const driverId = uuid.parse(payload.driverId);
    const driverUserId = uuid.nullish().parse(payload.driverUserId) ?? null;
    if (trip.status === "CANCELLED" || trip.status === "COMPLETED") {
      await emit(tx, trip, "DriverReleaseRequested", { driverId, reason: "terminal_trip" });
      return;
    }
    if (!trip.driverId && (trip.status === "REQUESTED" || cancelling)) {
      const assigned = await update(tx, trip, { driverId, driverUserId,
        ...(trip.demoFault === "F01" && payload.reservationReused === true ? { demoFaultAt: new Date() } : {}),
        status: cancelling ? "COMPENSATING" : trip.paymentMethod === "CASH" ? "MATCHED" : "DRIVER_ASSIGNED",
      });
      if (cancelling) await emit(tx, trip, "DriverReleaseRequested", { driverId, reason: trip.cancelReason });
      else if (assigned.status === "MATCHED") await emit(tx, assigned, "TripConfirmed", recipients(assigned));
    }
    return;
  }
  if (event.eventType === "NoDriverAvailable") {
    if (!trip.driverId && (trip.status === "REQUESTED" || cancelling)) {
      const terminal = await update(tx, trip, { driverReleased: true, cancelReason: trip.cancelReason ?? "no_driver_available" });
      await finishCancellation(tx, terminal);
    }
    return;
  }
  if (event.eventType === "DriverStarted") {
    if (trip.status === "STARTING" && payload.driverId === trip.driverId) {
      await update(tx, trip, { status: "IN_PROGRESS", startedAt: new Date(event.occurredAt) });
      await emit(tx, trip, "TripStarted", recipients(trip));
    }
    return;
  }
  if (event.eventType === "DriverReleased") {
    if (payload.driverId === trip.driverId && trip.driverId && !trip.driverReleased && (cancelling || trip.status === "COMPLETED")) {
      const released = await update(tx, trip, { driverReleased: true });
      if (cancelling) await advanceCancellation(tx, released);
    }
    return;
  }

  if (payload.passengerId !== trip.passengerId || payload.method !== trip.paymentMethod || Number(payload.amount) !== trip.fare
    || payload.currency !== "VND" || (trip.paymentId && trip.paymentId !== payload.paymentId)) {
    throw new Error("Payment result does not match ride");
  }
  const paymentId = uuid.parse(payload.paymentId);
  if (payload.operationType === "REFUND") {
    if (!cancelling || !["REFUND_PENDING", "REFUND_FAILED"].includes(trip.paymentState ?? "")) return;
    if (event.eventType === "PaymentRefunded" && Number(payload.refundedAmount) === trip.fare) {
      const refunded = await update(tx, trip, { paymentState: "REFUNDED", paymentId });
      await advanceCancellation(tx, refunded);
    } else if (event.eventType === "PaymentRefunded") {
      throw new Error("Refund amount does not match ride");
    } else if (event.eventType === "PaymentRefundFailed") {
      await update(tx, trip, { paymentState: "REFUND_FAILED", paymentId });
    }
    return;
  }

  const expectedOperation = trip.paymentMethod === "TRANSFER" ? "PAY" : "CONFIRM_CASH";
  if (payload.operationType !== expectedOperation || !["PENDING", "MANUAL_REVIEW"].includes(trip.paymentState ?? "")) return;
  if (event.eventType === "PaymentSucceeded") {
    const paid = await update(tx, trip, { paymentState: "SUCCESS", paymentId,
      ...(trip.status === "PAYMENT_PENDING" ? { status: "MATCHED" } : {}),
    });
    if (cancelling) await advanceCancellation(tx, paid);
    else if (trip.status === "PAYMENT_PENDING") await emit(tx, paid, "TripConfirmed", recipients(paid));
  } else if (event.eventType === "PaymentFailed") {
    const startCancellation = !cancelling && trip.status === "PAYMENT_PENDING";
    const failed = await update(tx, trip, { paymentState: "FAILED", paymentId,
      ...(startCancellation ? { status: "COMPENSATING", cancelReason: "payment_failed" } : {}),
    });
    if (startCancellation) await emit(tx, trip, "DriverReleaseRequested", { driverId: trip.driverId, reason: "payment_failed" });
    if (cancelling) await advanceCancellation(tx, failed);
  } else if (event.eventType === "PaymentReviewRequired") {
    await update(tx, trip, { paymentState: "MANUAL_REVIEW", paymentId });
  }
}
