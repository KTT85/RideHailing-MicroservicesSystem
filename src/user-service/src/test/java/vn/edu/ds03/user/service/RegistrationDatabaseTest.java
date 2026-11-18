package vn.edu.ds03.user.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import vn.edu.ds03.user.dto.RegisterRequest;
import vn.edu.ds03.user.model.User.Role;
import vn.edu.ds03.user.repository.UserRepository;

/** Opt-in: uses an existing local users table; removes only records created by this test. */
@EnabledIfEnvironmentVariable(named = "USER_DB_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.profiles.active=local"})
class RegistrationDatabaseTest {
    @DynamicPropertySource
    static void jwtSecret(DynamicPropertyRegistry registry) {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        String secret = java.util.Base64.getEncoder().encodeToString(key);
        registry.add("app.jwt.secret", () -> secret);
    }
    @Autowired private UserService service;
    @Autowired private UserRepository repository;
    @Autowired private JdbcTemplate jdbc;
    private final String email = "registration-test-" + UUID.randomUUID() + "@example.test";

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM users WHERE email = ?", email);
    }

    private String unusedPhone() {
        for (int attempt = 0; attempt < 100; attempt++) {
            String phone = "0" + ThreadLocalRandom.current().nextLong(100000000L, 1000000000L);
            if (!repository.existsByPhone(phone)) return phone;
        }
        throw new IllegalStateException("Could not allocate a test phone");
    }

    @Test
    void realDatabaseStoresHashAndGeneratedFields() {
        String password = "test-only-password";
        var response = service.register(new RegisterRequest("Demo", email, unusedPhone(), password, Role.DRIVER));
        var stored = repository.findByEmail(email).orElseThrow();
        assertEquals(response.id(), stored.getId());
        assertDoesNotThrow(() -> UUID.fromString(stored.getId()));
        assertNotNull(stored.getCreatedAt());
        assertTrue(BCrypt.checkpw(password, stored.getPasswordHash()));
        assertEquals(Role.DRIVER, stored.getRole());
    }

    @Test
    void concurrentSameEmailCreatesExactlyOneAccount() throws Exception {
        var first = new RegisterRequest("Demo", email, unusedPhone(), "test-password-one", Role.PASSENGER);
        String secondPhone;
        do { secondPhone = unusedPhone(); } while (secondPhone.equals(first.phone()));
        var second = new RegisterRequest("Demo", email, secondPhone, "test-password-two", Role.DRIVER);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> task1 = () -> attempt(first, ready, start);
            Callable<Boolean> task2 = () -> attempt(second, ready, start);
            var result1 = executor.submit(task1);
            var result2 = executor.submit(task2);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int successes = (result1.get(20, TimeUnit.SECONDS) ? 1 : 0)
                    + (result2.get(20, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email));
        } finally {
            start.countDown();
        }
    }

    private boolean attempt(RegisterRequest request, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
        try {
            service.register(request);
            return true;
        } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            return false;
        } catch (org.springframework.web.server.ResponseStatusException conflict) {
            assertEquals(409, conflict.getStatusCode().value());
            return false;
        }
    }
}
