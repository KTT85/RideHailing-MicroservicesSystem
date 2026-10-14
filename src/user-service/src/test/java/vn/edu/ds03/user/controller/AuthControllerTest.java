package vn.edu.ds03.user.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.ds03.user.dto.UserResponse;
import vn.edu.ds03.user.exception.GlobalExceptionHandler;
import vn.edu.ds03.user.model.User;
import vn.edu.ds03.user.service.UserService;
import vn.edu.ds03.user.service.AuthService;

class AuthControllerTest {
    private final UserService service = mock(UserService.class);
    private MockMvc mvc;
    private LocalValidatorFactoryBean validator;
    private static final String VALID = """
        {"name":"Demo User","email":"user@example.com","phone":"0912345678",
         "password":"test-only-password","role":"PASSENGER"}
        """;

    @BeforeEach
    void setup() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(service, mock(AuthService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).setValidator(validator).build();
    }

    @AfterEach
    void close() { validator.close(); }

    @Test
    void successReturns201WithoutPassword() throws Exception {
        when(service.register(any())).thenReturn(new UserResponse("sample-id", "Demo User",
                "user@example.com", "0912345678", User.Role.PASSENGER, User.UserStatus.ACTIVE,
                OffsetDateTime.parse("2026-10-13T07:00:00Z")));
        String body = mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(VALID)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("sample-id"));
        assertFalse(body.contains("password"));
        verify(service).register(any());
    }

    @Test
    void errorsReturn400Or409WithoutLeakingDetails() throws Exception {
        for (String json : new String[]{"{", VALID.replace("PASSENGER", "UNKNOWN"),
                VALID.replace("PASSENGER", "ADMIN"), VALID.replace("\"PASSENGER\"", "null"),
                VALID.replace("0912345678", "+84912345678")}) {
            String body = mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertFalse(body.contains("test-only-password"));
        }
        verifyNoInteractions(service);
        for (RuntimeException error : new RuntimeException[]{
                new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate email"),
                new DataIntegrityViolationException("private SQL details")}) {
            doThrow(error).when(service).register(any());
            String body = mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(VALID))
                    .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
            assertFalse(body.contains("private SQL details"));
        }
    }
}
