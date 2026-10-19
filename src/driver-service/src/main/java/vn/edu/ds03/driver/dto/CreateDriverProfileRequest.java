package vn.edu.ds03.driver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.edu.ds03.driver.model.VehicleType;

public record CreateDriverProfileRequest(
        @NotBlank @Size(max = 20) String licensePlate,
        @NotNull VehicleType vehicleType) {
}
