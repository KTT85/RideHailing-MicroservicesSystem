package vn.edu.ds03.driver.service;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.ds03.driver.dto.CreateDriverProfileRequest;
import vn.edu.ds03.driver.dto.DriverProfileResponse;
import vn.edu.ds03.driver.model.Driver;
import vn.edu.ds03.driver.model.Vehicle;
import vn.edu.ds03.driver.repository.DriverRepository;

@Service
public class DriverProfileService {

    private static final Logger log = LoggerFactory.getLogger(DriverProfileService.class);

    private final DriverRepository driverRepository;

    public DriverProfileService(DriverRepository driverRepository) {
        this.driverRepository = driverRepository;
    }

    @Transactional
    public DriverProfileResponse createProfile(String userId, CreateDriverProfileRequest request) {
        String normalizedUserId = normalizeAndValidateUserId(userId);
        if (driverRepository.existsByUserId(normalizedUserId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Driver profile already exists");
        }

        Driver driver = new Driver(normalizedUserId);
        driver.attachVehicle(new Vehicle(request.licensePlate(), request.vehicleType()));
        try {
            driver = driverRepository.saveAndFlush(driver);
        } catch (DataIntegrityViolationException exception) {
            log.warn("Driver profile creation conflict for userId={}", normalizedUserId);
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Driver profile or license plate already exists");
        }

        return DriverProfileResponse.from(driver);
    }

    @Transactional(readOnly = true)
    public DriverProfileResponse getCurrentProfile(String userId) {
        String normalizedUserId = normalizeAndValidateUserId(userId);
        return driverRepository.findWithVehicleByUserId(normalizedUserId)
                .map(DriverProfileResponse::from)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Driver profile not found"));
    }

    private static String normalizeAndValidateUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token subject");
        }
        return userId.strip().toLowerCase(Locale.ROOT);
    }
}
