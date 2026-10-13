package vn.edu.ds03.user.dto;

import jakarta.validation.constraints.*;
import vn.edu.ds03.user.model.User.Role;

public record RegisterRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Pattern(regexp = "^(0[0-9]{9}|\\+84[0-9]{9})$",
                message = "Phone must use 0xxxxxxxxx or +84xxxxxxxxx format") String phone,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotNull Role role
) {
    @AssertTrue(message = "Registration role must be PASSENGER or DRIVER")
    public boolean isPublicRoleAllowed() {
        return role == null || role == Role.PASSENGER || role == Role.DRIVER;
    }

    @Override
    public String toString() {
        return "RegisterRequest[redacted]";
    }
}
