package vn.edu.ds03.driver.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.ds03.driver.dto.CreateDriverProfileRequest;
import vn.edu.ds03.driver.dto.DriverProfileResponse;
import vn.edu.ds03.driver.service.DriverProfileService;

@RestController
@RequestMapping("/drivers")
public class DriverProfileController {
    private final DriverProfileService driverProfileService;

    public DriverProfileController(DriverProfileService driverProfileService) {
        this.driverProfileService = driverProfileService;
    }

    @PostMapping("/profile")
    @ResponseStatus(HttpStatus.CREATED)
    public DriverProfileResponse createProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateDriverProfileRequest request) {
        return driverProfileService.createProfile(jwt.getSubject(), request);
    }
}
