package vn.edu.ds03.driver.controller;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.ds03.driver.dto.DriverCandidateResponse;
import vn.edu.ds03.driver.model.VehicleType;
import vn.edu.ds03.driver.service.DriverCandidateService;

@RestController
@RequestMapping("/internal/drivers")
public class InternalDriverController {

    private final DriverCandidateService driverCandidateService;

    public InternalDriverController(DriverCandidateService driverCandidateService) {
        this.driverCandidateService = driverCandidateService;
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
}
