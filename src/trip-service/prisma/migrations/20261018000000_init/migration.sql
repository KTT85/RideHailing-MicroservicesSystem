CREATE TYPE "trip_status" AS ENUM ('REQUESTED', 'MATCHING', 'DRIVER_ASSIGNED', 'ACCEPTED', 'REJECTED', 'IN_PROGRESS', 'COMPLETED', 'PAYMENT_PENDING', 'PAYMENT_FAILED', 'COMPENSATING', 'CANCELLED');

CREATE TABLE "trips" (
  "id" UUID NOT NULL,
  "passenger_id" UUID NOT NULL,
  "driver_id" UUID,
  "status" "trip_status" NOT NULL DEFAULT 'REQUESTED',
  "pickup" JSONB NOT NULL,
  "dropoff" JSONB NOT NULL,
  "fare" DOUBLE PRECISION,
  "idempotency_key" VARCHAR(255) NOT NULL,
  "requested_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "accepted_at" TIMESTAMPTZ(6),
  "started_at" TIMESTAMPTZ(6),
  "completed_at" TIMESTAMPTZ(6),
  "cancelled_at" TIMESTAMPTZ(6),
  "cancel_reason" TEXT,
  CONSTRAINT "trips_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "outbox_events" (
  "id" UUID NOT NULL,
  "aggregate_id" UUID NOT NULL,
  "event_type" VARCHAR(100) NOT NULL,
  "payload" JSONB NOT NULL,
  "correlation_id" UUID NOT NULL,
  "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "published_at" TIMESTAMPTZ(6),
  CONSTRAINT "outbox_events_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "processed_events" (
  "event_id" UUID NOT NULL,
  "processed_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT "processed_events_pkey" PRIMARY KEY ("event_id")
);

CREATE UNIQUE INDEX "trips_idempotency_key_key" ON "trips"("idempotency_key");
CREATE INDEX "trips_passenger_id_requested_at_idx" ON "trips"("passenger_id", "requested_at");
CREATE INDEX "trips_driver_id_requested_at_idx" ON "trips"("driver_id", "requested_at");
CREATE INDEX "trips_status_idx" ON "trips"("status");
CREATE INDEX "outbox_events_published_at_created_at_idx" ON "outbox_events"("published_at", "created_at");
