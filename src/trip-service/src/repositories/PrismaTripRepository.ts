import { Prisma } from "@prisma/client";
import type { ITripRepository } from "./ITripRepository";
import type { Trip, TripLocation, TripStatus } from "../types/trip";

type TripRow = Prisma.TripGetPayload<Record<string, never>>;

function parseLocation(value: Prisma.JsonValue): TripLocation {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error("Stored trip location is not a JSON object");
  }
  const record = value as Prisma.JsonObject;
  const lat = record.lat;
  const lng = record.lng;
  const address = record.address;
  if (typeof lat !== "number" || typeof lng !== "number") {
    throw new Error("Stored trip location must contain numeric lat and lng");
  }
  if (address !== undefined && typeof address !== "string") {
    throw new Error("Stored trip location address must be a string");
  }
  return address === undefined ? { lat, lng } : { lat, lng, address };
}

function toTrip(row: TripRow): Trip {
  return {
    id: row.id,
    passengerId: row.passengerId,
    driverId: row.driverId,
    status: row.status,
    pickup: parseLocation(row.pickup),
    dropoff: parseLocation(row.dropoff),
    fare: row.fare,
    idempotencyKey: row.idempotencyKey,
    requestedAt: row.requestedAt.toISOString(),
    acceptedAt: row.acceptedAt?.toISOString() ?? null,
    startedAt: row.startedAt?.toISOString() ?? null,
    completedAt: row.completedAt?.toISOString() ?? null,
    cancelledAt: row.cancelledAt?.toISOString() ?? null,
    cancelReason: row.cancelReason,
    paymentId: row.paymentId ?? undefined,
  };
}

function toDate(value: string | null | undefined): Date | null | undefined {
  if (value === undefined) return undefined;
  if (value === null) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) throw new Error(`Invalid date: ${value}`);
  return date;
}

export class PrismaTripRepository implements ITripRepository {
  constructor(private readonly prisma: Prisma.TransactionClient) {}

  /** Creates a trip using Prisma and returns its API representation. */
  async create(input: {
    passengerId: string;
    pickup: Trip["pickup"];
    dropoff: Trip["dropoff"];
    idempotencyKey: string;
  }): Promise<Trip> {
    const row = await this.prisma.trip.create({
      data: {
        passengerId: input.passengerId,
        pickup: { ...input.pickup },
        dropoff: { ...input.dropoff },
        idempotencyKey: input.idempotencyKey,
      },
    });
    return toTrip(row);
  }

  /** Looks up a trip by UUID. */
  async findById(id: string): Promise<Trip | null> {
    const row = await this.prisma.trip.findUnique({ where: { id } });
    return row === null ? null : toTrip(row);
  }

  /** Looks up a trip by its unique idempotency key. */
  async findByIdempotencyKey(key: string): Promise<Trip | null> {
    const row = await this.prisma.trip.findUnique({ where: { idempotencyKey: key } });
    return row === null ? null : toTrip(row);
  }

  /** Persists the new status and any supplied mutable fields. */
  async updateStatus(id: string, status: TripStatus, patch: Partial<Trip> = {}): Promise<Trip> {
    const data: Prisma.TripUpdateInput = { status };
    if (patch.passengerId !== undefined) data.passengerId = patch.passengerId;
    if (patch.driverId !== undefined) data.driverId = patch.driverId;
    if (patch.pickup !== undefined) data.pickup = { ...patch.pickup };
    if (patch.dropoff !== undefined) data.dropoff = { ...patch.dropoff };
    if (patch.fare !== undefined) data.fare = patch.fare;
    if (patch.paymentId !== undefined) data.paymentId = patch.paymentId;
    if (patch.idempotencyKey !== undefined) data.idempotencyKey = patch.idempotencyKey;
    if (patch.acceptedAt !== undefined) data.acceptedAt = toDate(patch.acceptedAt);
    if (patch.startedAt !== undefined) data.startedAt = toDate(patch.startedAt);
    if (patch.completedAt !== undefined) data.completedAt = toDate(patch.completedAt);
    if (patch.cancelledAt !== undefined) data.cancelledAt = toDate(patch.cancelledAt);
    if (patch.cancelReason !== undefined) data.cancelReason = patch.cancelReason;

    return toTrip(await this.prisma.trip.update({ where: { id }, data }));
  }

  /** Returns trips matching the supplied passenger, driver, and status filters. */
  async list(filter: {
    passengerId?: string;
    driverId?: string;
    status?: TripStatus;
  }): Promise<Trip[]> {
    const rows = await this.prisma.trip.findMany({
      where: {
        ...(filter.passengerId === undefined ? {} : { passengerId: filter.passengerId }),
        ...(filter.driverId === undefined ? {} : { driverId: filter.driverId }),
        ...(filter.status === undefined ? {} : { status: filter.status }),
      },
      orderBy: { requestedAt: "desc" },
    });
    return rows.map(toTrip);
  }
}
