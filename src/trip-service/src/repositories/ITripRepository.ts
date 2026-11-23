import type { Trip, TripLocation, TripStatus } from "../types/trip";

export interface ITripRepository {
  /** Creates a trip, enforcing idempotency through the unique key. */
  create(input: {
    passengerId: string;
    pickup: Trip["pickup"];
    dropoff: Trip["dropoff"];
    idempotencyKey: string;
  }): Promise<Trip>;

  /** Finds a trip by its identifier, or returns null when absent. */
  findById(id: string): Promise<Trip | null>;

  /** Finds a trip by its idempotency key, or returns null when absent. */
  findByIdempotencyKey(key: string): Promise<Trip | null>;

  /** Updates trip status and supported trip fields, then returns the saved trip. */
  updateStatus(id: string, status: TripStatus, patch?: Partial<Trip>): Promise<Trip>;

  /** Lists trips matching all supplied filters. */
  list(filter: { passengerId?: string; driverId?: string; status?: TripStatus }): Promise<Trip[]>;
}

export type { TripLocation };
