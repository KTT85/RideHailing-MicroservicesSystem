package vn.edu.ds03.user.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.*;
import java.util.*;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;
import vn.edu.ds03.user.config.*;
import vn.edu.ds03.user.model.User;
import vn.edu.ds03.user.repository.UserRepository;
import vn.edu.ds03.user.service.*;
import vn.edu.ds03.user.exception.GlobalExceptionHandler;

@SpringJUnitWebConfig(LoginSecurityTest.Config.class)
@TestPropertySource(properties = {"app.jwt.issuer=ds03-user-service", "app.jwt.expires-in-seconds=3600"})
class LoginSecurityTest {
    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, JwtConfig.class, JwtService.class, AuthService.class,
            UserService.class, AuthController.class, UserController.class, GlobalExceptionHandler.class})
    static class Config {
        @Bean UserRepository repository() { return mock(UserRepository.class); }
    }

    @DynamicPropertySource
    static void secret(DynamicPropertyRegistry registry) {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        registry.add("app.jwt.secret", () -> Base64.getEncoder().encodeToString(key));
    }

    @Autowired WebApplicationContext context;
    @Autowired UserRepository repository;
    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder decoder;
    private MockMvc mvc;
    private User user;
    private static final String PASSWORD = "test-only-password";

    @BeforeEach
    void setup() {
        reset(repository);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        user = new User("Demo User", "user@example.com", "0912345678",
                BCrypt.hashpw(PASSWORD, BCrypt.gensalt(10)), User.Role.PASSENGER);
        ReflectionTestUtils.setField(user, "id", "sample-user");
        ReflectionTestUtils.setField(user, "createdAt", LocalDateTime.of(2026, 10, 13, 14, 0));
        when(repository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(repository.findByPhone("0912345678")).thenReturn(Optional.of(user));
        when(repository.findById("sample-user")).thenReturn(Optional.of(user));
    }

    private String login(String email, String password) throws Exception {
        return mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(JsonMapper.builder().build().writeValueAsString(Map.of("identifier", email, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void loginIssuesJwtAndMeReturnsOnlyCurrentUser() throws Exception {
        String body = login("USER@example.com", PASSWORD);
        var response = JsonMapper.builder().build().readTree(body);
        String token = response.get("accessToken").asText();
        Jwt decoded = decoder.decode(token);
        assertEquals("sample-user", decoded.getSubject());
        assertEquals("sample-user", decoded.getClaimAsString("userId"));
        assertEquals("PASSENGER", decoded.getClaimAsString("role"));
        assertEquals(3600, response.get("expiresIn").asLong());
        assertEquals("Bearer", response.get("tokenType").asText());
        assertFalse(body.contains("password"));
        String me = mvc.perform(get("/users/me").header("Authorization", "Bearer " + token)
                .param("userId", "another-user"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(me.contains("sample-user"));
        assertFalse(me.contains("password"));
    }

    @Test
    void phoneLoginIssuesTokenForSameAccount() throws Exception {
        String body = login("0912345678", PASSWORD);
        String token = JsonMapper.builder().build().readTree(body).get("accessToken").asText();
        assertEquals("sample-user", decoder.decode(token).getSubject());
        assertEquals("PASSENGER", decoder.decode(token).getClaimAsString("role"));
        verify(repository).findByPhone("0912345678");
        verify(repository, never()).findByEmail(anyString());
    }

    @Test
    void oversizedJsonIsRejectedBeforeAuthenticationOrDatabase() throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + "x".repeat(65536) + "\"}"))
                .andExpect(status().is(413));
        verifyNoInteractions(repository);
    }

    @Test
    void wrongPasswordUnknownEmailAndDisabledAccountReturn401() throws Exception {
        for (String[] credentials : new String[][]{{"user@example.com", "wrong-password"}, {"unknown@example.com", PASSWORD},
                {"0912345678", "wrong-password"}, {"0999999999", PASSWORD}}) {
            mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(JsonMapper.builder().build().writeValueAsString(Map.of("identifier", credentials[0], "password", credentials[1]))))
                    .andExpect(status().isUnauthorized());
        }
        user.setStatus(User.UserStatus.DISABLED);
        for (String identifier : new String[]{"user@example.com", "0912345678"}) {
            mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(JsonMapper.builder().build().writeValueAsString(Map.of("identifier", identifier, "password", PASSWORD))))
                    .andExpect(status().isUnauthorized());
        }
    }

    private String token(String role, Instant expiration) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().issuer("ds03-user-service").subject("sample-user")
                        .issuedAt(expiration.minusSeconds(3600)).expiresAt(expiration).claim("role", role).build())).getTokenValue();
    }

    @Test
    void missingInvalidExpiredAndTamperedTokensReturn401() throws Exception {
        mvc.perform(get("/users/me")).andExpect(status().isUnauthorized());
        String valid = token("PASSENGER", Instant.now().plusSeconds(3600));
        String[] parts = valid.split("\\.");
        String signature = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        for (String token : new String[]{"invalid-token", token("PASSENGER", Instant.now().minusSeconds(120)),
                parts[0] + "." + parts[1] + "." + signature}) {
            mvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void unsupportedRoleReturns403AndDisabledUserCannotUseExistingToken() throws Exception {
        mvc.perform(get("/users/me").header("Authorization", "Bearer " + token("OTHER", Instant.now().plusSeconds(3600))))
                .andExpect(status().isForbidden());
        String issued = token("PASSENGER", Instant.now().plusSeconds(3600));
        user.setStatus(User.UserStatus.DISABLED);
        mvc.perform(get("/users/me").header("Authorization", "Bearer " + issued)).andExpect(status().isUnauthorized());
    }

    @Test
    void loginValidationAndShortDemoPasswordWork() throws Exception {
        for (String body : new String[]{"{}", "{\"identifier\":\"invalid\",\"password\":\"test-password\"}",
                "{\"identifier\":\"+84912345678\",\"password\":\"test-password\"}",
                "{\"identifier\":\"091234567\",\"password\":\"test-password\"}",
                "{\"identifier\":\"\",\"password\":\"test-password\"}"}) {
            mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        String shortPassword = "abcdef";
        User demo = new User("Demo", "demo@example.com", "0900000001",
                BCrypt.hashpw(shortPassword, BCrypt.gensalt(10)), User.Role.DRIVER);
        ReflectionTestUtils.setField(demo, "id", "demo-driver");
        ReflectionTestUtils.setField(demo, "createdAt", LocalDateTime.now());
        when(repository.findByEmail("demo@example.com")).thenReturn(Optional.of(demo));
        String body = login("demo@example.com", shortPassword);
        String token = JsonMapper.builder().build().readTree(body).get("accessToken").asText();
        assertEquals("DRIVER", decoder.decode(token).getClaimAsString("role"));
    }
}
