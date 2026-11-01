package vn.edu.ds03.user.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.ds03.user.dto.RegisterRequest;
import vn.edu.ds03.user.model.User;
import vn.edu.ds03.user.model.User.Role;
import vn.edu.ds03.user.repository.UserRepository;

class UserServiceTest {
    private final UserRepository repository = mock(UserRepository.class);
    private final UserService service = new UserService(repository);

    private RegisterRequest request(String password, Role role) {
        return new RegisterRequest(" Demo User ", "USER@example.com", "0912345678", password, role);
    }

    @Test
    void registrationNormalizesDataAndStoresBcrypt() {
        when(repository.saveAndFlush(any(User.class))).thenAnswer(call -> {
            User user = call.getArgument(0);
            assertEquals("user@example.com", user.getEmail());
            assertEquals("0912345678", user.getPhone());
            assertEquals(User.UserStatus.ACTIVE, user.getStatus());
            ReflectionTestUtils.setField(user, "id", "test-user");
            ReflectionTestUtils.setField(user, "createdAt", LocalDateTime.of(2026, 10, 13, 14, 0));
            return user;
        });
        for (Role role : new Role[]{Role.PASSENGER, Role.DRIVER}) {
            String password = "á".repeat(36); // Exactly 72 UTF-8 bytes.
            var response = service.register(request(password, role));
            assertEquals("Demo User", response.name());
            assertEquals(role, response.role());
            assertEquals(java.time.ZoneOffset.UTC, response.createdAt().getOffset());
        }
        var users = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(repository, times(2)).saveAndFlush(users.capture());
        for (User user : users.getAllValues()) {
            assertTrue(BCrypt.checkpw("á".repeat(36), user.getPasswordHash()));
        }
        assertNotEquals(users.getAllValues().get(0).getPasswordHash(), users.getAllValues().get(1).getPasswordHash());
    }

    @Test
    void duplicateEmailIsRejected() {
        when(repository.existsByEmail("user@example.com")).thenReturn(true);
        var error = assertThrows(ResponseStatusException.class,
                () -> service.register(request("test-password", Role.PASSENGER)));
        assertEquals(409, error.getStatusCode().value());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void duplicatePhoneIsRejected() {
        when(repository.existsByPhone("0912345678")).thenReturn(true);
        var error = assertThrows(ResponseStatusException.class,
                () -> service.register(request("test-password", Role.DRIVER)));
        assertEquals(409, error.getStatusCode().value());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void invalidRoleAndBcryptByteLimitAreRejectedBeforeDatabase() {
        for (Role role : new Role[]{Role.ADMIN, null}) {
            assertEquals(400, assertThrows(ResponseStatusException.class,
                    () -> service.register(request("test-password", role))).getStatusCode().value());
        }
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.register(request("á".repeat(37), Role.PASSENGER))).getStatusCode().value());
        verifyNoInteractions(repository);
    }
}
