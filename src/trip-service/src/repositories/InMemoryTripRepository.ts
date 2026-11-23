import { randomUUID } from "node:crypto";
import type { ITripRepository } from "./ITripRepository";
import type { Trip, TripStatus } from "../types/trip";

/** In-process repository used to run the Trip Service without external systems. */
export class InMemoryTripRepository implements ITripRepository {
  private readonly tripsById = new Map<string, Trip>();
  private readonly tripIdByIdempotencyKey = new Map<string, string>();

  /** Creates a REQUESTED trip, returning the existing trip for a repeated key. */
  async create(input: {
    passengerId: string;
    pickup: Trip["pickup"];
    dropoff: Trip["dropoff"];
    idempotencyKey: string;
  }): Promise<Trip> {
    const existingId = this.tripIdByIdempotencyKey.get(input.idempotencyKey);
    if (existingId !== undefined) {
      const existingTrip = this.tripsById.get(existingId);
      if (existingTrip !== undefined) return this.copyTrip(existingTrip);
    }

    const trip: Trip = {
      id: randomUUID(),
      passengerId: input.passengerId,
      driverId: null,
      status: "REQUESTED",
      pickup: { ...input.pickup },
      dropoff: { ...input.dropoff },
      fare: null,
      idempotencyKey: input.idempotencyKey,
      requestedAt: new Date().toISOString(),
      acceptedAt: null,
      startedAt: null,
      completedAt: null,
      cancelledAt: null,
      cancelReason: null,
    };

    this.tripsById.set(trip.id, trip);
    this.tripIdByIdempotencyKey.set(trip.idempotencyKey, trip.id);
    return this.copyTrip(trip);
  }

  /** Finds and copies a trip by its identifier. */
  async findById(id: string): Promise<Trip | null> {
    const trip = this.tripsById.get(id);
    return trip === undefined ? null : this.copyTrip(trip);
  }

  /** Finds and copies a trip by its idempotency key. */
  async findByIdempotencyKey(key: string): Promise<Trip | null> {
    const id = this.tripIdByIdempotencyKey.get(key);
    if (id === undefined) return null;
    const trip = this.tripsById.get(id);
    return trip === undefined ? null : this.copyTrip(trip);
  }

  /** Updates a trip's status and fields, throwing when the trip does not exist. */
  async updateStatus(id: string, status: TripStatus, patch: Partial<Trip> = {}): Promise<Trip> {
    const trip = this.tripsById.get(id);
    if (trip === undefined) throw new Error(`Trip ${id} does not exist`);

    const updatedTrip: Trip = {
      ...trip,
      ...patch,
      id: trip.id,
      idempotencyKey: trip.idempotencyKey,
      status,
      pickup: patch.pickup === undefined ? { ...trip.pickup } : { ...patch.pickup },
      dropoff: patch.dropoff === undefined ? { ...trip.dropoff } : { ...patch.dropoff },
    };
    this.tripsById.set(id, updatedTrip);
    return this.copyTrip(updatedTrip);
  }

  /** Returns copied trips matching every supplied filter. */
  async list(filter: { passengerId?: string; driverId?: string; status?: TripStatus }): Promise<Trip[]> {
    return [...this.tripsById.values()]
      .filter((trip) => filter.passengerId === undefined || trip.passengerId === filter.passengerId)
      .filter((trip) => filter.driverId === undefined || trip.driverId === filter.driverId)
      .filter((trip) => filter.status === undefined || trip.status === filter.status)
      .sort((left, right) => right.requestedAt.localeCompare(left.requestedAt))
      .map((trip) => this.copyTrip(trip));
  }

  private copyTrip(trip: Trip): Trip {
    return { ...trip, pickup: { ...trip.pickup }, dropoff: { ...trip.dropoff } };
  }
}
