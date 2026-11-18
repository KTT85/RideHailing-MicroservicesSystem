package vn.edu.ds03.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.ds03.driver.dto.CreateDriverProfileRequest;
import vn.edu.ds03.driver.dto.DriverProfileResponse;
import vn.edu.ds03.driver.dto.UpdateDriverAvailabilityRequest;
import vn.edu.ds03.driver.dto.UpdateDriverLocationRequest;
import vn.edu.ds03.driver.model.Driver;
import vn.edu.ds03.driver.model.DriverStatus;
import vn.edu.ds03.driver.model.Vehicle;
import vn.edu.ds03.driver.model.VehicleType;
import vn.edu.ds03.driver.repository.DriverRepository;

@ExtendWith(MockitoExtension.class)
class DriverProfileServiceTest {

    private static final String MIXED_CASE_USER_ID = "550E8400-E29B-41D4-A716-446655440000";
    private static final String NORMALIZED_USER_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Mock
    private DriverRepository driverRepository;

    private DriverProfileService driverProfileService;

    @BeforeEach
    void setUp() {
        driverProfileService = new DriverProfileService(driverRepository);
    }

    @ParameterizedTest
    @EnumSource(VehicleType.class)
    @DisplayName("createProfile normalizes userId before lookup and persists offline driver with uppercase plate")
    void createProfile_validRequest_normalizesUserIdAndSavesDriverAndVehicle(VehicleType vehicleType) {
        CreateDriverProfileRequest request = new CreateDriverProfileRequest(" 51a-123.45 ", vehicleType);
        when(driverRepository.existsByUserId(NORMALIZED_USER_ID)).thenReturn(false);
        when(driverRepository.saveAndFlush(any(Driver.class))).thenAnswer(invocation -> {
            Driver saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
            ReflectionTestUtils.setField(saved.getVehicle(), "id", "99999999-8888-7777-6666-555555555555");
            return saved;
        });

        DriverProfileResponse response = driverProfileService.createProfile(MIXED_CASE_USER_ID, request);

        ArgumentCaptor<Driver> driverCaptor = ArgumentCaptor.forClass(Driver.class);
        verify(driverRepository).existsByUserId(NORMALIZED_USER_ID);
        verify(driverRepository).saveAndFlush(driverCaptor.capture());
        verifyNoMoreInteractions(driverRepository);

        Driver persisted = driverCaptor.getValue();
        assertThat(persisted.getUserId()).isEqualTo(NORMALIZED_USER_ID);
        assertThat(persisted.getStatus()).isEqualTo(DriverStatus.OFFLINE);
        assertThat(persisted.isAcceptingTrips()).isFalse();
        assertThat(persisted.getVehicle().getLicensePlate()).isEqualTo("51A-123.45");
        assertThat(persisted.getVehicle().getVehicleType()).isEqualTo(vehicleType);

        assertThat(response.driverId()).isEqualTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        assertThat(response.userId()).isEqualTo(NORMALIZED_USER_ID);
        assertThat(response.status()).isEqualTo(DriverStatus.OFFLINE);
        assertThat(response.acceptingTrips()).isFalse();
        assertThat(response.currentTripId()).isNull();
        assertThat(response.latitude()).isNull();
        assertThat(response.longitude()).isNull();
        assertThat(response.vehicle().vehicleId()).isEqualTo("99999999-8888-7777-6666-555555555555");
        assertThat(response.vehicle().licensePlate()).isEqualTo("51A-123.45");
        assertThat(response.vehicle().vehicleType()).isEqualTo(vehicleType);
    }

