package vn.edu.ds03.matching.dto;

import java.math.BigDecimal;

public record DriverCandidateDto(
        String driverId,
        BigDecimal latitude,
        BigDecimal longitude,
        double distanceKm,
        String vehicleType,
        String licensePlate) {
}
