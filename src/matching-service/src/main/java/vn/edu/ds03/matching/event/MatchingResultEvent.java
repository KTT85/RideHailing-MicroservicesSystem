package vn.edu.ds03.matching.event;

import java.time.Instant;

public record MatchingResultEvent(
        String eventId,
        String eventType,
        String correlationId,
        String source,
        Instant timestamp,
        Instant occurredAt,
        Payload payload) {

    public static final String SOURCE = "matching-service";

    public MatchingResultEvent(
            String eventId,
            String eventType,
            String correlationId,
            Instant timestamp,
            Payload payload) {
        this(eventId, eventType, correlationId, SOURCE, timestamp, timestamp, payload);
    }

    public record Payload(
            String tripId,
            String driverId,
            String driverUserId,
            String vehicleType,
            String licensePlate,
            Instant assignedAt,
            String reason,
            boolean reservationReused) {
        public Payload(String tripId, String driverId, String vehicleType, String licensePlate,
                       Instant assignedAt, String reason) {
            this(tripId, driverId, null, vehicleType, licensePlate, assignedAt, reason, false);
        }
    }
}
