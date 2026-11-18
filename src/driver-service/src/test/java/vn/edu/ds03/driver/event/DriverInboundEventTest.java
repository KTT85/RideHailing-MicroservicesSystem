package vn.edu.ds03.driver.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.edu.ds03.driver.exception.InvalidDriverEventException;

class DriverInboundEventTest {

    private static final String EVENT_ID = UUID.randomUUID().toString();
    private static final String CORRELATION_ID = UUID.randomUUID().toString();
    private static final String TRIP_ID = UUID.randomUUID().toString();
    private static final String DRIVER_ID = UUID.randomUUID().toString();
    private static final Instant NOW = Instant.parse("2026-11-18T06:30:00Z");

    @ParameterizedTest
    @ValueSource(strings = {
            "TripStarted",
            "trip.started",
            "DriverReleaseRequested",
            "driver.release_requested",
            "DriverRejected",
            "trip.rejected",
            "TripCompleted",
            "trip.completed",
            "TripCancelled",
            "trip.cancelled"
    })
    @DisplayName("validate accepts supported PascalCase and dot-case event types")
    void validate_supportedEventTypes_succeeds(String eventType) {
        var event = new DriverInboundEvent(
                EVENT_ID,
                eventType,
                CORRELATION_ID,
                NOW,
                null,
                new DriverInboundEvent.Payload(TRIP_ID, DRIVER_ID, "normal"));

        assertThatCode(event::validate).doesNotThrowAnyException();
        assertThat(event.effectiveTimestamp()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("validate falls back to occurredAt when timestamp is omitted")
    void validate_occurredAtFallback_succeeds() {
        var event = new DriverInboundEvent(
                EVENT_ID,
                "TripCompleted",
                CORRELATION_ID,
                null,
                NOW,
                new DriverInboundEvent.Payload(TRIP_ID, null, null));

        assertThatCode(event::validate).doesNotThrowAnyException();
        assertThat(event.effectiveTimestamp()).isEqualTo(NOW);
        assertThat(event.isReleaseEvent()).isTrue();
        assertThat(event.isStartEvent()).isFalse();
        assertThat(event.isExplicitReleaseCommand()).isFalse();

        var tripEntityPayloadEvent = new DriverInboundEvent(
                EVENT_ID,
                "trip.rejected",
                CORRELATION_ID,
                null,
                NOW,
                new DriverInboundEvent.Payload(null, TRIP_ID, DRIVER_ID, null));
        assertThatCode(tripEntityPayloadEvent::validate).doesNotThrowAnyException();
        assertThat(tripEntityPayloadEvent.payload().resolvedTripId()).isEqualTo(TRIP_ID);

        var conflictingIds = new DriverInboundEvent(
                EVENT_ID,
                "trip.rejected",
                CORRELATION_ID,
                null,
                NOW,
                new DriverInboundEvent.Payload(TRIP_ID, UUID.randomUUID().toString(), DRIVER_ID, null));
        assertThatThrownBy(conflictingIds::validate).isInstanceOf(InvalidDriverEventException.class);
    }

    @Test
    @DisplayName("validate requires driverId for DriverReleaseRequested")
    void validate_explicitReleaseWithoutDriverId_throwsInvalidDriverEvent() {
        var event = new DriverInboundEvent(
                EVENT_ID,
                "DriverReleaseRequested",
                CORRELATION_ID,
                NOW,
                null,
                new DriverInboundEvent.Payload(TRIP_ID, null, "payment_failed"));

        assertThatThrownBy(event::validate)
                .isInstanceOf(InvalidDriverEventException.class)
                .hasMessageContaining("driverId");
    }

    @Test
    @DisplayName("validate rejects uppercase or malformed UUIDs in envelope and payload")
    void validate_nonCanonicalUuids_throwsInvalidDriverEvent() {
        var uppercaseEventId = new DriverInboundEvent(
                EVENT_ID.toUpperCase(),
                "TripStarted",
                CORRELATION_ID,
                NOW,
                null,
                new DriverInboundEvent.Payload(TRIP_ID, DRIVER_ID, null));
        assertThatThrownBy(uppercaseEventId::validate)
                .isInstanceOf(InvalidDriverEventException.class);

        var invalidCorrelationId = new DriverInboundEvent(
                EVENT_ID,
                "TripStarted",
                "not-a-uuid",
                NOW,
                null,
                new DriverInboundEvent.Payload(TRIP_ID, DRIVER_ID, null));
        assertThatThrownBy(invalidCorrelationId::validate)
                .isInstanceOf(InvalidDriverEventException.class);

        var invalidDriverId = new DriverInboundEvent(
                EVENT_ID,
                "TripCancelled",
                CORRELATION_ID,
                NOW,
                null,
                new DriverInboundEvent.Payload(TRIP_ID, "bad-driver-id", "cancelled"));
        assertThatThrownBy(invalidDriverId::validate)
                .isInstanceOf(InvalidDriverEventException.class);
    }

    @Test
    @DisplayName("validate rejects missing timestamp, missing payload, missing tripId, and unsupported type")
    void validate_missingFieldsOrUnsupportedType_throwsInvalidDriverEvent() {
        var noTimestamp = new DriverInboundEvent(
                EVENT_ID,
                "TripStarted",
                CORRELATION_ID,
                null,
                null,
                new DriverInboundEvent.Payload(TRIP_ID, DRIVER_ID, null));
        assertThatThrownBy(noTimestamp::validate)
                .isInstanceOf(InvalidDriverEventException.class);

        var noPayload = new DriverInboundEvent(
                EVENT_ID, "TripStarted", CORRELATION_ID, NOW, null, null);
        assertThatThrownBy(noPayload::validate)
                .isInstanceOf(InvalidDriverEventException.class);

        var noTripId = new DriverInboundEvent(
                EVENT_ID,
                "TripStarted",
                CORRELATION_ID,
                NOW,
                null,
                new DriverInboundEvent.Payload(null, DRIVER_ID, null));
        assertThatThrownBy(noTripId::validate)
                .isInstanceOf(InvalidDriverEventException.class);

        var unsupportedType = new DriverInboundEvent(
                EVENT_ID,
                "PaymentRequested",
                CORRELATION_ID,
                NOW,
                null,
                new DriverInboundEvent.Payload(TRIP_ID, DRIVER_ID, null));
        assertThatThrownBy(unsupportedType::validate)
                .isInstanceOf(InvalidDriverEventException.class)
                .hasMessageContaining("Unsupported");
    }
}
