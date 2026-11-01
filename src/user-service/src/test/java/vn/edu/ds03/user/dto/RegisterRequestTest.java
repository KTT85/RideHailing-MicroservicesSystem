package vn.edu.ds03.user.dto;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.validation.*;
import org.junit.jupiter.api.*;
import vn.edu.ds03.user.model.User.Role;

class RegisterRequestTest {
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setup() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() { factory.close(); }

    private RegisterRequest request(String name, String email, String phone, String password, Role role) {
        return new RegisterRequest(name, email, phone, password, role);
    }

    private void rejects(RegisterRequest request, String field) {
        assertTrue(validator.validate(request).stream().anyMatch(error ->
                error.getPropertyPath().toString().equals(field)), "Expected error: " + field);
    }

    @Test
    void validDataAndBoundariesAreAccepted() {
        for (Role role : new Role[]{Role.PASSENGER, Role.DRIVER}) {
            for (int length : new int[]{8, 72}) {
                assertTrue(validator.validate(request("x".repeat(100), "user@example.com",
                        "0912345678", "x".repeat(length), role)).isEmpty());
            }
        }
        assertFalse(request("Demo", "user@example.com", "0912345678", "test-secret", Role.DRIVER)
                .toString().contains("test-secret"));
    }

    @Test
    void requiredFieldsAreChecked() {
        for (String missing : new String[]{null, ""}) {
            rejects(request(missing, "user@example.com", "0912345678", "test-password", Role.DRIVER), "name");
            rejects(request("Demo", missing, "0912345678", "test-password", Role.DRIVER), "email");
            rejects(request("Demo", "user@example.com", missing, "test-password", Role.DRIVER), "phone");
            rejects(request("Demo", "user@example.com", "0912345678", missing, Role.DRIVER), "password");
        }
        rejects(request("Demo", "user@example.com", "0912345678", "test-password", null), "role");
    }

    @Test
    void invalidEmailAndPhoneAreRejected() {
        rejects(request("Demo", "invalid-email", "0912345678", "test-password", Role.DRIVER), "email");
        for (String phone : new String[]{"+84912345678", "9123456789", "091234567", "09123456789", "09123abc78"}) {
            rejects(request("Demo", "user@example.com", phone, "test-password", Role.DRIVER), "phone");
        }
    }

    @Test
    void invalidLengthsAndRegistrationRoleAreRejected() {
        rejects(request("x".repeat(101), "user@example.com", "0912345678", "test-password", Role.DRIVER), "name");
        rejects(request("Demo", "x".repeat(255), "0912345678", "test-password", Role.DRIVER), "email");
        for (String password : new String[]{"x".repeat(7), "x".repeat(73), "        "}) {
            rejects(request("Demo", "user@example.com", "0912345678", password, Role.DRIVER), "password");
        }
        rejects(request("Demo", "user@example.com", "0912345678", "test-password", Role.ADMIN), "publicRoleAllowed");
    }
}
