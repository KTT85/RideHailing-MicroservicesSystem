package vn.edu.ds03.driver.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record UpdateDriverLocationRequest(
        @NotNull
        @DecimalMin(value = "-90.0")
        @DecimalMax(value = "90.0")
        BigDecimal latitude,
        @NotNull
        @DecimalMin(value = "-180.0")
        @DecimalMax(value = "180.0")
        BigDecimal longitude) {

    private static final int MAX_INPUT_SCALE = 20;

    @AssertTrue(message = "Coordinate precision exceeds supported scale")
    public boolean isCoordinateScaleSupported() {
        return (latitude == null || Math.abs((long) latitude.scale()) <= MAX_INPUT_SCALE)
                && (longitude == null || Math.abs((long) longitude.scale()) <= MAX_INPUT_SCALE);
    }
}
