package vn.edu.ds03.driver.dto;

import vn.edu.ds03.driver.model.DriverStatus;
import vn.edu.ds03.driver.model.VehicleType;

public record DriverProfileResponse(
        String driverId,
        String userId,
        DriverStatus status,
        boolean acceptingTrips,
        VehicleProfile vehicle) {

    public record VehicleProfile(String vehicleId, String licensePlate, VehicleType vehicleType) {
    }
}
