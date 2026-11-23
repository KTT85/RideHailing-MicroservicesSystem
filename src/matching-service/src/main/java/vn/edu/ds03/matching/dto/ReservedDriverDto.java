package vn.edu.ds03.matching.dto;

public record ReservedDriverDto(
        String driverId,
        String status,
        String currentTripId,
        VehicleInfo vehicle) {

    public record VehicleInfo(
            String vehicleId,
            String licensePlate,
            String vehicleType) {
    }
}
