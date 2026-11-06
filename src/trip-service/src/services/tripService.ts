import { randomUUID } from "node:crypto";
import type { Trip, TripStatus } from "../types/trip";
import type { ITripRepository } from "../repositories/ITripRepository";
import type { EventEnvelope, IEventPublisher } from "../messaging/IEventPublisher";
import { TripServiceError } from "../middleware/errorHandler";

const SERVICE_NAME = "trip-service";

const NEXT_STATUSES: Readonly<Record<TripStatus, readonly TripStatus[]>> = {
  REQUESTED: ["MATCHING", "CANCELLED"],
  MATCHING: ["DRIVER_ASSIGNED", "CANCELLED"],
  DRIVER_ASSIGNED: ["ACCEPTED", "REJECTED", "CANCELLED"],
  ACCEPTED: ["IN_PROGRESS", "CANCELLED"],
  REJECTED: ["CANCELLED"],
  IN_PROGRESS: ["COMPLETED"],
  COMPLETED: ["PAYMENT_PENDING"],
  PAYMENT_PENDING: ["COMPLETED", "PAYMENT_FAILED", "COMPENSATING"],
  PAYMENT_FAILED: ["COMPENSATING"],
  COMPENSATING: ["CANCELLED"],
  CANCELLED: [],
};

const EVENT_TYPES: Readonly<Record<TripStatus, string>> = {
  REQUESTED: "trip.requested",
  MATCHING: "trip.matching",
  DRIVER_ASSIGNED: "trip.driver_assigned",
  ACCEPTED: "trip.accepted",
  REJECTED: "trip.rejected",
  IN_PROGRESS: "trip.started",
  COMPLETED: "trip.completed",
  PAYMENT_PENDING: "trip.payment_pending",
  PAYMENT_FAILED: "trip.payment_failed",
  COMPENSATING: "trip.compensating",
  CANCELLED: "trip.cancelled",
};

/** Request context attached to every event created by an API call. */
export interface TripCommandContext {
  correlationId: string;
}

/** Coordinates trip persistence, state transitions, and event publication. */
export class TripService {
  private readonly pendingCreates = new Map<string, Promise<Trip>>();

  /** Creates a service backed by the supplied repository and event publisher. */
  constructor(
    private readonly repository: ITripRepository,
    private readonly eventPublisher: IEventPublisher,
  ) {}

  /** Creates a REQUESTED trip, reusing the saved trip for a repeated idempotency key. */
  async createTrip(
    input: {
      passengerId: string;
      pickup: Trip["pickup"];
      dropoff: Trip["dropoff"];
      idempotencyKey: string;
    },
    context: TripCommandContext,
  ): Promise<Trip> {
    const pendingCreate = this.pendingCreates.get(input.idempotencyKey);
    if (pendingCreate !== undefined) return pendingCreate;

    const createPromise = this.createOrReuseTrip(input, context);
    this.pendingCreates.set(input.idempotencyKey, createPromise);
    try {
      return await createPromise;
    } finally {
      if (this.pendingCreates.get(input.idempotencyKey) === createPromise) {
        this.pendingCreates.delete(input.idempotencyKey);
      }
    }
  }

  /** Gets one trip or raises TRIP_NOT_FOUND. */
  async getTrip(id: string): Promise<Trip> {
    const trip = await this.repository.findById(id);
    if (trip === null) throw new TripServiceError(404, "TRIP_NOT_FOUND", `Trip ${id} was not found`);
    return trip;
  }

  /** Lists trips matching the provided filters. */
  async listTrips(filter: { passengerId?: string; driverId?: string; status?: TripStatus }): Promise<Trip[]> {
    return this.repository.list(filter);
  }

  /** Accepts a trip after progressing it through matching and driver assignment. */
  async acceptTrip(id: string, context: TripCommandContext, driverId?: string): Promise<Trip> {
    await this.ensureDriverAssigned(id, context, driverId);
    return this.transitionStatus(id, "ACCEPTED", { acceptedAt: new Date().toISOString() }, context);
  }

  /** Rejects a driver-assigned trip. */
  async rejectTrip(id: string, context: TripCommandContext, driverId?: string): Promise<Trip> {
    await this.ensureDriverAssigned(id, context, driverId);
    return this.transitionStatus(id, "REJECTED", {}, context);
  }

  /** Starts an accepted trip. */
  async startTrip(id: string, context: TripCommandContext): Promise<Trip> {
    return this.transitionStatus(id, "IN_PROGRESS", { startedAt: new Date().toISOString() }, context);
  }

  /** Completes an in-progress trip and stores the optional final fare. */
  async completeTrip(id: string, context: TripCommandContext, fare?: number): Promise<Trip> {
    const patch: Partial<Trip> = { completedAt: new Date().toISOString() };
    if (fare !== undefined) patch.fare = fare;
    return this.transitionStatus(id, "COMPLETED", patch, context);
  }

  /** Cancels a trip before it enters IN_PROGRESS, or after compensation. */
  async cancelTrip(id: string, context: TripCommandContext, reason?: string): Promise<Trip> {
    const patch: Partial<Trip> = { cancelledAt: new Date().toISOString(), cancelReason: reason ?? null };
    return this.transitionStatus(id, "CANCELLED", patch, context);
  }

  private async createOrReuseTrip(
    input: {
      passengerId: string;
      pickup: Trip["pickup"];
      dropoff: Trip["dropoff"];
      idempotencyKey: string;
    },
    context: TripCommandContext,
  ): Promise<Trip> {
    const existingTrip = await this.repository.findByIdempotencyKey(input.idempotencyKey);
    if (existingTrip !== null) return existingTrip;

    const trip = await this.repository.create(input);
    await this.publishStatusEvent(trip, context);
    return trip;
  }

  private async ensureDriverAssigned(id: string, context: TripCommandContext, driverId?: string): Promise<void> {
    let trip = await this.getTrip(id);
    if (trip.status === "REQUESTED") {
      trip = await this.transitionStatus(id, "MATCHING", {}, context);
    }
    if (trip.status === "MATCHING") {
      const patch: Partial<Trip> = {};
      if (driverId !== undefined) patch.driverId = driverId;
      await this.transitionStatus(id, "DRIVER_ASSIGNED", patch, context);
      return;
    }
    if (trip.status === "DRIVER_ASSIGNED") return;

    throw this.invalidTransition(trip.status, "DRIVER_ASSIGNED");
  }

  private async transitionStatus(
    id: string,
    nextStatus: TripStatus,
    patch: Partial<Trip>,
    context: TripCommandContext,
  ): Promise<Trip> {
    const currentTrip = await this.getTrip(id);
    if (!NEXT_STATUSES[currentTrip.status].includes(nextStatus)) {
      throw this.invalidTransition(currentTrip.status, nextStatus);
    }

    const updatedTrip = await this.repository.updateStatus(id, nextStatus, patch);
    await this.publishStatusEvent(updatedTrip, context);
    return updatedTrip;
  }

  private invalidTransition(currentStatus: TripStatus, nextStatus: TripStatus): TripServiceError {
    return new TripServiceError(
      409,
      "INVALID_STATE_TRANSITION",
      `Cannot transition trip from ${currentStatus} to ${nextStatus}`,
    );
  }

  private async publishStatusEvent(trip: Trip, context: TripCommandContext): Promise<void> {
    const envelope: EventEnvelope<Trip> = {
      eventId: randomUUID(),
      eventType: EVENT_TYPES[trip.status],
      correlationId: context.correlationId,
      source: SERVICE_NAME,
      occurredAt: new Date().toISOString(),
      payload: trip,
    };
    await this.eventPublisher.publish(envelope);
  }
}
