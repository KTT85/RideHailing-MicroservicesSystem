package vn.edu.ds03.driver.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import vn.edu.ds03.driver.exception.InvalidDriverEventException;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DriverInboundEvent(
        String eventId,
        String eventType,
        String correlationId,
        Instant timestamp,
        Instant occurredAt,
        Payload payload) {

    public static final String START_COMMAND = "DriverStartRequested";
    private static final Set<String> START_EVENTS = Set.of(START_COMMAND, "TripStarted", "trip.started");
    private static final Set<String> EXPLICIT_RELEASE_COMMANDS =
            Set.of("DriverReleaseRequested", "driver.release_requested");
    private static final Set<String> TRIP_TERMINAL_RELEASE_EVENTS = Set.of(
            "DriverRejected",
            "trip.rejected",
            "TripCompleted",
            "trip.completed",
            "TripCancelled",
            "trip.cancelled");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payload(String tripId, String id, String driverId, String reason) {
        public Payload(String tripId, String driverId, String reason) {
            this(tripId, null, driverId, reason);
        }

        public String resolvedTripId() {
            return tripId != null ? tripId : id;
        }
    }

    public Instant effectiveTimestamp() {
        return timestamp != null ? timestamp : occurredAt;
    }

    public boolean isStartEvent() {
        return eventType != null && START_EVENTS.contains(eventType);
    }

    public boolean isExplicitReleaseCommand() {
        return eventType != null && EXPLICIT_RELEASE_COMMANDS.contains(eventType);
    }

    public boolean isReleaseEvent() {
        return isExplicitReleaseCommand()
                || (eventType != null && TRIP_TERMINAL_RELEASE_EVENTS.contains(eventType));
    }

    public void validate() {
        requireCanonicalUuid(eventId, "eventId");
        requireCanonicalUuid(correlationId, "correlationId");
        if (effectiveTimestamp() == null) {
            throw new InvalidDriverEventException("Event timestamp is required");
        }
        if (payload == null) {
            throw new InvalidDriverEventException("Event payload is required");
        }
        if (!isStartEvent() && !isReleaseEvent()) {
            throw new InvalidDriverEventException("Unsupported driver event type: " + eventType);
        }
        if (payload.tripId() != null && payload.id() != null && !payload.tripId().equals(payload.id())) {
            throw new InvalidDriverEventException("Conflicting payload.tripId and payload.id");
        }
        requireCanonicalUuid(payload.resolvedTripId(), "payload.tripId");
        if (isExplicitReleaseCommand() || START_COMMAND.equals(eventType)) {
            requireCanonicalUuid(payload.driverId(), "payload.driverId");
        } else if (payload.driverId() != null) {
            requireCanonicalUuid(payload.driverId(), "payload.driverId");
        }
    }

    private static void requireCanonicalUuid(String value, String fieldName) {
        if (value == null || value.length() != 36) {
            throw new InvalidDriverEventException("Expected canonical lowercase UUID for " + fieldName);
        }
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new InvalidDriverEventException("Expected canonical lowercase UUID for " + fieldName);
            }
        } catch (IllegalArgumentException ex) {
            throw new InvalidDriverEventException("Expected canonical lowercase UUID for " + fieldName, ex);
        }
    }
}
