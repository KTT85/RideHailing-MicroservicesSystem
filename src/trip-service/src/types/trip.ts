export type TripStatus =
  | "REQUESTED"
  | "MATCHING"
  | "DRIVER_ASSIGNED"
  | "ACCEPTED"
  | "REJECTED"
  | "IN_PROGRESS"
  | "COMPLETED"
  | "PAYMENT_PENDING"
  | "PAYMENT_FAILED"
  | "COMPENSATING"
  | "CANCELLED";

export interface TripLocation {
  lat: number;
  lng: number;
  address?: string;
}

export interface Trip {
  id: string;
  passengerId: string;
  driverId: string | null;
  paymentId?: string | null;
  status: TripStatus;
  pickup: TripLocation;
  dropoff: TripLocation;
  fare: number | null;
  idempotencyKey: string;
  requestedAt: string;
  acceptedAt: string | null;
  startedAt: string | null;
  completedAt: string | null;
  cancelledAt: string | null;
  cancelReason: string | null;
}
