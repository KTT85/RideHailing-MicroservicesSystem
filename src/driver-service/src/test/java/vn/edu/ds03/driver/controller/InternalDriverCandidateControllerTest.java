package vn.edu.ds03.driver.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.List;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
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
                        + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
class InternalDriverCandidateControllerTest {

    private static final String ISSUER = "ds03-user-service";
    private static final String INTERNAL_TOKEN = "driver-internal-service-token-32-chars";
    private static final byte[] TEST_KEY_BYTES =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final String VALID_QUERY =
            "/internal/drivers/candidates?pickupLatitude=10.7768890&pickupLongitude=106.7008060&vehicleType=BIKE&limit=2";

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final JwtEncoder JWT_ENCODER = NimbusJwtEncoder
            .withSecretKey(new SecretKeySpec(TEST_KEY_BYTES, "HmacSHA256"))
            .build();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> Base64.getEncoder().encodeToString(TEST_KEY_BYTES));
        registry.add("app.jwt.issuer", () -> ISSUER);
        registry.add("app.internal-token", () -> INTERNAL_TOKEN);
        registry.add("MYSQL_PASSWORD", () -> "unused");
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

    @Test
    @DisplayName("GET /internal/drivers/candidates returns nearest AVAILABLE candidates when X-Service-Token is valid")
    void getAvailableCandidates_validServiceToken_returnsSortedCandidates() throws Exception {
        Driver farDriver = buildAvailableDriver(
                "driver-far", "user-far", "veh-far", "51A-999.02", VehicleType.BIKE, "10.8230989", "106.6296638");
        Driver nearDriver = buildAvailableDriver(
                "driver-near", "user-near", "veh-near", "51A-999.01", VehicleType.BIKE, "10.7770000", "106.7010000");
        when(driverRepository.findAvailableCandidatesByVehicleType(VehicleType.BIKE))
                .thenReturn(List.of(farDriver, nearDriver));

        HttpResponse<String> response = sendInternalRequest("GET", VALID_QUERY, INTERNAL_TOKEN, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        JsonNode array = JSON_MAPPER.readTree(response.body());
        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isEqualTo(2);
        assertThat(array.get(0).get("driverId").asString()).isEqualTo("driver-near");
        assertThat(array.get(0).get("licensePlate").asString()).isEqualTo("51A-999.01");
        assertThat(array.get(0).get("vehicleType").asString()).isEqualTo("BIKE");
        assertThat(array.get(1).get("driverId").asString()).isEqualTo("driver-far");
        assertThat(array.get(0).get("distanceKm").asDouble())
                .isLessThan(array.get(1).get("distanceKm").asDouble());

        verify(driverRepository).findAvailableCandidatesByVehicleType(VehicleType.BIKE);
        verifyNoMoreInteractions(driverRepository);
    }

    @Test
    @DisplayName("GET /internal/drivers/candidates rejects missing/wrong X-Service-Token and rejects external DRIVER JWT")
    void getAvailableCandidates_missingWrongOrJwtOnly_returns401WithoutCallingRepository() throws Exception {
        assertThat(sendInternalRequest("GET", VALID_QUERY, null, null).statusCode()).isEqualTo(401);
        assertThat(sendInternalRequest("GET", VALID_QUERY, "", null).statusCode()).isEqualTo(401);
        assertThat(sendInternalRequest("GET", VALID_QUERY, "wrong-service-token-value-32-chars", null).statusCode())
                .isEqualTo(401);

        Instant now = Instant.now();
        String driverJwt = JWT_ENCODER.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                        .issuer(ISSUER)
                        .subject("550e8400-e29b-41d4-a716-446655440000")
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(3600))
                        .claim("role", "DRIVER")
                        .build())).getTokenValue();

        assertThat(sendInternalRequest("GET", VALID_QUERY, null, "Bearer " + driverJwt).statusCode())
                .isEqualTo(401);
        verifyNoInteractions(driverRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/internal/drivers/candidates?pickupLongitude=106.7&vehicleType=BIKE",
            "/internal/drivers/candidates?pickupLatitude=10.7&vehicleType=BIKE",
            "/internal/drivers/candidates?pickupLatitude=10.7&pickupLongitude=106.7",
            "/internal/drivers/candidates?pickupLatitude=abc&pickupLongitude=106.7&vehicleType=BIKE",
            "/internal/drivers/candidates?pickupLatitude=10.7&pickupLongitude=106.7&vehicleType=TRUCK",
            "/internal/drivers/candidates?pickupLatitude=-91&pickupLongitude=106.7&vehicleType=BIKE",
            "/internal/drivers/candidates?pickupLatitude=10.7&pickupLongitude=181&vehicleType=BIKE",
            "/internal/drivers/candidates?pickupLatitude=1E-500000000&pickupLongitude=106.7&vehicleType=BIKE",
            "/internal/drivers/candidates?pickupLatitude=10.7&pickupLongitude=106.7&vehicleType=BIKE&limit=0",
            "/internal/drivers/candidates?pickupLatitude=10.7&pickupLongitude=106.7&vehicleType=BIKE&limit=21",
            "/internal/drivers/candidates?pickupLatitude=10.7&pickupLongitude=106.7&vehicleType=BIKE&limit=abc"
    })
    @DisplayName("GET /internal/drivers/candidates returns 400 ProblemDetail without leaking class names on invalid params")
    void getAvailableCandidates_invalidQueryParams_returns400ProblemDetail(String invalidPath) throws Exception {
        HttpResponse<String> response = sendInternalRequest("GET", invalidPath, INTERNAL_TOKEN, null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body())
                .isNotBlank()
                .doesNotContain("vn.edu.ds03")
                .doesNotContain("VehicleType")
                .doesNotContain("BigDecimal");
        JsonNode problem = JSON_MAPPER.readTree(response.body());
        assertThat(problem.get("status").asInt()).isEqualTo(400);
        verifyNoInteractions(driverRepository);
    }

    @ParameterizedTest
    @MethodSource("disallowedInternalRoutes")
    @DisplayName("Disallowed internal methods or paths enforce 401 when unauthenticated and 403 when authenticated")
    void disallowedInternalRoutes_enforceDenyAll(
            String method, String path, boolean withServiceToken, int expectedStatus) throws Exception {
        String serviceToken = withServiceToken ? INTERNAL_TOKEN : null;

        HttpResponse<String> response = sendInternalRequest(method, path, serviceToken, null);

        assertThat(response.statusCode()).isEqualTo(expectedStatus);
    }

    static Stream<Arguments> disallowedInternalRoutes() {
        return Stream.of(
                Arguments.of("POST", "/internal/drivers/candidates", false, 401),
                Arguments.of("DELETE", "/internal/drivers/candidates", false, 401),
                Arguments.of("GET", "/internal/drivers/unknown", false, 401),
                Arguments.of("POST", "/internal/drivers/candidates", true, 403),
                Arguments.of("DELETE", "/internal/drivers/candidates", true, 403),
                Arguments.of("GET", "/internal/drivers/unknown", true, 403),
                Arguments.of("GET", "/internal/drivers/driver-1/reserve", false, 401),
                Arguments.of("GET", "/internal/drivers/driver-1/reserve", true, 403),
                Arguments.of("DELETE", "/internal/drivers/driver-1/release", true, 403));
    }

    @Test
    @DisplayName("POST /internal/drivers/{id}/reserve|start|release enforce X-Service-Token, validation, and lifecycle")
    void reservationLifecycleEndpoints_validInvalidAndUnauthorizedRequests_behaveAsExpected() throws Exception {
        String driverId = "aaaaaaaa-1111-2222-3333-444444444444";
        String tripId = "bbbbbbbb-1111-2222-3333-444444444444";
        String validBody = "{\"tripId\":\"" + tripId + "\"}";

        assertThat(sendInternalJson("POST", "/internal/drivers/" + driverId + "/reserve", null, validBody).statusCode())
                .isEqualTo(401);
        assertThat(sendInternalJson("POST", "/internal/drivers/" + driverId + "/start", null, validBody).statusCode())
                .isEqualTo(401);
        assertThat(sendInternalJson("POST", "/internal/drivers/" + driverId + "/release", null, validBody).statusCode())
                .isEqualTo(401);

        for (String invalidBody : new String[]{
                "{}",
                "{\"tripId\":\"\"}",
                "{\"tripId\":\"   \"}",
                "{\"tripId\":\"" + "x".repeat(37) + "\"}",
                "{\"tripId\":\"bad/trip\"}"
        }) {
            HttpResponse<String> badReq = sendInternalJson(
                    "POST", "/internal/drivers/" + driverId + "/reserve", INTERNAL_TOKEN, invalidBody);
            assertThat(badReq.statusCode()).isEqualTo(400);
        }
        verifyNoInteractions(driverRepository);

        Driver reservedDriver = buildAvailableDriver(
                driverId, "user-1", "veh-1", "51A-777.01", VehicleType.BIKE, "10.7768890", "106.7008060");
        ReflectionTestUtils.setField(reservedDriver, "status", DriverStatus.RESERVED);
        ReflectionTestUtils.setField(reservedDriver, "currentTripId", tripId);
        when(driverRepository.reserveAvailableDriver(driverId, tripId)).thenReturn(1);
        when(driverRepository.findWithVehicleById(driverId))
                .thenReturn(java.util.Optional.of(reservedDriver));

        HttpResponse<String> reserveResp = sendInternalJson(
                "POST", "/internal/drivers/" + driverId + "/reserve", INTERNAL_TOKEN, validBody);
        assertThat(reserveResp.statusCode()).isEqualTo(200);
        assertThat(JSON_MAPPER.readTree(reserveResp.body()).get("status").asString()).isEqualTo("RESERVED");

        Driver busyDriver = buildAvailableDriver(
                driverId, "user-1", "veh-1", "51A-777.01", VehicleType.BIKE, "10.7768890", "106.7008060");
        ReflectionTestUtils.setField(busyDriver, "status", DriverStatus.BUSY);
        ReflectionTestUtils.setField(busyDriver, "currentTripId", tripId);
        when(driverRepository.startReservedTrip(driverId, tripId)).thenReturn(1);
        when(driverRepository.findWithVehicleById(driverId))
                .thenReturn(java.util.Optional.of(busyDriver));

        HttpResponse<String> startResp = sendInternalJson(
                "POST", "/internal/drivers/" + driverId + "/start", INTERNAL_TOKEN, validBody);
        assertThat(startResp.statusCode()).isEqualTo(200);
        assertThat(JSON_MAPPER.readTree(startResp.body()).get("status").asString()).isEqualTo("BUSY");

        Driver releasedDriver = buildAvailableDriver(
                driverId, "user-1", "veh-1", "51A-777.01", VehicleType.BIKE, "10.7768890", "106.7008060");
        when(driverRepository.releaseDriverFromTrip(driverId, tripId)).thenReturn(1);
        when(driverRepository.findWithVehicleById(driverId))
                .thenReturn(java.util.Optional.of(releasedDriver));

        HttpResponse<String> releaseResp = sendInternalJson(
                "POST", "/internal/drivers/" + driverId + "/release", INTERNAL_TOKEN, validBody);
        assertThat(releaseResp.statusCode()).isEqualTo(200);
        assertThat(JSON_MAPPER.readTree(releaseResp.body()).get("status").asString()).isEqualTo("AVAILABLE");
    }

    private HttpResponse<String> sendInternalJson(
            String method, String path, String serviceToken, String jsonBody) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        if (serviceToken != null) {
            builder.header("X-Service-Token", serviceToken);
        }
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendInternalRequest(
            String method, String path, String serviceToken, String authorizationHeader) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (serviceToken != null) {
            builder.header("X-Service-Token", serviceToken);
        }
        if (authorizationHeader != null) {
            builder.header("Authorization", authorizationHeader);
        }
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Driver buildAvailableDriver(
            String driverId,
            String userId,
            String vehicleId,
            String plate,
            VehicleType type,
            String lat,
            String lon) {
        Driver driver = new Driver(userId);
        Vehicle vehicle = new Vehicle(plate, type);
        driver.attachVehicle(vehicle);
        driver.updateLocation(new BigDecimal(lat), new BigDecimal(lon));
        driver.updateAvailability(true);
        ReflectionTestUtils.setField(driver, "id", driverId);
        ReflectionTestUtils.setField(vehicle, "id", vehicleId);
        return driver;
    }
}
