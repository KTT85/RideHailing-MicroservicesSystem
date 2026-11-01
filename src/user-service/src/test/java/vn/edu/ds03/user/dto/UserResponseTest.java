package vn.edu.ds03.user.dto;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import vn.edu.ds03.user.model.User;

class UserResponseTest {
    @Test
    void utcTimestampIsReturnedWithoutApplyingAnotherOffset() {
        User user = new User("Demo User", "user@example.com", "0912345678",
                "unused-test-hash", User.Role.PASSENGER);
        LocalDateTime stored = LocalDateTime.of(2026, 10, 14, 7, 0);
        ReflectionTestUtils.setField(user, "createdAt", stored);
        var response = UserResponse.from(user);
        assertEquals(ZoneOffset.UTC, response.createdAt().getOffset());
        assertEquals(Instant.parse("2026-10-14T07:00:00Z"), response.createdAt().toInstant());
    }

    @Test
    void newUserTimestampIsGeneratedInUtc() {
        User user = new User("Demo User", "user@example.com", "0912345678",
                "unused-test-hash", User.Role.DRIVER);
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
        ReflectionTestUtils.invokeMethod(user, "initialize");
        LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC);
        assertFalse(user.getCreatedAt().isBefore(before));
        assertFalse(user.getCreatedAt().isAfter(after));
    }
}