    @Test
    @DisplayName("createProfile throws 409 Conflict when normalized userId already exists")
    void createProfile_existingMixedCaseUserId_throwsConflictWithoutSaving() {
        CreateDriverProfileRequest request = new CreateDriverProfileRequest("51A-123.45", VehicleType.CAR);
        when(driverRepository.existsByUserId(NORMALIZED_USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> driverProfileService.createProfile(MIXED_CASE_USER_ID, request))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(statusEx -> {
                    assertThat(statusEx.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(statusEx.getReason()).isEqualTo("Driver profile already exists");
                });

        verify(driverRepository).existsByUserId(NORMALIZED_USER_ID);
        verify(driverRepository, never()).saveAndFlush(any(Driver.class));
        verifyNoMoreInteractions(driverRepository);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    @DisplayName("createProfile throws 401 Unauthorized when userId subject is null or blank")
    void createProfile_nullOrBlankUserId_throwsUnauthorizedWithoutCallingRepository(String invalidUserId) {
        CreateDriverProfileRequest request = new CreateDriverProfileRequest("51A-123.45", VehicleType.BIKE);

        assertThatThrownBy(() -> driverProfileService.createProfile(invalidUserId, request))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(statusEx -> assertThat(statusEx.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verifyNoMoreInteractions(driverRepository);
    }

    @Test
    @DisplayName("createProfile translates DataIntegrityViolationException into 409 Conflict without SQL details")
    void createProfile_dataIntegrityViolationOnSave_throwsConflictWithoutExposingSqlDetails() {
        CreateDriverProfileRequest request = new CreateDriverProfileRequest("51A-123.45", VehicleType.CAR);
        when(driverRepository.existsByUserId(NORMALIZED_USER_ID)).thenReturn(false);
        when(driverRepository.saveAndFlush(any(Driver.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "Duplicate entry '51A-123.45' for key 'uq_vehicles_license_plate'"));

        assertThatThrownBy(() -> driverProfileService.createProfile(MIXED_CASE_USER_ID, request))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(statusEx -> {
                    assertThat(statusEx.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(statusEx.getReason()).isEqualTo("Driver profile or license plate already exists");
                    assertThat(statusEx.getReason()).doesNotContain("uq_vehicles_license_plate");
                });
    }

    @Test
    @DisplayName("getCurrentProfile normalizes userId and maps existing driver, coordinates, trip, and vehicle")
    void getCurrentProfile_existingDriver_returnsFullDriverProfileResponse() {
        Driver driver = new Driver(NORMALIZED_USER_ID);
        Vehicle vehicle = new Vehicle("59x1-678.90", VehicleType.CAR);
        driver.attachVehicle(vehicle);
        ReflectionTestUtils.setField(driver, "id", "driver-id-1");
        ReflectionTestUtils.setField(vehicle, "id", "vehicle-id-1");
        ReflectionTestUtils.setField(driver, "status", DriverStatus.BUSY);
        ReflectionTestUtils.setField(driver, "acceptingTrips", true);
        ReflectionTestUtils.setField(driver, "currentTripId", "trip-id-1");
        ReflectionTestUtils.setField(driver, "latitude", new BigDecimal("10.7626220"));
        ReflectionTestUtils.setField(driver, "longitude", new BigDecimal("106.6601720"));
        when(driverRepository.findWithVehicleByUserId(NORMALIZED_USER_ID))
                .thenReturn(Optional.of(driver));

        DriverProfileResponse response = driverProfileService.getCurrentProfile("  " + MIXED_CASE_USER_ID + "  ");

        assertThat(response.driverId()).isEqualTo("driver-id-1");
        assertThat(response.userId()).isEqualTo(NORMALIZED_USER_ID);
        assertThat(response.status()).isEqualTo(DriverStatus.BUSY);
        assertThat(response.acceptingTrips()).isTrue();
        assertThat(response.currentTripId()).isEqualTo("trip-id-1");
        assertThat(response.latitude()).isEqualByComparingTo("10.7626220");
        assertThat(response.longitude()).isEqualByComparingTo("106.6601720");
        assertThat(response.vehicle().vehicleId()).isEqualTo("vehicle-id-1");
        assertThat(response.vehicle().licensePlate()).isEqualTo("59X1-678.90");
        assertThat(response.vehicle().vehicleType()).isEqualTo(VehicleType.CAR);
        verify(driverRepository).findWithVehicleByUserId(NORMALIZED_USER_ID);
        verifyNoMoreInteractions(driverRepository);
    }

    @Test
    @DisplayName("getCurrentProfile throws 404 Not Found when driver profile does not exist")
    void getCurrentProfile_missingDriver_throwsNotFound() {
        when(driverRepository.findWithVehicleByUserId(NORMALIZED_USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> driverProfileService.getCurrentProfile(MIXED_CASE_USER_ID))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(statusEx -> {
                    assertThat(statusEx.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(statusEx.getReason()).isEqualTo("Driver profile not found");
                });

        verify(driverRepository).findWithVehicleByUserId(NORMALIZED_USER_ID);
        verifyNoMoreInteractions(driverRepository);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    @DisplayName("getCurrentProfile throws 401 Unauthorized when userId subject is null or blank")
    void getCurrentProfile_nullOrBlankUserId_throwsUnauthorizedWithoutCallingRepository(String invalidUserId) {
        assertThatThrownBy(() -> driverProfileService.getCurrentProfile(invalidUserId))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(statusEx -> assertThat(statusEx.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verifyNoMoreInteractions(driverRepository);
    }

    @Test
    @DisplayName("updateLocation normalizes userId, updates coordinates to 7 decimals, saves, and returns profile")
    void updateLocation_existingDriver_updatesCoordinatesSavesAndReturnsProfile() {
        Driver driver = new Driver(NORMALIZED_USER_ID);
        Vehicle vehicle = new Vehicle("51a-123.45", VehicleType.BIKE);
        driver.attachVehicle(vehicle);
        ReflectionTestUtils.setField(driver, "id", "driver-loc-1");
        ReflectionTestUtils.setField(vehicle, "id", "vehicle-loc-1");
        when(driverRepository.findWithVehicleByUserId(NORMALIZED_USER_ID))
                .thenReturn(Optional.of(driver));
        when(driverRepository.saveAndFlush(driver)).thenReturn(driver);

        UpdateDriverLocationRequest request = new UpdateDriverLocationRequest(
                new BigDecimal("10.77584391"), new BigDecimal("106.70175554"));
        DriverProfileResponse response = driverProfileService.updateLocation(MIXED_CASE_USER_ID, request);

        assertThat(response.driverId()).isEqualTo("driver-loc-1");
        assertThat(response.latitude()).isEqualTo(new BigDecimal("10.7758439"));
        assertThat(response.longitude()).isEqualTo(new BigDecimal("106.7017555"));
        verify(driverRepository).findWithVehicleByUserId(NORMALIZED_USER_ID);
        verify(driverRepository).saveAndFlush(driver);
        verifyNoMoreInteractions(driverRepository);
    }

    @Test
    @DisplayName("updateLocation throws 404 Not Found when driver profile does not exist")
    void updateLocation_missingDriver_throwsNotFoundWithoutSaving() {
        when(driverRepository.findWithVehicleByUserId(NORMALIZED_USER_ID))
                .thenReturn(Optional.empty());
        UpdateDriverLocationRequest request = new UpdateDriverLocationRequest(
                new BigDecimal("10.7758439"), new BigDecimal("106.7017555"));

        assertThatThrownBy(() -> driverProfileService.updateLocation(MIXED_CASE_USER_ID, request))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(statusEx -> {
                    assertThat(statusEx.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(statusEx.getReason()).isEqualTo("Driver profile not found");
                });

        verify(driverRepository).findWithVehicleByUserId(NORMALIZED_USER_ID);
        verify(driverRepository, never()).saveAndFlush(any(Driver.class));
        verifyNoMoreInteractions(driverRepository);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    @DisplayName("updateLocation throws 401 Unauthorized when userId subject is null or blank")
    void updateLocation_nullOrBlankUserId_throwsUnauthorizedWithoutCallingRepository(String invalidUserId) {
        UpdateDriverLocationRequest request = new UpdateDriverLocationRequest(
                new BigDecimal("10.7758439"), new BigDecimal("106.7017555"));

        assertThatThrownBy(() -> driverProfileService.updateLocation(invalidUserId, request))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(statusEx -> assertThat(statusEx.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verifyNoMoreInteractions(driverRepository);
    }

    @Test
    @DisplayName("updateAvailability enables and disables trip acceptance when driver has valid coordinates")
    void updateAvailability_withValidCoordinates_togglesStatusAndSaves() {
        Driver driver = new Driver(NORMALIZED_USER_ID);
        Vehicle vehicle = new Vehicle("51a-123.45", VehicleType.BIKE);
        driver.attachVehicle(vehicle);
        driver.updateLocation(new BigDecimal("10.7626220"), new BigDecimal("106.6601720"));
        ReflectionTestUtils.setField(driver, "id", "driver-avail-1");
        ReflectionTestUtils.setField(vehicle, "id", "vehicle-avail-1");
        when(driverRepository.findWithVehicleByUserId(NORMALIZED_USER_ID))
                .thenReturn(Optional.of(driver));
        when(driverRepository.saveAndFlush(driver)).thenReturn(driver);

        DriverProfileResponse online = driverProfileService.updateAvailability(
                MIXED_CASE_USER_ID, new UpdateDriverAvailabilityRequest(true));
        assertThat(online.status()).isEqualTo(DriverStatus.AVAILABLE);
        assertThat(online.acceptingTrips()).isTrue();

        DriverProfileResponse offline = driverProfileService.updateAvailability(
                MIXED_CASE_USER_ID, new UpdateDriverAvailabilityRequest(false));
        assertThat(offline.status()).isEqualTo(DriverStatus.OFFLINE);
        assertThat(offline.acceptingTrips()).isFalse();
    }

    @Test
    @DisplayName("updateAvailability translates missing coordinates or active trip state into 409 Conflict")
    void updateAvailability_missingCoordinatesOrActiveTrip_throwsConflictWithoutSaving() {
        Driver noLocationDriver = new Driver(NORMALIZED_USER_ID);
        noLocationDriver.attachVehicle(new Vehicle("51a-123.45", VehicleType.BIKE));
        when(driverRepository.findWithVehicleByUserId(NORMALIZED_USER_ID))
                .thenReturn(Optional.of(noLocationDriver));

        assertThatThrownBy(() -> driverProfileService.updateAvailability(
                MIXED_CASE_USER_ID, new UpdateDriverAvailabilityRequest(true)))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        Driver busyDriver = new Driver(NORMALIZED_USER_ID);
        busyDriver.attachVehicle(new Vehicle("51a-123.45", VehicleType.BIKE));
        busyDriver.updateLocation(new BigDecimal("10.7626220"), new BigDecimal("106.6601720"));
        ReflectionTestUtils.setField(busyDriver, "status", DriverStatus.BUSY);
        ReflectionTestUtils.setField(busyDriver, "acceptingTrips", true);
        ReflectionTestUtils.setField(busyDriver, "currentTripId", "trip-999");
        when(driverRepository.findWithVehicleByUserId(NORMALIZED_USER_ID))
                .thenReturn(Optional.of(busyDriver));

        assertThatThrownBy(() -> driverProfileService.updateAvailability(
                MIXED_CASE_USER_ID, new UpdateDriverAvailabilityRequest(false)))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(driverRepository, never()).saveAndFlush(any(Driver.class));
    }

    @Test
    @DisplayName("updateAvailability throws 404 Not Found when driver profile does not exist")
    void updateAvailability_missingDriver_throwsNotFoundWithoutSaving() {
        when(driverRepository.findWithVehicleByUserId(NORMALIZED_USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> driverProfileService.updateAvailability(
                MIXED_CASE_USER_ID, new UpdateDriverAvailabilityRequest(true)))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(driverRepository, never()).saveAndFlush(any(Driver.class));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    @DisplayName("updateAvailability throws 401 Unauthorized when userId subject is null or blank")
    void updateAvailability_nullOrBlankUserId_throwsUnauthorizedWithoutCallingRepository(String invalidUserId) {
        assertThatThrownBy(() -> driverProfileService.updateAvailability(
                invalidUserId, new UpdateDriverAvailabilityRequest(true)))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verifyNoMoreInteractions(driverRepository);
    }
}
