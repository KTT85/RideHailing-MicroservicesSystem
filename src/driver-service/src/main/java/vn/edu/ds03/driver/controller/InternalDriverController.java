package vn.edu.ds03.driver.controller;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.ds03.driver.dto.DriverCandidateResponse;
import vn.edu.ds03.driver.dto.DriverProfileResponse;
import vn.edu.ds03.driver.dto.DriverTripActionRequest;
import vn.edu.ds03.driver.model.VehicleType;
import vn.edu.ds03.driver.service.DriverCandidateService;
import vn.edu.ds03.driver.service.DriverReservationService;

@RestController
@RequestMapping("/internal/drivers")
public class InternalDriverController {

    private final DriverCandidateService driverCandidateService;
    private final DriverReservationService driverReservationService;

    public InternalDriverController(
            DriverCandidateService driverCandidateService,
            DriverReservationService driverReservationService) {
        this.driverCandidateService = driverCandidateService;
        this.driverReservationService = driverReservationService;
    }

    @GetMapping("/candidates")
    public List<DriverCandidateResponse> getAvailableCandidates(
            @RequestParam BigDecimal pickupLatitude,
            @RequestParam BigDecimal pickupLongitude,
            @RequestParam VehicleType vehicleType,
            @RequestParam(defaultValue = "5") int limit) {
        return driverCandidateService.findNearestCandidates(
                pickupLatitude, pickupLongitude, vehicleType, limit);
    }

    @PostMapping("/{driverId}/reserve")
    public DriverProfileResponse reserveDriver(
            @PathVariable String driverId,
            @Valid @RequestBody DriverTripActionRequest request) {
        return driverReservationService.reserveDriver(driverId, request.tripId());
    }

    @PostMapping("/{driverId}/start")
    public DriverProfileResponse startTrip(
            @PathVariable String driverId,
            @Valid @RequestBody DriverTripActionRequest request) {
        return driverReservationService.startTrip(driverId, request.tripId());
    }

    @PostMapping("/{driverId}/release")
    public DriverProfileResponse releaseDriver(
            @PathVariable String driverId,
            @Valid @RequestBody DriverTripActionRequest request) {
        return driverReservationService.releaseDriver(driverId, request.tripId());
    }
}
