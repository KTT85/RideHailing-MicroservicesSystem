package vn.edu.ds03.driver.service;

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
    private final DriverRepository driverRepository;

    public DriverProfileService(DriverRepository driverRepository) {
        this.driverRepository = driverRepository;
    }

    @Transactional
    public DriverProfileResponse createProfile(String userId, CreateDriverProfileRequest request) {
        if (driverRepository.existsByUserId(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Driver profile already exists");
        }

        Driver driver = new Driver(userId);
        driver.attachVehicle(new Vehicle(request.licensePlate(), request.vehicleType()));
        try {
            driver = driverRepository.saveAndFlush(driver);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Driver profile or license plate already exists",
                    exception);
        }

        Vehicle vehicle = driver.getVehicle();
        return new DriverProfileResponse(
                driver.getId(),
                driver.getUserId(),
                driver.getStatus(),
                driver.isAcceptingTrips(),
                new DriverProfileResponse.VehicleProfile(
                        vehicle.getId(), vehicle.getLicensePlate(), vehicle.getVehicleType()));
    }
}
