package vn.edu.ds03.driver.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
}
