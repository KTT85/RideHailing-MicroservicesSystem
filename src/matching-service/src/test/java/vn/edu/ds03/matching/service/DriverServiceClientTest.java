package vn.edu.ds03.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import vn.edu.ds03.matching.config.MatchingProperties;
import vn.edu.ds03.matching.dto.DriverCandidateDto;
import vn.edu.ds03.matching.dto.ReservedDriverDto;

class DriverServiceClientTest {

    private static final String TOKEN = "driver-internal-service-token-32-chars";

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private DriverServiceClient buildClient(String internalToken) {
        MatchingProperties props = new MatchingProperties(
                baseUrl,
                internalToken,
                5,
                2000,
                3000,
                "ride.events",
                "matching.delivery",
                "matching.commands",
                "matching.retry",
                "matching.dlq",
                5000,
                3,
                65536);
        return new DriverServiceClient(RestClient.builder().baseUrl(baseUrl).build(), props);
    }

    @Test
    @DisplayName("fetchCandidates sends X-Service-Token and parses ordered candidates")
    void fetchCandidates_validResponse_returnsCandidates() {
        String driverId = UUID.randomUUID().toString();
        AtomicReference<String> capturedToken = new AtomicReference<>();
        AtomicReference<String> capturedQuery = new AtomicReference<>();

        server.createContext("/internal/drivers/candidates", exchange -> {
            capturedToken.set(exchange.getRequestHeaders().getFirst("X-Service-Token"));
            capturedQuery.set(exchange.getRequestURI().getQuery());
            byte[] response = """
                    [
                      {
                        "driverId": "%s",
                        "latitude": 10.7768890,
                        "longitude": 106.7008060,
                        "distanceKm": 0.42,
                        "vehicleType": "CAR",
                        "licensePlate": "51A-123.45"
                      }
                    ]
                    """.formatted(driverId).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        DriverServiceClient client = buildClient(TOKEN);
        List<DriverCandidateDto> candidates = client.fetchCandidates(
                new BigDecimal("10.7760000"), new BigDecimal("106.7000000"), "CAR", 5);

        assertThat(capturedToken.get()).isEqualTo(TOKEN);
        assertThat(capturedQuery.get())
                .contains("pickupLatitude=10.7760000")
                .contains("pickupLongitude=106.7000000")
                .contains("vehicleType=CAR")
                .contains("limit=5");
        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().driverId()).isEqualTo(driverId);
        assertThat(candidates.getFirst().licensePlate()).isEqualTo("51A-123.45");
    }

    @Test
    @DisplayName("reserveDriver returns ReservedDriverDto on 200 and Optional.empty on 409 or 404")
    void reserveDriver_200And409And404_behavesAsExpected() {
        String driverId = UUID.randomUUID().toString();
        String tripId = UUID.randomUUID().toString();
        AtomicInteger callNumber = new AtomicInteger();

        server.createContext("/internal/drivers/" + driverId + "/reserve", exchange -> {
            int n = callNumber.incrementAndGet();
            if (n == 1) {
                byte[] ok = """
                        {
                          "driverId": "%s",
                          "status": "RESERVED",
                          "currentTripId": "%s",
                          "vehicle": {
                            "vehicleId": "%s",
                            "licensePlate": "59X1-999.99",
                            "vehicleType": "BIKE"
                          }
                        }
                        """.formatted(driverId, tripId, UUID.randomUUID())
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, ok.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(ok);
                }
                return;
            }
            int status = n == 2 ? 409 : 404;
            byte[] conflict = "{\"status\":%d}".formatted(status).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(status, conflict.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(conflict);
            }
        });

        DriverServiceClient client = buildClient(TOKEN);

        Optional<ReservedDriverDto> first = client.tryReserveDriver(driverId, tripId);
        assertThat(first).isPresent();
        assertThat(first.get().driverId()).isEqualTo(driverId);
        assertThat(first.get().vehicle().licensePlate()).isEqualTo("59X1-999.99");

        assertThat(client.tryReserveDriver(driverId, tripId)).isEmpty();
        assertThat(client.tryReserveDriver(driverId, tripId)).isEmpty();
    }

    @Test
    @DisplayName("missing internal token or 503 upstream failure throws IllegalStateException")
    void client_missingTokenOrUpstream5xx_throwsIllegalState() {
        String driverId = UUID.randomUUID().toString();
        String tripId = UUID.randomUUID().toString();

        DriverServiceClient unconfigured = buildClient("");
        assertThatThrownBy(() -> unconfigured.tryReserveDriver(driverId, tripId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRIVER_INTERNAL_TOKEN");

        server.createContext("/internal/drivers/" + driverId + "/reserve", exchange -> {
            byte[] err = "{\"detail\":\"Service unavailable\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, err.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(err);
            }
        });

        DriverServiceClient client = buildClient(TOKEN);
        assertThatThrownBy(() -> client.tryReserveDriver(driverId, tripId))
                .isInstanceOf(IllegalStateException.class);
    }
}
