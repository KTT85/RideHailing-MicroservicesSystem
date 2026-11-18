package vn.edu.ds03.driver.service;

import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.ds03.driver.dto.DriverProfileResponse;
import vn.edu.ds03.driver.model.Driver;
import vn.edu.ds03.driver.model.DriverStatus;
import vn.edu.ds03.driver.repository.DriverRepository;

@Service
public class DriverReservationService {

    private static final Logger log = LoggerFactory.getLogger(DriverReservationService.class);
    private static final int MAX_IDENTIFIER_LENGTH = 36;
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-z0-9\\-]+$");

    private final DriverRepository driverRepository;

    public DriverReservationService(DriverRepository driverRepository) {
        this.driverRepository = driverRepository;
    }

    @Transactional
    public DriverProfileResponse reserveDriver(String driverId, String tripId) {
        String normalizedDriverId = normalizeIdentifier(driverId, "driverId");
        String normalizedTripId = normalizeIdentifier(tripId, "tripId");

        int updatedRows;
        try {
            updatedRows = driverRepository.reserveAvailableDriver(normalizedDriverId, normalizedTripId);
        } catch (DataIntegrityViolationException exception) {
            log.warn("Trip reservation conflict on unique tripId={}", normalizedTripId);
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Trip is already assigned to another driver");
        }

        Driver driver = loadDriverOrThrow(normalizedDriverId);
        if (updatedRows == 1) {
            return DriverProfileResponse.from(driver);
        }

        if (normalizedTripId.equals(driver.getCurrentTripId())
                && (driver.getStatus() == DriverStatus.RESERVED
                        || driver.getStatus() == DriverStatus.BUSY)) {
            return DriverProfileResponse.from(driver);
        }

        throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Driver is not available for reservation");
    }

    @Transactional
    public DriverProfileResponse startTrip(String driverId, String tripId) {
        String normalizedDriverId = normalizeIdentifier(driverId, "driverId");
        String normalizedTripId = normalizeIdentifier(tripId, "tripId");

        int updatedRows = driverRepository.startReservedTrip(normalizedDriverId, normalizedTripId);
        Driver driver = loadDriverOrThrow(normalizedDriverId);
        if (updatedRows == 1) {
            return DriverProfileResponse.from(driver);
        }

        if (driver.getStatus() == DriverStatus.BUSY
                && normalizedTripId.equals(driver.getCurrentTripId())) {
            return DriverProfileResponse.from(driver);
        }

        throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Driver does not hold a matching reservation to start");
    }

    @Transactional
    public DriverProfileResponse releaseDriver(String driverId, String tripId) {
        String normalizedDriverId = normalizeIdentifier(driverId, "driverId");
        String normalizedTripId = normalizeIdentifier(tripId, "tripId");

        int updatedRows = driverRepository.releaseDriverFromTrip(normalizedDriverId, normalizedTripId);
        Driver driver = loadDriverOrThrow(normalizedDriverId);
        if (updatedRows == 1 || driver.getCurrentTripId() == null) {
            return DriverProfileResponse.from(driver);
        }

        throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Driver is currently assigned to a different trip");
    }

    private Driver loadDriverOrThrow(String normalizedDriverId) {
        return driverRepository.findWithVehicleById(normalizedDriverId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Driver profile not found"));
    }

    private static String normalizeIdentifier(String rawId, String fieldName) {
        if (rawId == null || rawId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
        }
        String normalized = rawId.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_IDENTIFIER_LENGTH
                || !IDENTIFIER_PATTERN.matcher(normalized).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + fieldName);
        }
        return normalized;
    }
}
