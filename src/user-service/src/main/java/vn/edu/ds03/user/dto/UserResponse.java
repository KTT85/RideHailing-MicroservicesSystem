package vn.edu.ds03.user.dto;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import vn.edu.ds03.user.model.User.Role;
import vn.edu.ds03.user.model.User;
import vn.edu.ds03.user.model.User.UserStatus;

public record UserResponse(
        String id,
        String name,
        String email,
        String phone,
        Role role,
        UserStatus status,
        OffsetDateTime createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(),
                user.getPhone(), user.getRole(), user.getStatus(),
                user.getCreatedAt().atOffset(ZoneOffset.UTC));
    }
}
