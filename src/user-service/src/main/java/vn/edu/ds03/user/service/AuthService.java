package vn.edu.ds03.user.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.ds03.user.dto.*;
import vn.edu.ds03.user.model.User;
import vn.edu.ds03.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private final UserRepository repository;
    private final JwtService jwtService;

    public AuthService(UserRepository repository, JwtService jwtService) {
        this.repository = repository;
        this.jwtService = jwtService;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) throw invalidCredentials();
        String identifier = request.identifier().strip();
        User user;
        if (identifier.contains("@")) {
            user = repository.findByEmail(identifier.toLowerCase(Locale.ROOT))
                    .orElseThrow(this::invalidCredentials);
        } else {
            user = repository.findByPhone(identifier).orElseThrow(this::invalidCredentials);
        }
        boolean matches;
        try {
            matches = BCrypt.checkpw(request.password(), user.getPasswordHash());
        } catch (IllegalArgumentException invalidHash) {
            matches = false;
        }
        if (!matches || user.getStatus() != User.UserStatus.ACTIVE) throw invalidCredentials();
        log.info("User login succeeded userId={} role={}", user.getId(), user.getRole());
        return new LoginResponse(jwtService.issue(user), "Bearer", jwtService.getExpiresIn(), UserResponse.from(user));
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(String userId) {
        User user = repository.findById(userId).orElseThrow(this::invalidCredentials);
        if (user.getStatus() != User.UserStatus.ACTIVE) throw invalidCredentials();
        return UserResponse.from(user);
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Thông tin đăng nhập không hợp lệ hoặc tài khoản không khả dụng");
    }
}
