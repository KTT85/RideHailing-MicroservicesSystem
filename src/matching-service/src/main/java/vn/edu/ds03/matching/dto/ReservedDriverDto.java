package vn.edu.ds03.matching.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReservedDriverDto(
        String driverId,
        String status,
        String currentTripId,
        VehicleInfo vehicle,
        String userId) {

    public ReservedDriverDto(String driverId, String status, String currentTripId, VehicleInfo vehicle) {
        this(driverId, status, currentTripId, vehicle, null);
    }

    public record VehicleInfo(
            String vehicleId,
            String licensePlate,
            String vehicleType) {
    }
}
