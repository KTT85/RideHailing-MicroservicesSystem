package vn.edu.ds03.driver.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.stream.Stream;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.edu.ds03.driver.DriverServiceApplication;
import vn.edu.ds03.driver.model.Driver;
import vn.edu.ds03.driver.model.DriverStatus;
import vn.edu.ds03.driver.model.Vehicle;
import vn.edu.ds03.driver.model.VehicleType;
import vn.edu.ds03.driver.repository.DriverRepository;

@SpringBootTest(
        classes = DriverServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,"
                        + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
                "app.messaging.enabled=false"
        })
class DriverProfileSecurityControllerTest {

    private static final String ISSUER = "ds03-user-service";
    private static final String MIXED_CASE_DRIVER_USER_ID = "550E8400-E29B-41D4-A716-446655440000";
    private static final String NORMALIZED_DRIVER_USER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final byte[] TEST_KEY_BYTES =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final String VALID_PAYLOAD = """
            {"licensePlate":" 51a-123.45 ","vehicleType":"BIKE"}
            """;

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final JwtEncoder JWT_ENCODER = NimbusJwtEncoder
            .withSecretKey(new SecretKeySpec(TEST_KEY_BYTES, "HmacSHA256"))
            .build();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        String encodedSecret = Base64.getEncoder().encodeToString(TEST_KEY_BYTES);
        registry.add("app.jwt.secret", () -> encodedSecret);
        registry.add("app.jwt.issuer", () -> ISSUER);
        registry.add("MYSQL_PASSWORD", () -> "unused-in-mocked-repo-test");
        registry.add("RABBITMQ_USER", () -> "unused");
        registry.add("RABBITMQ_PASSWORD", () -> "unused");
    }

    @Autowired
    private WebServerApplicationContext webServerContext;

    @MockitoBean
    private DriverRepository driverRepository;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + webServerContext.getWebServer().getPort();
    }

    @ParameterizedTest
    @EnumSource(VehicleType.class)
    @DisplayName("POST /drivers/profile returns 201 Created for valid DRIVER JWT, ignores spoofed fields, and stays stateless")
    void createProfile_validDriverJwt_returns201IgnoresSpoofedFieldsAndStaysStateless(VehicleType vehicleType)
            throws Exception {
        when(driverRepository.existsByUserId(NORMALIZED_DRIVER_USER_ID)).thenReturn(false);
        when(driverRepository.saveAndFlush(any(Driver.class))).thenAnswer(invocation -> {
            Driver saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", "driver-uuid-1");
            ReflectionTestUtils.setField(saved.getVehicle(), "id", "vehicle-uuid-1");
            return saved;
        });

        String token = issueToken(ISSUER, MIXED_CASE_DRIVER_USER_ID, "DRIVER", Instant.now().plusSeconds(3600));
        String bodyWithSpoofedFields = """
                {"userId":"spoofed-user","status":"AVAILABLE","acceptingTrips":true,"licensePlate":"1234567890abcdefghij","vehicleType":"%s"}
                """.formatted(vehicleType.name());

        HttpResponse<String> response = sendJson(
                "POST",
                "/drivers/profile?userId=spoofed-query-user",
                "Bearer " + token,
                bodyWithSpoofedFields);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");

        JsonNode json = JSON_MAPPER.readTree(response.body());
        assertThat(json.get("driverId").asString()).isEqualTo("driver-uuid-1");
        assertThat(json.get("userId").asString()).isEqualTo(NORMALIZED_DRIVER_USER_ID);
        assertThat(json.get("status").asString()).isEqualTo("OFFLINE");
        assertThat(json.get("acceptingTrips").asBoolean()).isFalse();
        assertThat(json.get("currentTripId").isNull()).isTrue();
        assertThat(json.get("latitude").isNull()).isTrue();
        assertThat(json.get("longitude").isNull()).isTrue();
        assertThat(json.get("vehicle").get("vehicleId").asString()).isEqualTo("vehicle-uuid-1");
        assertThat(json.get("vehicle").get("licensePlate").asString()).isEqualTo("1234567890ABCDEFGHIJ");
        assertThat(json.get("vehicle").get("vehicleType").asString()).isEqualTo(vehicleType.name());

        ArgumentCaptor<Driver> driverCaptor = ArgumentCaptor.forClass(Driver.class);
        verify(driverRepository).existsByUserId(NORMALIZED_DRIVER_USER_ID);
        verify(driverRepository).saveAndFlush(driverCaptor.capture());
        verifyNoMoreInteractions(driverRepository);
        assertThat(driverCaptor.getValue().getUserId()).isEqualTo(NORMALIZED_DRIVER_USER_ID);
    }

    @ParameterizedTest
    @MethodSource("unauthorizedHeaders")
    @DisplayName("POST /drivers/profile returns 401 Unauthorized for missing, malformed, or untrusted Authorization headers")
    void createProfile_unauthorizedHeaders_returns401WithoutCallingRepository(String authorizationHeader)
            throws Exception {
        HttpResponse<String> response = sendJson(
                "POST",
                "/drivers/profile",
                authorizationHeader,
                VALID_PAYLOAD);

        assertThat(response.statusCode()).isEqualTo(401);
        verifyNoInteractions(driverRepository);
    }

    static Stream<String> unauthorizedHeaders() {
        Instant now = Instant.now();
        String validToken = issueStaticToken(ISSUER, NORMALIZED_DRIVER_USER_ID, "DRIVER", now, now.plusSeconds(3600));
        String[] segments = validToken.split("\\.");
        String tamperedSig = (segments[2].startsWith("A") ? "B" : "A") + segments[2].substring(1);
        String tamperedSignatureToken = segments[0] + "." + segments[1] + "." + tamperedSig;

        String forgedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"iss\":\"" + ISSUER + "\",\"sub\":\"other-user\",\"exp\":"
                        + now.plusSeconds(3600).getEpochSecond() + ",\"role\":\"DRIVER\"}")
                        .getBytes(StandardCharsets.UTF_8));
        String tamperedPayloadToken = segments[0] + "." + forgedPayload + "." + segments[2];

        String algNoneHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String algNoneToken = algNoneHeader + "." + segments[1] + ".";

        SecretKey otherKey = new SecretKeySpec(
                "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.US_ASCII), "HmacSHA256");
        String wrongSecretToken = NimbusJwtEncoder.withSecretKey(otherKey).build()
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(),
                        JwtClaimsSet.builder()
                                .issuer(ISSUER)
                                .subject(NORMALIZED_DRIVER_USER_ID)
                                .issuedAt(now)
                                .expiresAt(now.plusSeconds(3600))
                                .claim("role", "DRIVER")
                                .build()))
                .getTokenValue();

        String expiredToken = issueStaticToken(
                ISSUER, NORMALIZED_DRIVER_USER_ID, "DRIVER", now.minusSeconds(7200), now.minusSeconds(120));
        String missingExpToken = issueStaticToken(ISSUER, NORMALIZED_DRIVER_USER_ID, "DRIVER", now, null);
        String wrongIssuerToken = issueStaticToken(
                "evil-issuer", NORMALIZED_DRIVER_USER_ID, "DRIVER", now, now.plusSeconds(3600));
        String missingIssuerToken = issueStaticToken(
                null, NORMALIZED_DRIVER_USER_ID, "DRIVER", now, now.plusSeconds(3600));
        String missingSubToken = issueStaticToken(ISSUER, null, "DRIVER", now, now.plusSeconds(3600));
        String blankSubToken = issueStaticToken(ISSUER, "   ", "DRIVER", now, now.plusSeconds(3600));
        String oversizedSubToken = issueStaticToken(ISSUER, "x".repeat(37), "DRIVER", now, now.plusSeconds(3600));

        return Stream.of(
                null,
                "Basic dXNlcjpwYXNz",
                "Bearer ",
                "Bearer not-a-jwt",
                "Bearer " + tamperedSignatureToken,
                "Bearer " + tamperedPayloadToken,
                "Bearer " + algNoneToken,
                "Bearer " + wrongSecretToken,
                "Bearer " + expiredToken,
                "Bearer " + missingExpToken,
                "Bearer " + wrongIssuerToken,
                "Bearer " + missingIssuerToken,
                "Bearer " + missingSubToken,
                "Bearer " + blankSubToken,
                "Bearer " + oversizedSubToken);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PASSENGER", "ADMIN", "driver", "ROLE_DRIVER", ""})
    @DisplayName("POST /drivers/profile returns 403 Forbidden when JWT role claim is not exact DRIVER")
    void createProfile_nonDriverRoleVariants_returns403WithoutCallingRepository(String roleClaim) throws Exception {
        String role = roleClaim.isEmpty() ? null : roleClaim;
        String token = issueToken(ISSUER, MIXED_CASE_DRIVER_USER_ID, role, Instant.now().plusSeconds(3600));

        HttpResponse<String> response = sendJson("POST", "/drivers/profile", "Bearer " + token, VALID_PAYLOAD);

        assertThat(response.statusCode()).isEqualTo(403);
        verifyNoInteractions(driverRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "{}",
            "{",
            "{\"licensePlate\":null,\"vehicleType\":\"BIKE\"}",
            "{\"licensePlate\":\"\",\"vehicleType\":\"BIKE\"}",
            "{\"licensePlate\":\"   \",\"vehicleType\":\"BIKE\"}",
            "{\"licensePlate\":\"123456789012345678901\",\"vehicleType\":\"BIKE\"}",
            "{\"licensePlate\":\"ßßßßßßßßßßßßßßß\",\"vehicleType\":\"BIKE\"}",
            "{\"licensePlate\":\"51A-123\\n45\",\"vehicleType\":\"BIKE\"}",
            "{\"licensePlate\":\"<script>1</script>\",\"vehicleType\":\"BIKE\"}",
            "{\"licensePlate\":\"51A-123.45\"}",
            "{\"licensePlate\":\"51A-123.45\",\"vehicleType\":null}",
            "{\"licensePlate\":\"51A-123.45\",\"vehicleType\":\"TRUCK\"}"
    })
    @DisplayName("POST /drivers/profile returns RFC 7807 400 Bad Request JSON without leaking internal class names")
    void createProfile_invalidRequestBody_returns400ProblemDetailWithoutLeakingInternals(String invalidJson)
            throws Exception {
        String token = issueToken(ISSUER, MIXED_CASE_DRIVER_USER_ID, "DRIVER", Instant.now().plusSeconds(3600));

        HttpResponse<String> response = sendJson("POST", "/drivers/profile", "Bearer " + token, invalidJson);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body())
                .isNotBlank()
                .doesNotContain("vn.edu.ds03")
                .doesNotContain("VehicleType");
        JsonNode problem = JSON_MAPPER.readTree(response.body());
        assertThat(problem.get("status").asInt()).isEqualTo(400);
        assertThat(problem.get("detail").asString()).isNotBlank();
        verifyNoInteractions(driverRepository);
    }

    @Test
    @DisplayName("POST /drivers/profile returns 409 ProblemDetail when driver profile already exists for user")
    void createProfile_existingUserProfile_returns409ProblemDetail() throws Exception {
        String token = issueToken(ISSUER, MIXED_CASE_DRIVER_USER_ID, "DRIVER", Instant.now().plusSeconds(3600));
        when(driverRepository.existsByUserId(NORMALIZED_DRIVER_USER_ID)).thenReturn(true);

        HttpResponse<String> response = sendJson("POST", "/drivers/profile", "Bearer " + token, VALID_PAYLOAD);

        assertThat(response.statusCode()).isEqualTo(409);
        JsonNode problem = JSON_MAPPER.readTree(response.body());
        assertThat(problem.get("status").asInt()).isEqualTo(409);
        assertThat(problem.get("detail").asString()).isEqualTo("Driver profile already exists");
    }

    @Test
    @DisplayName("POST /drivers/profile returns 409 ProblemDetail on duplicate license plate without leaking SQL")
    void createProfile_duplicateLicensePlate_returns409ProblemDetailWithoutLeakingSql() throws Exception {
        String token = issueToken(ISSUER, MIXED_CASE_DRIVER_USER_ID, "DRIVER", Instant.now().plusSeconds(3600));
        when(driverRepository.existsByUserId(NORMALIZED_DRIVER_USER_ID)).thenReturn(false);
        when(driverRepository.saveAndFlush(any(Driver.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry for key uq_vehicles_license_plate"));

        HttpResponse<String> response = sendJson("POST", "/drivers/profile", "Bearer " + token, VALID_PAYLOAD);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body())
                .doesNotContain("uq_vehicles_license_plate")
                .doesNotContain("DataIntegrityViolationException");
        JsonNode problem = JSON_MAPPER.readTree(response.body());
        assertThat(problem.get("status").asInt()).isEqualTo(409);
        assertThat(problem.get("detail").asString()).isEqualTo("Driver profile or license plate already exists");
    }

    @Test
    @DisplayName("GET /drivers/me returns 200 OK with current driver profile and ignores spoofed userId query param")
    void getMyProfile_validDriverJwtWithProfile_returns200AndUsesTokenSubject() throws Exception {
        Driver driver = new Driver(NORMALIZED_DRIVER_USER_ID);
        Vehicle vehicle = new Vehicle("51h-555.66", VehicleType.CAR);
        driver.attachVehicle(vehicle);
        ReflectionTestUtils.setField(driver, "id", "driver-me-1");
        ReflectionTestUtils.setField(vehicle, "id", "vehicle-me-1");
        ReflectionTestUtils.setField(driver, "latitude", new BigDecimal("10.7768890"));
        ReflectionTestUtils.setField(driver, "longitude", new BigDecimal("106.7008060"));
        when(driverRepository.findWithVehicleByUserId(NORMALIZED_DRIVER_USER_ID))
                .thenReturn(Optional.of(driver));

        String token = issueToken(ISSUER, MIXED_CASE_DRIVER_USER_ID, "DRIVER", Instant.now().plusSeconds(3600));
        HttpResponse<String> response = sendRequest(
                "GET", "/drivers/me?userId=spoofed-other-user", "Bearer " + token);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = JSON_MAPPER.readTree(response.body());
        assertThat(json.get("driverId").asString()).isEqualTo("driver-me-1");
        assertThat(json.get("userId").asString()).isEqualTo(NORMALIZED_DRIVER_USER_ID);
        assertThat(json.get("status").asString()).isEqualTo("OFFLINE");
        assertThat(json.get("acceptingTrips").asBoolean()).isFalse();
        assertThat(json.get("currentTripId").isNull()).isTrue();
        assertThat(json.get("latitude").decimalValue()).isEqualByComparingTo("10.7768890");
        assertThat(json.get("longitude").decimalValue()).isEqualByComparingTo("106.7008060");
        assertThat(json.get("vehicle").get("vehicleId").asString()).isEqualTo("vehicle-me-1");
        assertThat(json.get("vehicle").get("licensePlate").asString()).isEqualTo("51H-555.66");
        assertThat(json.get("vehicle").get("vehicleType").asString()).isEqualTo("CAR");

        verify(driverRepository).findWithVehicleByUserId(NORMALIZED_DRIVER_USER_ID);
        verifyNoMoreInteractions(driverRepository);
    }

    @Test
    @DisplayName("GET /drivers/me returns 404 ProblemDetail when driver profile does not exist")
    void getMyProfile_missingProfile_returns404ProblemDetail() throws Exception {
        when(driverRepository.findWithVehicleByUserId(NORMALIZED_DRIVER_USER_ID))
                .thenReturn(Optional.empty());
        String token = issueToken(ISSUER, MIXED_CASE_DRIVER_USER_ID, "DRIVER", Instant.now().plusSeconds(3600));

        HttpResponse<String> response = sendRequest("GET", "/drivers/me", "Bearer " + token);

        assertThat(response.statusCode()).isEqualTo(404);
        JsonNode problem = JSON_MAPPER.readTree(response.body());
        assertThat(problem.get("status").asInt()).isEqualTo(404);
        assertThat(problem.get("detail").asString()).isEqualTo("Driver profile not found");
    }

    @Test
    @DisplayName("GET /drivers/me enforces 401 when anonymous and 403 when role is not DRIVER")
    void getMyProfile_anonymousOrNonDriverRole_returns401Or403() throws Exception {
        assertThat(sendRequest("GET", "/drivers/me", null).statusCode()).isEqualTo(401);

        String passengerToken = issueToken(
                ISSUER, MIXED_CASE_DRIVER_USER_ID, "PASSENGER", Instant.now().plusSeconds(3600));
        assertThat(sendRequest("GET", "/drivers/me", "Bearer " + passengerToken).statusCode()).isEqualTo(403);
        verifyNoInteractions(driverRepository);
    }

    @Test
    @DisplayName("PUT /drivers/me/location returns 200 OK with scaled coordinates and ignores spoofed userId")
    void updateMyLocation_validDriverJwtAndCoordinates_returns200WithUpdatedLocation() throws Exception {
        Driver driver = new Driver(NORMALIZED_DRIVER_USER_ID);
        Vehicle vehicle = new Vehicle("51a-123.45", VehicleType.BIKE);
        driver.attachVehicle(vehicle);
        ReflectionTestUtils.setField(driver, "id", "driver-loc-1");
        ReflectionTestUtils.setField(vehicle, "id", "vehicle-loc-1");
        when(driverRepository.findWithVehicleForUpdateByUserId(NORMALIZED_DRIVER_USER_ID))
                .thenReturn(Optional.of(driver));
        when(driverRepository.saveAndFlush(driver)).thenReturn(driver);

        String token = issueToken(ISSUER, MIXED_CASE_DRIVER_USER_ID, "DRIVER", Instant.now().plusSeconds(3600));
        String payload = """
                {"userId":"spoofed-user","latitude":10.76262236,"longitude":106.66017244}
                """;

        HttpResponse<String> response = sendJson(
                "PUT", "/drivers/me/location?userId=spoofed-query", "Bearer " + token, payload);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = JSON_MAPPER.readTree(response.body());
        assertThat(json.get("driverId").asString()).isEqualTo("driver-loc-1");
        assertThat(json.get("userId").asString()).isEqualTo(NORMALIZED_DRIVER_USER_ID);
        assertThat(json.get("latitude").decimalValue()).isEqualByComparingTo("10.7626224");
        assertThat(json.get("longitude").decimalValue()).isEqualByComparingTo("106.6601724");
        verify(driverRepository).findWithVehicleForUpdateByUserId(NORMALIZED_DRIVER_USER_ID);
        verify(driverRepository).saveAndFlush(driver);
        verifyNoMoreInteractions(driverRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "{}",
            "{",
            "{\"latitude\":10.76}",
            "{\"longitude\":106.66}",
            "{\"latitude\":null,\"longitude\":106.66}",
            "{\"latitude\":10.76,\"longitude\":null}",
            "{\"latitude\":-90.0001,\"longitude\":106.66}",
            "{\"latitude\":90.0001,\"longitude\":106.66}",
            "{\"latitude\":10.76,\"longitude\":-180.0001}",
            "{\"latitude\":10.76,\"longitude\":180.0001}",
            "{\"latitude\":1E-500000000,\"longitude\":106.66}",
            "{\"latitude\":10.76,\"longitude\":1E-500000000}",
            "{\"latitude\":1E+100000,\"longitude\":106.66}",
            "{\"latitude\":\"abc\",\"longitude\":106.66}"
    })
    @DisplayName("PUT /drivers/me/location returns 400 ProblemDetail when coordinates are missing or out of range")
    void updateMyLocation_invalidPayloads_returns400ProblemDetailWithoutCallingRepository(String invalidJson)
            throws Exception {
        String token = issueToken(ISSUER, MIXED_CASE_DRIVER_USER_ID, "DRIVER", Instant.now().plusSeconds(3600));

        HttpResponse<String> response = sendJson("PUT", "/drivers/me/location", "Bearer " + token, invalidJson);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body())
                .doesNotContain("vn.edu.ds03")
                .doesNotContain("BigDecimal");
        JsonNode problem = JSON_MAPPER.readTree(response.body());
        assertThat(problem.get("status").asInt()).isEqualTo(400);
        assertThat(problem.get("detail").asString()).isNotBlank();
        verifyNoInteractions(driverRepository);
    }

    @Test
    @DisplayName("PUT /drivers/me/location returns 404 ProblemDetail when driver profile does not exist")
    void updateMyLocation_missingProfile_returns404ProblemDetail() throws Exception {
        when(driverRepository.findWithVehicleForUpdateByUserId(NORMALIZED_DRIVER_USER_ID))
                .thenReturn(Optional.empty());
        String token = issueToken(ISSUER, MIXED_CASE_DRIVER_USER_ID, "DRIVER", Instant.now().plusSeconds(3600));

        HttpResponse<String> response = sendJson(
                "PUT",
                "/drivers/me/location",
                "Bearer " + token,
                "{\"latitude\":10.7626220,\"longitude\":106.6601720}");

        assertThat(response.statusCode()).isEqualTo(404);
        JsonNode problem = JSON_MAPPER.readTree(response.body());
        assertThat(problem.get("status").asInt()).isEqualTo(404);
        assertThat(problem.get("detail").asString()).isEqualTo("Driver profile not found");
    }

    @Test
    @DisplayName("PUT /drivers/me/location enforces 401 when anonymous and 403 when role is not DRIVER")
    void updateMyLocation_anonymousOrNonDriverRole_returns401Or403() throws Exception {
        String body = "{\"latitude\":10.7626220,\"longitude\":106.6601720}";
        assertThat(sendJson("PUT", "/drivers/me/location", null, body).statusCode()).isEqualTo(401);

        String passengerToken = issueToken(
                ISSUER, MIXED_CASE_DRIVER_USER_ID, "PASSENGER", Instant.now().plusSeconds(3600));
        assertThat(sendJson("PUT", "/drivers/me/location", "Bearer " + passengerToken, body).statusCode())
                .isEqualTo(403);
        verifyNoInteractions(driverRepository);
    }

    @Test
    @DisplayName("PATCH /drivers/me/availability toggles AVAILABLE and OFFLINE when coordinates exist")
    void updateMyAvailability_validDriverWithCoordinates_togglesAvailableAndOffline() throws Exception {
        Driver driver = new Driver(NORMALIZED_DRIVER_USER_ID);
        Vehicle vehicle = new Vehicle("51a-123.45", VehicleType.BIKE);
        driver.attachVehicle(vehicle);
        driver.updateLocation(new BigDecimal("10.7626220"), new BigDecimal("106.6601720"));
        ReflectionTestUtils.setField(driver, "id", "driver-avail-1");
        ReflectionTestUtils.setField(vehicle, "id", "vehicle-avail-1");
        when(driverRepository.findWithVehicleForUpdateByUserId(NORMALIZED_DRIVER_USER_ID))
                .thenReturn(Optional.of(driver));
        when(driverRepository.saveAndFlush(driver)).thenReturn(driver);

        String token = issueToken(ISSUER, MIXED_CASE_DRIVER_USER_ID, "DRIVER", Instant.now().plusSeconds(3600));

        HttpResponse<String> onlineResp = sendJson(
                "PATCH",
                "/drivers/me/availability?userId=spoofed",
                "Bearer " + token,
                "{\"userId\":\"spoofed\",\"acceptingTrips\":true}");
        assertThat(onlineResp.statusCode()).isEqualTo(200);
        JsonNode onlineJson = JSON_MAPPER.readTree(onlineResp.body());
        assertThat(onlineJson.get("status").asString()).isEqualTo("AVAILABLE");
        assertThat(onlineJson.get("acceptingTrips").asBoolean()).isTrue();

        HttpResponse<String> offlineResp = sendJson(
                "PATCH",
                "/drivers/me/availability",
                "Bearer " + token,
                "{\"acceptingTrips\":false}");
        assertThat(offlineResp.statusCode()).isEqualTo(200);
        JsonNode offlineJson = JSON_MAPPER.readTree(offlineResp.body());
        assertThat(offlineJson.get("status").asString()).isEqualTo("OFFLINE");
        assertThat(offlineJson.get("acceptingTrips").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("PATCH /drivers/me/availability returns 409 ProblemDetail when coordinates are missing or driver is on active trip")
    void updateMyAvailability_missingCoordinatesOrActiveTrip_returns409ProblemDetail() throws Exception {
        String token = issueToken(ISSUER, MIXED_CASE_DRIVER_USER_ID, "DRIVER", Instant.now().plusSeconds(3600));

        Driver noLocationDriver = new Driver(NORMALIZED_DRIVER_USER_ID);
        noLocationDriver.attachVehicle(new Vehicle("51a-123.45", VehicleType.BIKE));
        when(driverRepository.findWithVehicleForUpdateByUserId(NORMALIZED_DRIVER_USER_ID))
                .thenReturn(Optional.of(noLocationDriver));

        HttpResponse<String> noLocResp = sendJson(
                "PATCH", "/drivers/me/availability", "Bearer " + token, "{\"acceptingTrips\":true}");
        assertThat(noLocResp.statusCode()).isEqualTo(409);
        assertThat(JSON_MAPPER.readTree(noLocResp.body()).get("status").asInt()).isEqualTo(409);

        Driver reservedDriver = new Driver(NORMALIZED_DRIVER_USER_ID);
        reservedDriver.attachVehicle(new Vehicle("51a-123.45", VehicleType.BIKE));
        reservedDriver.updateLocation(new BigDecimal("10.7626220"), new BigDecimal("106.6601720"));
        ReflectionTestUtils.setField(reservedDriver, "status", DriverStatus.RESERVED);
        ReflectionTestUtils.setField(reservedDriver, "acceptingTrips", true);
        ReflectionTestUtils.setField(reservedDriver, "currentTripId", "trip-1");
        when(driverRepository.findWithVehicleForUpdateByUserId(NORMALIZED_DRIVER_USER_ID))
                .thenReturn(Optional.of(reservedDriver));

        HttpResponse<String> activeTripResp = sendJson(
                "PATCH", "/drivers/me/availability", "Bearer " + token, "{\"acceptingTrips\":false}");
        assertThat(activeTripResp.statusCode()).isEqualTo(409);
        assertThat(JSON_MAPPER.readTree(activeTripResp.body()).get("status").asInt()).isEqualTo(409);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "{}",
            "{",
            "{\"acceptingTrips\":null}",
            "{\"acceptingTrips\":\"not-a-boolean\"}"
    })
    @DisplayName("PATCH /drivers/me/availability returns 400 ProblemDetail for missing or malformed acceptingTrips")
    void updateMyAvailability_invalidPayloads_returns400ProblemDetailWithoutCallingRepository(String invalidJson)
            throws Exception {
        String token = issueToken(ISSUER, MIXED_CASE_DRIVER_USER_ID, "DRIVER", Instant.now().plusSeconds(3600));

        HttpResponse<String> response = sendJson("PATCH", "/drivers/me/availability", "Bearer " + token, invalidJson);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("vn.edu.ds03");
        assertThat(JSON_MAPPER.readTree(response.body()).get("status").asInt()).isEqualTo(400);
        verifyNoInteractions(driverRepository);
    }

    @Test
    @DisplayName("PATCH /drivers/me/availability returns 404 when profile is missing and enforces 401/403")
    void updateMyAvailability_missingProfileOrUnauthorized_returnsExpectedStatus() throws Exception {
        String body = "{\"acceptingTrips\":true}";
        assertThat(sendJson("PATCH", "/drivers/me/availability", null, body).statusCode()).isEqualTo(401);

        String passengerToken = issueToken(
                ISSUER, MIXED_CASE_DRIVER_USER_ID, "PASSENGER", Instant.now().plusSeconds(3600));
        assertThat(sendJson("PATCH", "/drivers/me/availability", "Bearer " + passengerToken, body).statusCode())
                .isEqualTo(403);
        verifyNoInteractions(driverRepository);

        when(driverRepository.findWithVehicleForUpdateByUserId(NORMALIZED_DRIVER_USER_ID))
                .thenReturn(Optional.empty());
        String driverToken = issueToken(
                ISSUER, MIXED_CASE_DRIVER_USER_ID, "DRIVER", Instant.now().plusSeconds(3600));
        HttpResponse<String> notFoundResp = sendJson(
                "PATCH", "/drivers/me/availability", "Bearer " + driverToken, body);
        assertThat(notFoundResp.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("Real Spring Boot Actuator health and info endpoints are public and hide internal details")
    void actuatorHealthAndInfo_withoutJwt_return200WithoutComponentDetails() throws Exception {
        HttpResponse<String> healthResponse = sendRequest("GET", "/actuator/health", null);
        assertThat(healthResponse.statusCode()).isEqualTo(200);
        JsonNode healthJson = JSON_MAPPER.readTree(healthResponse.body());
        assertThat(healthJson.get("status").asString()).isEqualTo("UP");
        assertThat(healthJson.has("components")).isFalse();

        HttpResponse<String> infoResponse = sendRequest("GET", "/actuator/info", null);
        assertThat(infoResponse.statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @MethodSource("undeclaredRouteCases")
    @DisplayName("Undeclared or non-GET routes enforce 401 when anonymous and 403 when authenticated")
    void undeclaredRoutes_anonymousOrAuthenticated_returnExpectedDenyStatus(
            String method, String path, boolean authenticated, int expectedStatus) throws Exception {
        String authHeader = authenticated
                ? "Bearer " + issueToken(ISSUER, MIXED_CASE_DRIVER_USER_ID, "DRIVER", Instant.now().plusSeconds(3600))
                : null;

        HttpResponse<String> response = sendRequest(method, path, authHeader);

        assertThat(response.statusCode()).isEqualTo(expectedStatus);
    }

    static Stream<Arguments> undeclaredRouteCases() {
        return Stream.of(
                Arguments.of("POST", "/actuator/health", false, 401),
                Arguments.of("GET", "/actuator/env", false, 401),
                Arguments.of("GET", "/drivers/profile", false, 401),
                Arguments.of("DELETE", "/drivers/profile", false, 401),
                Arguments.of("DELETE", "/drivers/me", false, 401),
                Arguments.of("GET", "/drivers/me/location", false, 401),
                Arguments.of("DELETE", "/drivers/me/location", false, 401),
                Arguments.of("GET", "/drivers/me/availability", false, 401),
                Arguments.of("DELETE", "/drivers/me/availability", false, 401),
                Arguments.of("GET", "/actuator/env", true, 403),
                Arguments.of("GET", "/drivers/profile", true, 403),
                Arguments.of("DELETE", "/drivers/profile", true, 403),
                Arguments.of("DELETE", "/drivers/me", true, 403),
                Arguments.of("GET", "/drivers/me/location", true, 403),
                Arguments.of("DELETE", "/drivers/me/location", true, 403),
                Arguments.of("GET", "/drivers/me/availability", true, 403),
                Arguments.of("DELETE", "/drivers/me/availability", true, 403),
                Arguments.of("GET", "/internal/drivers/candidates", false, 401),
                Arguments.of("GET", "/internal/drivers/candidates", true, 401));
    }

    private HttpResponse<String> sendJson(String method, String path, String authHeader, String jsonBody)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendRequest(String method, String path, String authHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String issueToken(String issuer, String subject, String role, Instant expiresAt) {
        return issueStaticToken(issuer, subject, role, expiresAt.minusSeconds(3600), expiresAt);
    }

    private static String issueStaticToken(
            String issuer, String subject, String role, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder();
        if (issuer != null) {
            claims.issuer(issuer);
        }
        if (subject != null) {
            claims.subject(subject);
        }
        if (issuedAt != null) {
            claims.issuedAt(issuedAt);
        }
        if (expiresAt != null) {
            claims.expiresAt(expiresAt);
        }
        if (role != null) {
            claims.claim("role", role);
        }
        return JWT_ENCODER.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                claims.build())).getTokenValue();
    }
}
