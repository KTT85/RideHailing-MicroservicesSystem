# Driver Service

Driver Service owns driver profiles, vehicles, locations, availability and trip reservations. It stores data only in `driver_db`.

## Create a driver profile

`POST /drivers/profile` requires a valid User Service JWT with role `DRIVER`.

```http
Authorization: Bearer <accessToken>
Content-Type: application/json
```

```json
{
  "licensePlate": "51A-123.45",
  "vehicleType": "BIKE"
}
```

The service takes `userId` from the verified token subject. Clients cannot select a different account ID. A new profile starts `OFFLINE`, with `acceptingTrips` false and no location.

Successful response is HTTP 201:

```json
{
  "driverId": "<driver-uuid>",
  "userId": "<user-uuid>",
  "status": "OFFLINE",
  "acceptingTrips": false,
  "currentTripId": null,
  "latitude": null,
  "longitude": null,
  "vehicle": {
    "vehicleId": "<vehicle-uuid>",
    "licensePlate": "51A-123.45",
    "vehicleType": "BIKE"
  }
}
```

An existing profile for the same user or an already registered license plate returns HTTP 409. Invalid input returns HTTP 400; a missing/invalid token returns HTTP 401; a valid token without the `DRIVER` role returns HTTP 403.

## Read current driver profile

`GET /drivers/me` requires a valid User Service JWT with role `DRIVER` and returns the authenticated driver's profile, status, coordinates, active trip reference, and vehicle in HTTP 200. If the authenticated account has not created a driver profile yet, it returns HTTP 404 (`ProblemDetail`).

## Update driver GPS location

`PUT /drivers/me/location` requires a valid User Service JWT with role `DRIVER`.

```json
{
  "latitude": 10.7626224,
  "longitude": 106.6601724
}
```

Both `latitude` (`[-90, 90]`) and `longitude` (`[-180, 180]`) are required and normalized to 7 decimal places (`DECIMAL(10,7)`). Returns HTTP 200 with the updated profile, HTTP 400 if coordinates are missing or out of bounds, or HTTP 404 if the driver profile does not exist.

## Toggle driver availability

`PATCH /drivers/me/availability` requires a valid User Service JWT with role `DRIVER`.

```json
{
  "acceptingTrips": true
}
```

Transitions the driver between `OFFLINE` (`acceptingTrips = false`) and `AVAILABLE` (`acceptingTrips = true`). Going `AVAILABLE` requires valid GPS coordinates to be set first; otherwise HTTP 409 (`ProblemDetail`) is returned. Availability cannot be toggled while the driver is assigned to an active trip (`RESERVED` or `BUSY`), which also returns HTTP 409.

## Internal candidate lookup (for Matching Service)

`GET /internal/drivers/candidates?pickupLatitude=10.7768890&pickupLongitude=106.7008060&vehicleType=BIKE&limit=5` requires `X-Service-Token: <DRIVER_INTERNAL_TOKEN>` (`>= 32` characters; fails closed with HTTP 401 when unset or mismatched). Returns `AVAILABLE` drivers with matching `vehicleType` ordered by Haversine distance (`distanceKm`) ascending up to `limit` (`1..20`).

## Internal atomic reservation & trip lifecycle (OCC & Saga compensation)

All internal mutation endpoints require `X-Service-Token: <DRIVER_INTERNAL_TOKEN>` and JSON body `{"tripId": "<trip-uuid>"}`:

- `POST /internal/drivers/{driverId}/reserve`: atomically transitions `AVAILABLE` → `RESERVED` with `currentTripId = tripId` via conditional compare-and-swap SQL update (`0` double-bookings under concurrent requests). Idempotent (`200 OK`) if the same `tripId` is already held by `driverId`; returns `409 Conflict` if the driver is unavailable or `tripId` is already held by another driver.
- `POST /internal/drivers/{driverId}/start`: atomically transitions `RESERVED` → `BUSY` for the matching `tripId` (idempotent on `BUSY`; `409` on state/trip mismatch).
- `POST /internal/drivers/{driverId}/release`: atomically clears `currentTripId` and returns `RESERVED`/`BUSY` → `AVAILABLE` (or `OFFLINE` if `acceptingTrips = false`) for both trip completion and Saga compensation. Idempotent (`200 OK`) if already idle; returns `409` if holding a different active trip.

## JWT settings

The service verifies HS256 tokens from User Service. It requires the same Base64 `JWT_SECRET` and `JWT_ISSUER` as User Service; the issuer defaults to `ds03-user-service`. The decoded secret must contain at least 32 bytes. Do not commit the secret.

## Database

On a new MySQL volume, `docker/mysql/init/02-driver-schema.sql` creates `drivers` and `vehicles` after the database initialization script. Existing volumes are not reinitialized automatically; apply that schema through an administrator connection before using the profile API.
