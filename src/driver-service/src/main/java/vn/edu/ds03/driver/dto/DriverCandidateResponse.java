package vn.edu.ds03.driver.dto;

import java.math.BigDecimal;
import vn.edu.ds03.driver.model.VehicleType;

public record DriverCandidateResponse(
        String driverId,
        String userId,
        String vehicleId,
        String licensePlate,
        VehicleType vehicleType,
        BigDecimal latitude,
        BigDecimal longitude,
        double distanceKm) {
}
