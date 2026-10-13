package vn.edu.ds03.user.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.ds03.user.dto.RegisterRequest;
import vn.edu.ds03.user.dto.UserResponse;
import vn.edu.ds03.user.model.User;
import vn.edu.ds03.user.model.User.Role;
import vn.edu.ds03.user.repository.UserRepository;

@Service
public class UserService {
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (request.role() != Role.PASSENGER && request.role() != Role.DRIVER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Chỉ được đăng ký PASSENGER hoặc DRIVER");
        }
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Mật khẩu không được vượt quá 72 byte UTF-8");
        }
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        String phone = request.phone();
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email đã được sử dụng");
        }
        if (userRepository.existsByPhone(phone)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Số điện thoại đã được sử dụng");
        }
        String passwordHash = BCrypt.hashpw(request.password(), BCrypt.gensalt(10));
        User user = new User(request.name().strip(), email, phone, passwordHash, request.role());
        return UserResponse.from(userRepository.saveAndFlush(user));
    }
}
