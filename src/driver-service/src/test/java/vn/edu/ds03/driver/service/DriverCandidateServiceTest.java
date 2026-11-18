package vn.edu.ds03.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.ds03.driver.dto.DriverCandidateResponse;
import vn.edu.ds03.driver.model.Driver;
import vn.edu.ds03.driver.model.Vehicle;
import vn.edu.ds03.driver.model.VehicleType;
import vn.edu.ds03.driver.repository.DriverRepository;

@ExtendWith(MockitoExtension.class)
class DriverCandidateServiceTest {

    @Mock
    private DriverRepository driverRepository;

    private DriverCandidateService driverCandidateService;

    @BeforeEach
    void setUp() {
        driverCandidateService = new DriverCandidateService(driverRepository);
    }

    @Test
    @DisplayName("findNearestCandidates sorts by Haversine distance ascending, tie-breaks by driverId, and enforces limit")
    void findNearestCandidates_multipleAvailableDrivers_sortsByDistanceTieBreaksAndLimits() {
        Driver farDriver = buildAvailableDriver(
                "driver-c", "user-c", "veh-c", "51A-000.03", VehicleType.BIKE, "10.8230989", "106.6296638");
        Driver sameSpotLaterId = buildAvailableDriver(
                "driver-b", "user-b", "veh-b", "51A-000.02", VehicleType.BIKE, "10.7768890", "106.7008060");
        Driver sameSpotEarlierId = buildAvailableDriver(
                "driver-a", "user-a", "veh-a", "51A-000.01", VehicleType.BIKE, "10.7768890", "106.7008060");

        when(driverRepository.findAvailableCandidatesByVehicleType(VehicleType.BIKE))
                .thenReturn(List.of(farDriver, sameSpotLaterId, sameSpotEarlierId));

        List<DriverCandidateResponse> results = driverCandidateService.findNearestCandidates(
                new BigDecimal("10.7768890"),
                new BigDecimal("106.7008060"),
                VehicleType.BIKE,
                2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).driverId()).isEqualTo("driver-a");
        assertThat(results.get(0).distanceKm()).isCloseTo(0.0, within(0.0001));
        assertThat(results.get(1).driverId()).isEqualTo("driver-b");
        assertThat(results.get(1).distanceKm()).isCloseTo(0.0, within(0.0001));

        verify(driverRepository).findAvailableCandidatesByVehicleType(VehicleType.BIKE);
        verifyNoMoreInteractions(driverRepository);
    }

    @ParameterizedTest
    @MethodSource("invalidCandidateQueries")
    @DisplayName("findNearestCandidates throws 400 Bad Request for invalid coordinates, scale, vehicleType, or limit")
    void findNearestCandidates_invalidArguments_throwsBadRequestWithoutCallingRepository(
            BigDecimal pickupLat, BigDecimal pickupLon, VehicleType vehicleType, int limit) {
        assertThatThrownBy(() -> driverCandidateService.findNearestCandidates(
                pickupLat, pickupLon, vehicleType, limit))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(driverRepository);
    }

    static Stream<Arguments> invalidCandidateQueries() {
        BigDecimal validLat = new BigDecimal("10.7768890");
        BigDecimal validLon = new BigDecimal("106.7008060");
        return Stream.of(
                Arguments.of(null, validLon, VehicleType.BIKE, 5),
                Arguments.of(validLat, null, VehicleType.BIKE, 5),
                Arguments.of(validLat, validLon, null, 5),
                Arguments.of(new BigDecimal("-90.0001"), validLon, VehicleType.BIKE, 5),
                Arguments.of(new BigDecimal("90.0001"), validLon, VehicleType.BIKE, 5),
                Arguments.of(validLat, new BigDecimal("-180.0001"), VehicleType.BIKE, 5),
                Arguments.of(validLat, new BigDecimal("180.0001"), VehicleType.BIKE, 5),
                Arguments.of(new BigDecimal("1E-500000000"), validLon, VehicleType.BIKE, 5),
                Arguments.of(new BigDecimal("1E+100000"), validLon, VehicleType.BIKE, 5),
                Arguments.of(validLat, validLon, VehicleType.BIKE, 0),
                Arguments.of(validLat, validLon, VehicleType.BIKE, -1),
                Arguments.of(validLat, validLon, VehicleType.BIKE, 21));
    }

    private static Driver buildAvailableDriver(
            String driverId,
            String userId,
            String vehicleId,
            String plate,
            VehicleType type,
            String lat,
            String lon) {
        Driver driver = new Driver(userId);
        Vehicle vehicle = new Vehicle(plate, type);
        driver.attachVehicle(vehicle);
        driver.updateLocation(new BigDecimal(lat), new BigDecimal(lon));
        driver.updateAvailability(true);
        ReflectionTestUtils.setField(driver, "id", driverId);
        ReflectionTestUtils.setField(vehicle, "id", vehicleId);
        return driver;
    }
}
