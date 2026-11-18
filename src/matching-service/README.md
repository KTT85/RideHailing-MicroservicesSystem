# Matching Service

Stateless driver matching service (no database). Consumes trip request events from RabbitMQ, queries nearest `AVAILABLE` drivers from Driver Service over REST, reserves a driver atomically (OCC fallback across candidates on `409 Conflict`), and publishes the matching outcome.

## Workflow & Contracts

1. **Inbound Queue (`matching.commands`)**:
   - Bound to durable topic exchange `ride.events` (`MATCHING_EVENTS_EXCHANGE`) for routing keys `TripRequested` and `trip.requested`.
   - Validates canonical lowercase UUIDs (`eventId`, `correlationId`, `tripId`/`id`), coordinates (`[-90, 90]`, `[-180, 180]`), and `vehicleType` (`BIKE` or `CAR`).
2. **REST Calls to Driver Service (`DRIVER_SERVICE_URL`)**:
   - Sends `X-Service-Token: <DRIVER_INTERNAL_TOKEN>` (`>= 32` characters).
   - `GET /internal/drivers/candidates`: fetches nearest `AVAILABLE` candidates ordered by Haversine distance.
   - `POST /internal/drivers/{driverId}/reserve`: attempts atomic reservation sequentially; on `409 Conflict` or `404 Not Found`, automatically falls back to the next nearest candidate.
3. **Outbound Events (`ride.events`)**:
   - Publishes `DriverAssigned` (`driver.assigned` for dot-case inbound events) when a driver is reserved, or `NoDriverAvailable` (`driver.unavailable`) when no candidate can be reserved, verified via correlated broker ACK.
4. **Idempotency & Retry / DLQ**:
   - Bounded in-memory LRU deduplication prevents duplicate reservations on redelivered `eventId`/`tripId`.
   - Transient REST/broker failures retry via `matching.retry` (TTL `5000` ms, up to `3` attempts) and dead-letter to `matching.dlq`; malformed or invalid payloads go directly to `matching.dlq`.
