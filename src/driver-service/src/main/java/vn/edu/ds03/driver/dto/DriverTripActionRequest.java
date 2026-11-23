package vn.edu.ds03.driver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DriverTripActionRequest(
        @NotBlank
        @Size(max = 36)
        @Pattern(regexp = "^[A-Za-z0-9\\-]+$", message = "tripId contains invalid characters")
        String tripId) {
}
