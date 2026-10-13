package vn.edu.ds03.user.dto;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UserResponse user
) {
    @Override
    public String toString() {
        return "LoginResponse[redacted]";
    }
}
