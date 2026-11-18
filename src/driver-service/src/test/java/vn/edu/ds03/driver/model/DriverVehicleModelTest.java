package vn.edu.ds03.driver.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

class DriverVehicleModelTest {

    private static final String UUID_LOWERCASE_PATTERN =
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    @Test
    @DisplayName("Driver constructor normalizes userId to lowercase and initializes offline state")
    void constructor_mixedCaseUserId_normalizesToLowercaseAndStartsOffline() {
        Driver driver = new Driver("550E8400-E29B-41D4-A716-446655440000");

        assertThat(driver.getId()).isNull();
        assertThat(driver.getUserId()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(driver.getStatus()).isEqualTo(DriverStatus.OFFLINE);
        assertThat(driver.isAcceptingTrips()).isFalse();
        assertThat(driver.getCurrentTripId()).isNull();
        assertThat(driver.getLatitude()).isNull();
        assertThat(driver.getLongitude()).isNull();
        assertThat(driver.getVehicle()).isNull();
    }

    @ParameterizedTest
    @EnumSource(VehicleType.class)
    @DisplayName("attachVehicle links vehicle and Vehicle strips whitespace and uppercases license plate")
    void attachVehicle_whitespaceAndLowercasePlate_stripsUppercasesAndLinksVehicle(VehicleType vehicleType) {
        Driver driver = new Driver("user-123");
        Vehicle vehicle = new Vehicle("\u2003 59a-123.45 \t", vehicleType);

        driver.attachVehicle(vehicle);

        assertThat(driver.getVehicle()).isSameAs(vehicle);
        assertThat(vehicle.getLicensePlate()).isEqualTo("59A-123.45");
        assertThat(vehicle.getVehicleType()).isEqualTo(vehicleType);
        assertThatThrownBy(() -> driver.attachVehicle(null))
                .isInstanceOf(NullPointerException.class);
        assertThat(driver.getVehicle()).isSameAs(vehicle);

        Vehicle replacement = new Vehicle("51g-999.99", VehicleType.CAR);
        driver.attachVehicle(replacement);
        assertThat(driver.getVehicle()).isSameAs(replacement);
        assertThat(replacement.getLicensePlate()).isEqualTo("51G-999.99");
    }

    @Test
    @DisplayName("PrePersist assignId generates valid lowercase UUIDs without overwriting existing IDs")
    void assignId_missingAndExistingIds_generatesValidLowercaseUuidOnlyWhenNull() {
        Driver driver = new Driver("user-123");
        Vehicle vehicle = new Vehicle("51F-888.99", VehicleType.CAR);

        driver.assignId();
        vehicle.assignId();

        String generatedDriverId = driver.getId();
        String generatedVehicleId = vehicle.getId();
        assertThat(generatedDriverId)
                .isNotNull()
                .isEqualTo(generatedDriverId.toLowerCase(Locale.ROOT))
                .matches(UUID_LOWERCASE_PATTERN);
        assertThat(generatedVehicleId)
                .isNotNull()
                .isEqualTo(generatedVehicleId.toLowerCase(Locale.ROOT))
                .matches(UUID_LOWERCASE_PATTERN);
        assertThatNoException().isThrownBy(() -> UUID.fromString(generatedDriverId));
        assertThatNoException().isThrownBy(() -> UUID.fromString(generatedVehicleId));

        driver.assignId();
        vehicle.assignId();
        assertThat(driver.getId()).isEqualTo(generatedDriverId);
        assertThat(vehicle.getId()).isEqualTo(generatedVehicleId);
    }

    @ParameterizedTest
    @MethodSource("validCoordinatePairs")
    @DisplayName("updateLocation scales valid coordinates to 7 decimals and preserves status")
    void updateLocation_validAndBoundaryCoordinates_scalesToSevenDecimals(
            String rawLat, String rawLon, String expectedLat, String expectedLon) {
        Driver driver = new Driver("user-123");

        driver.updateLocation(new BigDecimal(rawLat), new BigDecimal(rawLon));

        assertThat(driver.getLatitude()).isEqualTo(new BigDecimal(expectedLat));
        assertThat(driver.getLongitude()).isEqualTo(new BigDecimal(expectedLon));
        assertThat(driver.getStatus()).isEqualTo(DriverStatus.OFFLINE);
    }

    static Stream<Arguments> validCoordinatePairs() {
        return Stream.of(
                Arguments.of("10.76262236", "106.66017244", "10.7626224", "106.6601724"),
                Arguments.of("-90", "-180", "-90.0000000", "-180.0000000"),
                Arguments.of("90.0", "180.0", "90.0000000", "180.0000000"),
                Arguments.of("0", "0", "0.0000000", "0.0000000"));
    }

    @ParameterizedTest
    @MethodSource("invalidCoordinatePairs")
    @DisplayName("updateLocation rejects null or out-of-range coordinates without mutating existing location")
    void updateLocation_invalidCoordinates_throwsIllegalArgumentWithoutMutatingExistingLocation(
            BigDecimal invalidLat, BigDecimal invalidLon) {
        Driver driver = new Driver("user-123");
        driver.updateLocation(new BigDecimal("10.1000000"), new BigDecimal("106.2000000"));

        assertThatThrownBy(() -> driver.updateLocation(invalidLat, invalidLon))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(driver.getLatitude()).isEqualByComparingTo("10.1000000");
        assertThat(driver.getLongitude()).isEqualByComparingTo("106.2000000");
    }

    static Stream<Arguments> invalidCoordinatePairs() {
        return Stream.of(
                Arguments.of(null, new BigDecimal("106.0")),
                Arguments.of(new BigDecimal("10.0"), null),
                Arguments.of(new BigDecimal("-90.0000001"), new BigDecimal("106.0")),
                Arguments.of(new BigDecimal("90.0000001"), new BigDecimal("106.0")),
                Arguments.of(new BigDecimal("10.0"), new BigDecimal("-180.0000001")),
                Arguments.of(new BigDecimal("10.0"), new BigDecimal("180.0000001")),
                Arguments.of(new BigDecimal("1E+100000"), new BigDecimal("106.0")),
                Arguments.of(new BigDecimal("1E-500000000"), new BigDecimal("106.0")),
                Arguments.of(new BigDecimal("10.0"), new BigDecimal("1E-500000000")));
    }

    @Test
    @DisplayName("updateAvailability switches OFFLINE <-> AVAILABLE when coordinates exist and preserves coordinates")
    void updateAvailability_withCoordinates_switchesBetweenAvailableAndOffline() {
        Driver driver = new Driver("user-123");
        driver.updateLocation(new BigDecimal("10.7626220"), new BigDecimal("106.6601720"));

        driver.updateAvailability(true);
        assertThat(driver.getStatus()).isEqualTo(DriverStatus.AVAILABLE);
        assertThat(driver.isAcceptingTrips()).isTrue();

        driver.updateAvailability(true);
        assertThat(driver.getStatus()).isEqualTo(DriverStatus.AVAILABLE);
        assertThat(driver.isAcceptingTrips()).isTrue();

        driver.updateAvailability(false);
        assertThat(driver.getStatus()).isEqualTo(DriverStatus.OFFLINE);
        assertThat(driver.isAcceptingTrips()).isFalse();
        assertThat(driver.getLatitude()).isEqualByComparingTo("10.7626220");
        assertThat(driver.getLongitude()).isEqualByComparingTo("106.6601720");
    }

    @Test
    @DisplayName("updateAvailability rejects going online when coordinates have not been set")
    void updateAvailability_trueWithoutCoordinates_throwsIllegalStateAndRemainsOffline() {
        Driver driver = new Driver("user-123");

        assertThatThrownBy(() -> driver.updateAvailability(true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("location");

        assertThat(driver.getStatus()).isEqualTo(DriverStatus.OFFLINE);
        assertThat(driver.isAcceptingTrips()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = DriverStatus.class, names = {"RESERVED", "BUSY"})
    @DisplayName("updateAvailability rejects changes when driver is RESERVED or BUSY on an active trip")
    void updateAvailability_activeTripStates_throwsIllegalStateWithoutMutatingState(DriverStatus activeStatus) {
        Driver driver = new Driver("user-123");
        driver.updateLocation(new BigDecimal("10.7626220"), new BigDecimal("106.6601720"));
        ReflectionTestUtils.setField(driver, "status", activeStatus);
        ReflectionTestUtils.setField(driver, "acceptingTrips", true);
        ReflectionTestUtils.setField(driver, "currentTripId", "trip-123");

        assertThatThrownBy(() -> driver.updateAvailability(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active trip");
        assertThatThrownBy(() -> driver.updateAvailability(true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active trip");

        assertThat(driver.getStatus()).isEqualTo(activeStatus);
        assertThat(driver.isAcceptingTrips()).isTrue();
        assertThat(driver.getCurrentTripId()).isEqualTo("trip-123");
    }
}
