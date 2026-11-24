package vn.edu.ds03.matching.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import vn.edu.ds03.matching.exception.InvalidTripEventException;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TripRequestedEvent(
        String eventId,
        String eventType,
        String correlationId,
        Instant timestamp,
        Instant occurredAt,
        Payload payload) {

    private static final Set<String> SUPPORTED_TYPES = Set.of("TripRequested", "trip.requested");
    private static final Set<String> SUPPORTED_VEHICLES = Set.of("BIKE", "CAR");
    private static final BigDecimal MIN_LATITUDE = new BigDecimal("-90");
    private static final BigDecimal MAX_LATITUDE = new BigDecimal("90");
    private static final BigDecimal MIN_LONGITUDE = new BigDecimal("-180");
    private static final BigDecimal MAX_LONGITUDE = new BigDecimal("180");
    private static final int MAX_COORDINATE_SCALE = 20;

    public record PickupLocation(BigDecimal lat, BigDecimal lng) {
    }

    public record Payload(
            String tripId,
            String id,
            String passengerId,
            BigDecimal pickupLatitude,
            BigDecimal pickupLongitude,
            String vehicleType,
            PickupLocation pickup,
            String demoFault) {

        public Payload(String tripId, String id, String passengerId, BigDecimal pickupLatitude,
                       BigDecimal pickupLongitude, String vehicleType, PickupLocation pickup) {
            this(tripId, id, passengerId, pickupLatitude, pickupLongitude, vehicleType, pickup, null);
        }

        public String resolvedTripId() {
            return tripId != null ? tripId : id;
        }

        public BigDecimal resolvedLatitude() {
            return pickupLatitude != null
                    ? pickupLatitude
                    : (pickup != null ? pickup.lat() : null);
        }

        public BigDecimal resolvedLongitude() {
            return pickupLongitude != null
                    ? pickupLongitude
                    : (pickup != null ? pickup.lng() : null);
        }

        public String resolvedVehicleType() {
            return vehicleType != null && !vehicleType.isBlank() ? vehicleType : "CAR";
        }
    }

    public Instant effectiveTimestamp() {
        return timestamp != null ? timestamp : occurredAt;
    }

    public boolean isDotCaseConvention() {
        return "trip.requested".equals(eventType);
    }

    public void validate() {
        requireCanonicalUuid(eventId, "eventId");
        requireCanonicalUuid(correlationId, "correlationId");
        if (effectiveTimestamp() == null) {
            throw new InvalidTripEventException("Event timestamp is required");
        }
        if (eventType == null || !SUPPORTED_TYPES.contains(eventType)) {
            throw new InvalidTripEventException("Unsupported eventType: " + eventType);
        }
        if (payload == null) {
            throw new InvalidTripEventException("Event payload is required");
        }
        if (payload.tripId() != null && payload.id() != null && !payload.tripId().equals(payload.id())) {
            throw new InvalidTripEventException("Conflicting payload.tripId and payload.id");
        }
        requireCanonicalUuid(payload.resolvedTripId(), "payload.tripId");
        if (payload.passengerId() != null) {
            requireCanonicalUuid(payload.passengerId(), "payload.passengerId");
        }

        BigDecimal lat = payload.resolvedLatitude();
        BigDecimal lng = payload.resolvedLongitude();
        validateCoordinate(lat, MIN_LATITUDE, MAX_LATITUDE, "pickupLatitude");
        validateCoordinate(lng, MIN_LONGITUDE, MAX_LONGITUDE, "pickupLongitude");

        String vehicle = payload.resolvedVehicleType();
        if (!SUPPORTED_VEHICLES.contains(vehicle)) {
            throw new InvalidTripEventException("Unsupported vehicleType: " + vehicle);
        }
    }

    private static void validateCoordinate(
            BigDecimal value, BigDecimal min, BigDecimal max, String fieldName) {
        if (value == null) {
            throw new InvalidTripEventException("Missing coordinate: " + fieldName);
        }
        if (Math.abs((long) value.scale()) > MAX_COORDINATE_SCALE) {
            throw new InvalidTripEventException("Coordinate scale out of bounds: " + fieldName);
        }
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new InvalidTripEventException("Coordinate out of range: " + fieldName);
        }
    }

    public static void requireCanonicalUuid(String value, String fieldName) {
        if (value == null || value.length() != 36) {
            throw new InvalidTripEventException("Expected canonical lowercase UUID for " + fieldName);
        }
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new InvalidTripEventException("Expected canonical lowercase UUID for " + fieldName);
            }
        } catch (IllegalArgumentException ex) {
            throw new InvalidTripEventException("Expected canonical lowercase UUID for " + fieldName, ex);
        }
    }
}
