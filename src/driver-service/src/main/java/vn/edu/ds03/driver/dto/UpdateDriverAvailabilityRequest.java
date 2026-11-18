package vn.edu.ds03.driver.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateDriverAvailabilityRequest(
        @NotNull Boolean acceptingTrips) {
}
