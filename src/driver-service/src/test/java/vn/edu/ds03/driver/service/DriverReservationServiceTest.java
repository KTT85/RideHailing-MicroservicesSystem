package vn.edu.ds03.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.ds03.driver.dto.DriverProfileResponse;
import vn.edu.ds03.driver.model.Driver;
import vn.edu.ds03.driver.model.DriverStatus;
import vn.edu.ds03.driver.model.Vehicle;
import vn.edu.ds03.driver.model.VehicleType;
import vn.edu.ds03.driver.repository.DriverRepository;

@ExtendWith(MockitoExtension.class)
class DriverReservationServiceTest {

    private static final String MIXED_DRIVER_ID = "AAAAAAAA-1111-2222-3333-444444444444";
    private static final String DRIVER_ID = "aaaaaaaa-1111-2222-3333-444444444444";
    private static final String MIXED_TRIP_ID = "BBBBBBBB-1111-2222-3333-444444444444";
    private static final String TRIP_ID = "bbbbbbbb-1111-2222-3333-444444444444";

    @Mock
    private DriverRepository driverRepository;

    private DriverReservationService driverReservationService;

    @BeforeEach
    void setUp() {
        driverReservationService = new DriverReservationService(driverRepository);
    }

    @Test
    @DisplayName("reserveDriver atomically transitions AVAILABLE driver to RESERVED and returns updated profile")
    void reserveDriver_availableDriver_updatesAtomicallyAndReturnsReservedProfile() {
        Driver reserved = buildDriver(DriverStatus.RESERVED, TRIP_ID);
        when(driverRepository.reserveAvailableDriver(DRIVER_ID, TRIP_ID)).thenReturn(1);
        when(driverRepository.findWithVehicleById(DRIVER_ID)).thenReturn(Optional.of(reserved));

        DriverProfileResponse response = driverReservationService.reserveDriver(MIXED_DRIVER_ID, MIXED_TRIP_ID);

        assertThat(response.driverId()).isEqualTo(DRIVER_ID);
        assertThat(response.status()).isEqualTo(DriverStatus.RESERVED);
        assertThat(response.currentTripId()).isEqualTo(TRIP_ID);
        verify(driverRepository).reserveAvailableDriver(DRIVER_ID, TRIP_ID);
        verify(driverRepository).findWithVehicleById(DRIVER_ID);
        verifyNoMoreInteractions(driverRepository);
    }

    @Test
    @DisplayName("reserveDriver is idempotent when driver already holds the same tripId")
    void reserveDriver_sameTripAlreadyHeld_returnsExistingProfileIdempotently() {
        Driver alreadyReserved = buildDriver(DriverStatus.RESERVED, TRIP_ID);
        when(driverRepository.reserveAvailableDriver(DRIVER_ID, TRIP_ID)).thenReturn(0);
        when(driverRepository.findWithVehicleById(DRIVER_ID)).thenReturn(Optional.of(alreadyReserved));

        DriverProfileResponse response = driverReservationService.reserveDriver(DRIVER_ID, TRIP_ID);

        assertThat(response.status()).isEqualTo(DriverStatus.RESERVED);
        assertThat(response.currentTripId()).isEqualTo(TRIP_ID);
    }

