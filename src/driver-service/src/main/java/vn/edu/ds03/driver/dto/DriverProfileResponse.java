package vn.edu.ds03.driver.dto;

import java.math.BigDecimal;
import vn.edu.ds03.driver.model.Driver;
import vn.edu.ds03.driver.model.DriverStatus;
import vn.edu.ds03.driver.model.Vehicle;
import vn.edu.ds03.driver.model.VehicleType;

public record DriverProfileResponse(
        String driverId,
        String userId,
        DriverStatus status,
        boolean acceptingTrips,
        String currentTripId,
        BigDecimal latitude,
        BigDecimal longitude,
        VehicleProfile vehicle) {

    public static DriverProfileResponse from(Driver driver) {
        Vehicle vehicle = driver.getVehicle();
        return new DriverProfileResponse(
                driver.getId(),
                driver.getUserId(),
                driver.getStatus(),
                driver.isAcceptingTrips(),
                driver.getCurrentTripId(),
                driver.getLatitude(),
                driver.getLongitude(),
                new VehicleProfile(
                        vehicle.getId(),
                        vehicle.getLicensePlate(),
                        vehicle.getVehicleType()));
    }

    public record VehicleProfile(String vehicleId, String licensePlate, VehicleType vehicleType) {
    }
}
