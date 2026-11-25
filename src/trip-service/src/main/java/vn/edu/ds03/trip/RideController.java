package vn.edu.ds03.trip;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
class RideController {
    private final RideWorkflow workflow;
    private final TripStore trips;
    private final ObjectMapper json;
    private final String userUrl, driverUrl, failpointToken;
    private final boolean failpoints;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    RideController(RideWorkflow workflow, TripStore trips, ObjectMapper json,
            @Value("${USER_SERVICE_URL:http://localhost:8081}") String userUrl,
            @Value("${DRIVER_SERVICE_URL:http://localhost:8082}") String driverUrl,
            @Value("${DS03_ENABLE_DEMO_FAILPOINTS:false}") boolean enabled,
            @Value("${DS03_DEMO_FAILPOINT_TOKEN:}") String failpointToken) {
        this.workflow = workflow;
        this.trips = trips;
        this.json = json;
        this.userUrl = userUrl;
        this.driverUrl = driverUrl;
        this.failpointToken = failpointToken;
        this.failpoints = enabled && failpointToken.length() >= 32;
    }

    @GetMapping({"/actuator/health", "/api/rides/health"})
    ResponseEntity<?> health() {
        try {
            trips.count();
            return ResponseEntity.ok(Map.of("status", "UP", "rideWorkflow", true, "demoFailpoints", failpoints));
        } catch (RuntimeException e) {
            return ResponseEntity.status(503).body(Map.of("status", "DOWN", "rideWorkflow", false));
        }
    }

    @PostMapping({"/api/rides", "/trips"})
    ResponseEntity<?> create(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "Idempotency-Key", defaultValue = "") String key,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlation,
            @RequestHeader(value = "X-Demo-Failpoint", defaultValue = "") String fault,
            @RequestHeader(value = "X-Demo-Failpoint-Token", defaultValue = "") String token,
            @RequestBody JsonNode body) {
        var actor = authenticate(auth);
        if (!actor.passenger()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "PASSENGER_REQUIRED");
        key = key.trim();
        if (key.isEmpty() || key.length() > 128) throw new IllegalArgumentException("Invalid Idempotency-Key");
        if (!fault.isEmpty() && (!failpoints || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), failpointToken.getBytes(StandardCharsets.UTF_8)))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DEMO_FAILPOINT_DISABLED");
        }
        if (!Set.of("", "F01", "F02").contains(fault)) throw new IllegalArgumentException("Invalid demo failpoint");
        if (!body.isObject() || body.size() != 4) throw new IllegalArgumentException("Invalid ride request");
        var pickup = location(body.path("pickup"));
        var dropoff = location(body.path("dropoff"));
        String vehicle = body.path("vehicleType").asText();
        String payment = body.path("paymentMethod").asText();
        if ((pickup.lat() == dropoff.lat() && pickup.lng() == dropoff.lng()) ||
                !Set.of("BIKE", "CAR").contains(vehicle) || !Set.of("CASH", "TRANSFER").contains(payment)) {
            throw new IllegalArgumentException("Invalid ride request");
        }
        var result = workflow.create(actor, key, correlation == null ? UUID.randomUUID().toString() : RideWorkflow.canonicalId(correlation),
                new RideWorkflow.Input(pickup, dropoff, vehicle, payment), fault.isEmpty() ? null : fault);
        return ResponseEntity.status(result.created() ? 201 : 200).body(Map.of("data", result.data()));
    }

    @GetMapping({"/api/rides/{id}", "/trips/{id}"})
    Map<String, Ride> read(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String auth) {
        return Map.of("data", workflow.read(RideWorkflow.canonicalId(id), authenticate(auth)));
    }

    @PostMapping({"/api/rides/{id}/{action}", "/trips/{id}/{action}"})
    Map<String, Ride> act(@PathVariable String id, @PathVariable String action,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return Map.of("data", workflow.act(RideWorkflow.canonicalId(id), authenticate(auth), action));
    }

    private Ride.Location location(JsonNode value) {
        var lat = value.path("lat");
        var lng = value.path("lng");
        var addressNode = value.path("address");
        String address = addressNode.isMissingNode() ? null : addressNode.asText().trim();
        if (!lat.isNumber() || !lng.isNumber() || !Double.isFinite(lat.asDouble()) || !Double.isFinite(lng.asDouble())
                || Math.abs(lat.asDouble()) > 90 || Math.abs(lng.asDouble()) > 180
                || address != null && (!addressNode.isString() || address.length() > 512)) {
            throw new IllegalArgumentException("Invalid location");
        }
        return new Ride.Location(lat.asDouble(), lng.asDouble(), address);
    }

    private RideWorkflow.Actor authenticate(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");
        JsonNode account = profile(userUrl + "/users/me", auth);
        String userId = RideWorkflow.canonicalId(account.path("id").asText());
        String role = account.path("role").asText();
        if ("PASSENGER".equals(role)) return new RideWorkflow.Actor(role, userId);
        if (!"DRIVER".equals(role)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "RIDE_ROLE_REQUIRED");
        JsonNode driver = profile(driverUrl + "/drivers/me", auth);
        if (!userId.equals(driver.path("userId").asText())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DRIVER_PROFILE_REQUIRED");
        }
        return new RideWorkflow.Actor(role, RideWorkflow.canonicalId(driver.path("driverId").asText()));
    }

    private JsonNode profile(String url, String auth) {
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(2500))
                    .header("Authorization", auth).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (Set.of(401, 403, 404).contains(status)) {
                throw new ResponseStatusException(HttpStatus.valueOf(status), "AUTH_REQUIRED");
            }
            if (status == 200) return json.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.io.IOException e) {
            // The identity service could not be reached.
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_UNAVAILABLE");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION_ERROR", "message", error.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> rejected(ResponseStatusException error) {
        String reason = error.getReason();
        return ResponseEntity.status(error.getStatusCode())
                .body(Map.of("code", reason.split(":")[0], "message", reason));
    }
}
