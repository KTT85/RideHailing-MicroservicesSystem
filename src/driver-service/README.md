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

## JWT settings

The service verifies HS256 tokens from User Service. It requires the same Base64 `JWT_SECRET` and `JWT_ISSUER` as User Service; the issuer defaults to `ds03-user-service`. The decoded secret must contain at least 32 bytes. Do not commit the secret.

## Database

On a new MySQL volume, `docker/mysql/init/02-driver-schema.sql` creates `drivers` and `vehicles` after the database initialization script. Existing volumes are not reinitialized automatically; apply that schema through an administrator connection before using the profile API.
