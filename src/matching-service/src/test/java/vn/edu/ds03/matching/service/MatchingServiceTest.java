package vn.edu.ds03.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.edu.ds03.matching.config.MatchingProperties;
import vn.edu.ds03.matching.dto.DriverCandidateDto;
import vn.edu.ds03.matching.dto.ReservedDriverDto;
import vn.edu.ds03.matching.event.MatchingResultEvent;
import vn.edu.ds03.matching.event.TripRequestedEvent;
import vn.edu.ds03.matching.exception.InvalidTripEventException;

class MatchingServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-11-18T07:10:00Z");

    private final DriverServiceClient driverClient = mock(DriverServiceClient.class);
    private final MatchingProperties properties = new MatchingProperties(
            "http://driver-service:8080",
            "driver-internal-service-token-32-chars",
            5,
            2000,
            3000,
            "ride.events",
            "matching.delivery",
            "matching.commands",
            "matching.retry",
            "matching.dlq",
            5000,
            3,
            65536);
    private final MatchingService matchingService = new MatchingService(
            driverClient, properties, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

    private TripRequestedEvent sampleTripRequested(String eventType, String tripId) {
        return new TripRequestedEvent(
                UUID.randomUUID().toString(),
                eventType,
                UUID.randomUUID().toString(),
                FIXED_NOW,
                null,
                new TripRequestedEvent.Payload(
                        tripId,
                        null,
                        UUID.randomUUID().toString(),
                        new BigDecimal("10.7768890"),
                        new BigDecimal("106.7008060"),
                        "CAR",
                        null));
    }

    @Test
    void redeliveryAfterRestartUsesExistingReservation() {
        String tripId = UUID.randomUUID().toString();
        String driverId = UUID.randomUUID().toString();
        String userId = UUID.randomUUID().toString();
        when(driverClient.findReservation(tripId)).thenReturn(Optional.of(
                new ReservedDriverDto(driverId, "RESERVED", tripId,
                        new ReservedDriverDto.VehicleInfo(UUID.randomUUID().toString(), "51A-00001", "CAR"), userId)));

        var assignment = matchingService.matchTrip(sampleTripRequested("TripRequested", tripId)).orElseThrow();

        assertThat(assignment.eventType()).isEqualTo("DriverAssigned");
        assertThat(assignment.payload().driverId()).isEqualTo(driverId);
        assertThat(assignment.payload().driverUserId()).isEqualTo(userId);
        assertThat(assignment.payload().reservationReused()).isTrue();
        verify(driverClient, never()).fetchCandidates(any(), any(), anyString(), anyInt());
        verify(driverClient, never()).tryReserveDriver(anyString(), eq(tripId));
    }

    @Test
    @DisplayName("matchTrip skips 409 OCC-conflicted candidate and reserves next available candidate")
    void matchTrip_firstCandidateConflictsSecondSucceeds_returnsDriverAssigned() {
        String tripId = UUID.randomUUID().toString();
        String driver1 = UUID.randomUUID().toString();
        String driver2 = UUID.randomUUID().toString();
        TripRequestedEvent event = sampleTripRequested("TripRequested", tripId);

        when(driverClient.fetchCandidates(any(), any(), eq("CAR"), eq(5))).thenReturn(List.of(
                new DriverCandidateDto(driver1, new BigDecimal("10.7769"), new BigDecimal("106.7008"), 0.1, "CAR", "51A-111.11"),
                new DriverCandidateDto(driver2, new BigDecimal("10.7775"), new BigDecimal("106.7012"), 0.3, "CAR", "51A-222.22")));
        when(driverClient.tryReserveDriver(driver1, tripId)).thenReturn(Optional.empty());
        when(driverClient.tryReserveDriver(driver2, tripId)).thenReturn(Optional.of(
                new ReservedDriverDto(
                        driver2,
                        "RESERVED",
                        tripId,
                        new ReservedDriverDto.VehicleInfo(UUID.randomUUID().toString(), "51A-222.22", "CAR"))));

        Optional<MatchingResultEvent> result = matchingService.matchTrip(event);

        assertThat(result).isPresent();
        assertThat(result.get().eventType()).isEqualTo("DriverAssigned");
        assertThat(result.get().payload().tripId()).isEqualTo(tripId);
        assertThat(result.get().payload().driverId()).isEqualTo(driver2);
        assertThat(result.get().payload().licensePlate()).isEqualTo("51A-222.22");
        verify(driverClient).tryReserveDriver(driver1, tripId);
        verify(driverClient).tryReserveDriver(driver2, tripId);
    }

    @Test
    @DisplayName("matchTrip returns NoDriverAvailable / driver.unavailable when all candidates fail OCC")
    void matchTrip_allCandidatesUnavailable_returnsNoDriverAvailable() {
        String tripId = UUID.randomUUID().toString();
        String driver1 = UUID.randomUUID().toString();
        var dotCaseEvent = new TripRequestedEvent(
                UUID.randomUUID().toString(),
                "trip.requested",
                UUID.randomUUID().toString(),
                null,
                FIXED_NOW,
                new TripRequestedEvent.Payload(
                        null,
                        tripId,
                        UUID.randomUUID().toString(),
                        null,
                        null,
                        null,
                        new TripRequestedEvent.PickupLocation(
                                new BigDecimal("10.7768890"), new BigDecimal("106.7008060"))));

        when(driverClient.fetchCandidates(any(), any(), eq("CAR"), eq(5))).thenReturn(List.of(
                new DriverCandidateDto(driver1, new BigDecimal("10.7769"), new BigDecimal("106.7008"), 0.1, "CAR", "51A-111.11")));
        when(driverClient.tryReserveDriver(driver1, tripId)).thenReturn(Optional.empty());

        Optional<MatchingResultEvent> result = matchingService.matchTrip(dotCaseEvent);

        assertThat(result).isPresent();
        assertThat(result.get().eventType()).isEqualTo("driver.unavailable");
        assertThat(result.get().payload().tripId()).isEqualTo(tripId);
        assertThat(result.get().payload().driverId()).isNull();
        assertThat(result.get().payload().reason()).isEqualTo("NO_AVAILABLE_DRIVER");
    }

    @Test
    @DisplayName("duplicate completed eventId is skipped and redelivery before markCompleted reuses cached assignment")
    void matchTrip_idempotencyAndCachedAssignment_preventsDuplicateReservation() {
        String tripId = UUID.randomUUID().toString();
        String driverId = UUID.randomUUID().toString();
        TripRequestedEvent event = sampleTripRequested("TripRequested", tripId);

        when(driverClient.fetchCandidates(any(), any(), eq("CAR"), eq(5))).thenReturn(List.of(
                new DriverCandidateDto(driverId, new BigDecimal("10.7769"), new BigDecimal("106.7008"), 0.1, "CAR", "51A-333.33")));
        when(driverClient.tryReserveDriver(driverId, tripId)).thenReturn(Optional.of(
                new ReservedDriverDto(
                        driverId,
                        "RESERVED",
                        tripId,
                        new ReservedDriverDto.VehicleInfo(UUID.randomUUID().toString(), "51A-333.33", "CAR"))));

        // First delivery reserves driver, suppose broker publish fails BEFORE markEventCompleted
        Optional<MatchingResultEvent> firstAttempt = matchingService.matchTrip(event);
        assertThat(firstAttempt).isPresent();

        // Retry of same event before markEventCompleted returns cached assignment without calling REST again
        Optional<MatchingResultEvent> retryAttempt = matchingService.matchTrip(event);
        assertThat(retryAttempt).isPresent();
        assertThat(retryAttempt.get().payload().driverId()).isEqualTo(driverId);
        verify(driverClient, times(1)).fetchCandidates(any(), any(), anyString(), anyInt());
        verify(driverClient, times(1)).tryReserveDriver(driverId, tripId);

        // Once markEventCompleted is recorded, duplicate delivery returns empty
        matchingService.markEventCompleted(event.eventId());
        assertThat(matchingService.matchTrip(event)).isEmpty();
    }

    @Test
    @DisplayName("TripRequestedEvent rejects invalid coordinates, unsupported vehicleType, and malformed UUIDs")
    void validate_invalidInputs_throwsInvalidTripEvent() {
        String validUuid = UUID.randomUUID().toString();
        var badLat = new TripRequestedEvent(
                validUuid,
                "TripRequested",
                validUuid,
                FIXED_NOW,
                null,
                new TripRequestedEvent.Payload(
                        validUuid, null, validUuid, new BigDecimal("95.0"), new BigDecimal("106.7"), "BIKE", null));
        assertThatThrownBy(badLat::validate).isInstanceOf(InvalidTripEventException.class);

        var badVehicle = new TripRequestedEvent(
                validUuid,
                "TripRequested",
                validUuid,
                FIXED_NOW,
                null,
                new TripRequestedEvent.Payload(
                        validUuid, null, validUuid, new BigDecimal("10.7"), new BigDecimal("106.7"), "TRUCK", null));
        assertThatThrownBy(badVehicle::validate).isInstanceOf(InvalidTripEventException.class);

        var uppercaseId = new TripRequestedEvent(
                validUuid.toUpperCase(),
                "TripRequested",
                validUuid,
                FIXED_NOW,
                null,
                new TripRequestedEvent.Payload(
                        validUuid, null, validUuid, new BigDecimal("10.7"), new BigDecimal("106.7"), "BIKE", null));
        assertThatThrownBy(uppercaseId::validate).isInstanceOf(InvalidTripEventException.class);
        verify(driverClient, never()).fetchCandidates(any(), any(), anyString(), anyInt());
    }
}
