package vn.edu.ds03.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 254)
        @Pattern(regexp = "^(0[0-9]{9}|[^\\s@]+@[^\\s@]+\\.[^\\s@]+)$",
                message = "Nhập email hợp lệ hoặc số điện thoại 10 chữ số bắt đầu bằng 0")
        String identifier,
        @NotBlank @Size(max = 72) String password
) {
    @Override
    public String toString() {
        return "LoginRequest[redacted]";
    }
}
