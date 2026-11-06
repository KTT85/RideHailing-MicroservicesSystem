import { z } from "zod";
import type { TripStatus } from "../types/trip";

const TRIP_STATUSES: readonly [TripStatus, ...TripStatus[]] = [
  "REQUESTED",
  "MATCHING",
  "DRIVER_ASSIGNED",
  "ACCEPTED",
  "REJECTED",
  "IN_PROGRESS",
  "COMPLETED",
  "PAYMENT_PENDING",
  "PAYMENT_FAILED",
  "COMPENSATING",
  "CANCELLED",
];

const locationSchema = z
  .object({
    lat: z.number().finite().min(-90).max(90),
    lng: z.number().finite().min(-180).max(180),
    address: z.string().trim().min(1).max(500).optional(),
  })
  .strict();

/** Validates the JSON body used to create a trip. */
export const createTripSchema = z
  .object({
    passengerId: z.string().uuid(),
    pickup: locationSchema,
    dropoff: locationSchema,
  })
  .strict();

/** Validates a UUID trip route parameter. */
export const tripIdParamsSchema = z.object({ id: z.string().uuid() }).strict();

/** Validates the optional filters accepted by GET /api/trips. */
export const listTripsQuerySchema = z
  .object({
    passengerId: z.string().uuid().optional(),
    driverId: z.string().uuid().optional(),
    status: z.enum(TRIP_STATUSES).optional(),
  })
  .strict();

/** Validates the required request correlation header. */
export const correlationIdSchema = z.string().trim().min(1).max(255);

/** Validates the required trip creation idempotency header. */
export const idempotencyKeySchema = z.string().trim().min(1).max(255);

/** Validates the optional fare supplied when completing a trip. */
export const completeTripBodySchema = z
  .object({ fare: z.number().finite().nonnegative().optional() })
  .strict()
  .default({});

/** Validates the optional cancellation reason. */
export const cancelTripBodySchema = z
  .object({ reason: z.string().trim().min(1).max(500).optional() })
  .strict()
  .default({});

/** Validates endpoints whose action does not accept a request body. */
export const emptyActionBodySchema = z.object({}).strict().default({});

/** Validates the optional driver identity submitted when accepting or rejecting. */
export const driverActionBodySchema = z
  .object({ driverId: z.string().uuid().optional() })
  .strict()
  .default({});
