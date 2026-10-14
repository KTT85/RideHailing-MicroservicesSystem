package vn.edu.ds03.user.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import vn.edu.ds03.user.dto.RegisterRequest;
import vn.edu.ds03.user.dto.LoginRequest;
import vn.edu.ds03.user.dto.LoginResponse;
import vn.edu.ds03.user.dto.UserResponse;
import vn.edu.ds03.user.service.UserService;
import vn.edu.ds03.user.service.AuthService;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final UserService userService;
    private final AuthService authService;

    public AuthController(UserService userService, AuthService authService) {
        this.userService = userService;
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return userService.register(request);
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }
}
