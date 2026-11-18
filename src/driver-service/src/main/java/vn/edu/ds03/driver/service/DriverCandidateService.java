package vn.edu.ds03.driver.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.ds03.driver.dto.DriverCandidateResponse;
import vn.edu.ds03.driver.model.Driver;
import vn.edu.ds03.driver.model.Vehicle;
import vn.edu.ds03.driver.model.VehicleType;
import vn.edu.ds03.driver.repository.DriverRepository;

@Service
public class DriverCandidateService {

    private static final BigDecimal MIN_LATITUDE = new BigDecimal("-90");
    private static final BigDecimal MAX_LATITUDE = new BigDecimal("90");
    private static final BigDecimal MIN_LONGITUDE = new BigDecimal("-180");
    private static final BigDecimal MAX_LONGITUDE = new BigDecimal("180");
    private static final int MAX_INPUT_SCALE = 20;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 20;
    private static final double EARTH_RADIUS_KM = 6371.0088;

    private final DriverRepository driverRepository;

    public DriverCandidateService(DriverRepository driverRepository) {
        this.driverRepository = driverRepository;
    }

    @Transactional(readOnly = true)
    public List<DriverCandidateResponse> findNearestCandidates(
            BigDecimal pickupLatitude,
            BigDecimal pickupLongitude,
            VehicleType vehicleType,
            int limit) {
        validateQuery(pickupLatitude, pickupLongitude, vehicleType, limit);

        double pickupLat = pickupLatitude.doubleValue();
        double pickupLon = pickupLongitude.doubleValue();

        return driverRepository.findAvailableCandidatesByVehicleType(vehicleType).stream()
                .map(driver -> toCandidateResponse(driver, pickupLat, pickupLon))
                .sorted(Comparator.comparingDouble(DriverCandidateResponse::distanceKm)
                        .thenComparing(DriverCandidateResponse::driverId))
                .limit(limit)
                .toList();
    }

    private static void validateQuery(
            BigDecimal pickupLatitude,
            BigDecimal pickupLongitude,
            VehicleType vehicleType,
            int limit) {
        if (pickupLatitude == null || pickupLongitude == null || vehicleType == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Pickup coordinates and vehicleType are required");
        }
        if (Math.abs((long) pickupLatitude.scale()) > MAX_INPUT_SCALE
                || Math.abs((long) pickupLongitude.scale()) > MAX_INPUT_SCALE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Coordinate precision exceeds supported scale");
        }
        if (pickupLatitude.compareTo(MIN_LATITUDE) < 0 || pickupLatitude.compareTo(MAX_LATITUDE) > 0
                || pickupLongitude.compareTo(MIN_LONGITUDE) < 0 || pickupLongitude.compareTo(MAX_LONGITUDE) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Pickup coordinates are out of geographic bounds");
        }
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Limit must be between 1 and 20");
        }
    }

    private static DriverCandidateResponse toCandidateResponse(
            Driver driver, double pickupLat, double pickupLon) {
        Vehicle vehicle = driver.getVehicle();
        double distanceKm = haversineKm(
                pickupLat,
                pickupLon,
                driver.getLatitude().doubleValue(),
                driver.getLongitude().doubleValue());
        return new DriverCandidateResponse(
                driver.getId(),
                driver.getUserId(),
                vehicle.getId(),
                vehicle.getLicensePlate(),
                vehicle.getVehicleType(),
                driver.getLatitude(),
                driver.getLongitude(),
                distanceKm);
    }

    static double haversineKm(double lat1Deg, double lon1Deg, double lat2Deg, double lon2Deg) {
        double dLat = Math.toRadians(lat2Deg - lat1Deg);
        double dLon = Math.toRadians(lon2Deg - lon1Deg);
        double lat1Rad = Math.toRadians(lat1Deg);
        double lat2Rad = Math.toRadians(lat2Deg);

        double sinHalfLat = Math.sin(dLat / 2.0);
        double sinHalfLon = Math.sin(dLon / 2.0);
        double a = sinHalfLat * sinHalfLat
                + Math.cos(lat1Rad) * Math.cos(lat2Rad) * sinHalfLon * sinHalfLon;
        double clamped = Math.min(1.0, Math.max(0.0, a));
        double c = 2.0 * Math.atan2(Math.sqrt(clamped), Math.sqrt(1.0 - clamped));
        return EARTH_RADIUS_KM * c;
    }
}