    @Test
    @DisplayName("reserveDriver throws 404 when driver is missing and 409 on conflict or unique trip constraint")
    void reserveDriver_missingUnavailableOrDuplicateTrip_throws404Or409() {
        when(driverRepository.reserveAvailableDriver(DRIVER_ID, TRIP_ID)).thenReturn(0);
        when(driverRepository.findWithVehicleById(DRIVER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> driverReservationService.reserveDriver(DRIVER_ID, TRIP_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        Driver busyOtherTrip = buildDriver(DriverStatus.RESERVED, "other-trip-id");
        when(driverRepository.findWithVehicleById(DRIVER_ID)).thenReturn(Optional.of(busyOtherTrip));

        assertThatThrownBy(() -> driverReservationService.reserveDriver(DRIVER_ID, TRIP_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        when(driverRepository.reserveAvailableDriver(DRIVER_ID, TRIP_ID))
                .thenThrow(new DataIntegrityViolationException("uq_drivers_current_trip_id"));

        assertThatThrownBy(() -> driverReservationService.reserveDriver(DRIVER_ID, TRIP_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getReason()).doesNotContain("uq_drivers_current_trip_id");
                });
    }

    @Test
    @DisplayName("startTrip transitions RESERVED -> BUSY, stays idempotent on BUSY, and rejects mismatched state")
    void startTrip_reservedBusyMissingOrMismatched_handlesAllOutcomes() {
        Driver busy = buildDriver(DriverStatus.BUSY, TRIP_ID);
        when(driverRepository.startReservedTrip(DRIVER_ID, TRIP_ID)).thenReturn(1);
        when(driverRepository.findWithVehicleById(DRIVER_ID)).thenReturn(Optional.of(busy));

        DriverProfileResponse started = driverReservationService.startTrip(MIXED_DRIVER_ID, MIXED_TRIP_ID);
        assertThat(started.status()).isEqualTo(DriverStatus.BUSY);

        when(driverRepository.startReservedTrip(DRIVER_ID, TRIP_ID)).thenReturn(0);
        when(driverRepository.findWithVehicleById(DRIVER_ID)).thenReturn(Optional.of(busy));
        assertThat(driverReservationService.startTrip(DRIVER_ID, TRIP_ID).status())
                .isEqualTo(DriverStatus.BUSY);

        Driver available = buildDriver(DriverStatus.AVAILABLE, null);
        when(driverRepository.findWithVehicleById(DRIVER_ID)).thenReturn(Optional.of(available));
        assertThatThrownBy(() -> driverReservationService.startTrip(DRIVER_ID, TRIP_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        when(driverRepository.findWithVehicleById(DRIVER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> driverReservationService.startTrip(DRIVER_ID, TRIP_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("releaseDriver releases active trip, is idempotent when idle, and returns 409 if holding another trip")
    void releaseDriver_activeIdleMissingOrDifferentTrip_handlesAllOutcomes() {
        Driver available = buildDriver(DriverStatus.AVAILABLE, null);
        when(driverRepository.releaseDriverFromTrip(DRIVER_ID, TRIP_ID)).thenReturn(1);
        when(driverRepository.findWithVehicleById(DRIVER_ID)).thenReturn(Optional.of(available));

        DriverProfileResponse released = driverReservationService.releaseDriver(MIXED_DRIVER_ID, MIXED_TRIP_ID);
        assertThat(released.status()).isEqualTo(DriverStatus.AVAILABLE);
        assertThat(released.currentTripId()).isNull();

        when(driverRepository.releaseDriverFromTrip(DRIVER_ID, TRIP_ID)).thenReturn(0);
        when(driverRepository.findWithVehicleById(DRIVER_ID)).thenReturn(Optional.of(available));
        assertThat(driverReservationService.releaseDriver(DRIVER_ID, TRIP_ID).status())
                .isEqualTo(DriverStatus.AVAILABLE);

        Driver holdingOtherTrip = buildDriver(DriverStatus.RESERVED, "other-trip-2");
        when(driverRepository.findWithVehicleById(DRIVER_ID)).thenReturn(Optional.of(holdingOtherTrip));
        assertThatThrownBy(() -> driverReservationService.releaseDriver(DRIVER_ID, TRIP_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        when(driverRepository.findWithVehicleById(DRIVER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> driverReservationService.releaseDriver(DRIVER_ID, TRIP_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @ParameterizedTest
    @MethodSource("invalidIdentifiers")
    @DisplayName("All reservation lifecycle methods reject blank, oversized, or non-ASCII/special identifiers with 400")
    void lifecycleMethods_invalidIdentifiers_throwBadRequestWithoutCallingRepository(
            String badDriverId, String badTripId) {
        assertThatThrownBy(() -> driverReservationService.reserveDriver(badDriverId, badTripId))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> driverReservationService.startTrip(badDriverId, badTripId))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> driverReservationService.releaseDriver(badDriverId, badTripId))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(driverRepository);
    }

    static Stream<Arguments> invalidIdentifiers() {
        return Stream.of(
                Arguments.of(null, TRIP_ID),
                Arguments.of("   ", TRIP_ID),
                Arguments.of("x".repeat(37), TRIP_ID),
                Arguments.of("bad/id", TRIP_ID),
                Arguments.of(DRIVER_ID, null),
                Arguments.of(DRIVER_ID, "   "),
                Arguments.of(DRIVER_ID, "x".repeat(37)),
                Arguments.of(DRIVER_ID, "bad trip"));
    }

    private static Driver buildDriver(DriverStatus status, String currentTripId) {
        Driver driver = new Driver("user-1");
        Vehicle vehicle = new Vehicle("51A-123.45", VehicleType.BIKE);
        driver.attachVehicle(vehicle);
        driver.updateLocation(new BigDecimal("10.7768890"), new BigDecimal("106.7008060"));
        ReflectionTestUtils.setField(driver, "id", DRIVER_ID);
        ReflectionTestUtils.setField(vehicle, "id", "vehicle-1");
        ReflectionTestUtils.setField(driver, "status", status);
        ReflectionTestUtils.setField(driver, "acceptingTrips", status != DriverStatus.OFFLINE);
        ReflectionTestUtils.setField(driver, "currentTripId", currentTripId);
        return driver;
    }
}
