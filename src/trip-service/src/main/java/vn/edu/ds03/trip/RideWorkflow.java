package vn.edu.ds03.trip;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
class RideWorkflow {
    private final TripStore trips;
    private final TripOutbox outbox;
    private final ProcessedTripEvents processed;
    private final ObjectMapper json;

    RideWorkflow(TripStore trips, TripOutbox outbox, ProcessedTripEvents processed, ObjectMapper json) {
        this.trips = trips;
        this.outbox = outbox;
        this.processed = processed;
        this.json = json;
    }

    record Actor(String role, String id) {
        boolean passenger() { return role.equals("PASSENGER"); }
    }
    record Input(Ride.Location pickup, Ride.Location dropoff, String vehicleType, String paymentMethod) {}
    record Created(Ride data, boolean created) {}

    @Transactional
    public Created create(Actor actor, String key, String correlation, Input input, String fault) {
        trips.lockWorkflow();
        String digest = digest(actor.id(), key);
        var existing = trips.findByIdempotencyKey(digest);
        if (existing.isPresent()) {
            Ride ride = existing.get();
            if (!Objects.equals(ride.demoFault, fault) || !ride.pickup.equals(input.pickup())
                    || !ride.dropoff.equals(input.dropoff()) || !ride.vehicleType.equals(input.vehicleType())
                    || !ride.paymentMethod.equals(input.paymentMethod())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
            }
            return new Created(ride, false);
        }
        Ride ride = new Ride();
        ride.id = UUID.randomUUID().toString();
        ride.passengerId = actor.id();
        ride.idempotencyKey = digest;
        ride.correlationId = correlation;
        ride.status = "REQUESTED";
        ride.pickup = input.pickup();
        ride.dropoff = input.dropoff();
        ride.vehicleType = input.vehicleType();
        ride.paymentMethod = input.paymentMethod();
        ride.paymentState = "NOT_REQUESTED";
        ride.fare = fare(input);
        ride.demoFault = fault;
        ride.requestedAt = Instant.now();
        trips.save(ride);
        ObjectNode payload = json.createObjectNode().put("passengerId", ride.passengerId)
                .put("vehicleType", ride.vehicleType).put("pickupLatitude", ride.pickup.lat())
                .put("pickupLongitude", ride.pickup.lng());
        if ("F01".equals(fault)) payload.put("demoFault", fault);
        emit(ride, "TripRequested", payload);
        return new Created(ride, true);
    }

    static double fare(Input input) {
        var from = input.pickup();
        var to = input.dropoff();
        double latitude = Math.toRadians(to.lat() - from.lat());
        double longitude = Math.toRadians(to.lng() - from.lng());
        double a = Math.pow(Math.sin(latitude / 2), 2) + Math.cos(Math.toRadians(from.lat()))
                * Math.cos(Math.toRadians(to.lat())) * Math.pow(Math.sin(longitude / 2), 2);
        double km = 6371 * 2 * Math.asin(Math.sqrt(Math.min(1, a)));
        int rate = "CAR".equals(input.vehicleType()) ? 15_000 : 8_000;
        return Math.max(1L, Math.round(km * rate));
    }

    private String digest(String passenger, String key) {
        try {
            byte[] bytes = json.writeValueAsString(java.util.List.of(passenger, key)).getBytes(StandardCharsets.UTF_8);
            return "ride:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional(readOnly = true)
    public Ride read(String id, Actor actor) {
        return visible(id, actor);
    }

    private Ride visible(String id, Actor actor) {
        return trips.findById(id).filter(ride -> ride.correlationId != null && ride.paymentMethod != null
                && ride.fare != null && actor.id().equals(actor.passenger() ? ride.passengerId : ride.driverId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "RIDE_NOT_FOUND"));
    }

    @Transactional
    public Ride act(String id, Actor actor, String action) {
        trips.lockWorkflow();
        Ride ride = visible(id, actor);
        if (Set.of("pay", "cancel").contains(action) != actor.passenger()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "RIDE_ROLE_REQUIRED");
        }
        switch (action) {
            case "pay" -> {
                require(ride, "TRANSFER".equals(ride.paymentMethod));
                if (state(ride, "PAYMENT_PENDING") || "SUCCESS".equals(ride.paymentState)) break;
                require(ride, state(ride, "DRIVER_ASSIGNED") && "NOT_REQUESTED".equals(ride.paymentState));
                paymentCommand(ride, "PaymentRequested", "PAY");
                ride.status = "PAYMENT_PENDING";
                ride.paymentState = "PENDING";
            }
            case "accept" -> {
                if (state(ride, "ACCEPTED")) break;
                require(ride, state(ride, "MATCHED"));
                ride.status = "ACCEPTED";
                ride.acceptedAt = Instant.now();
            }
            case "reject" -> {
                if ("driver_rejected".equals(ride.cancelReason) && state(ride, "COMPENSATING", "CANCELLED")) break;
                require(ride, state(ride, "MATCHED", "ACCEPTED"));
                cancel(ride, "driver_rejected");
            }
            case "start" -> {
                if (state(ride, "STARTING", "IN_PROGRESS")) break;
                require(ride, state(ride, "ACCEPTED"));
                emit(ride, "DriverStartRequested", json.createObjectNode().put("driverId", ride.driverId));
                ride.status = "STARTING";
            }
            case "complete" -> {
                if (state(ride, "COMPLETED")) break;
                require(ride, state(ride, "IN_PROGRESS"));
                emit(ride, "TripCompleted", payment(ride).put("driverId", ride.driverId));
                ride.status = "COMPLETED";
                ride.completedAt = Instant.now();
                if ("CASH".equals(ride.paymentMethod)) ride.paymentState = "AWAITING_CASH_CONFIRMATION";
            }
            case "confirm-cash", "cash-payment-confirmation" -> {
                require(ride, state(ride, "COMPLETED") && "CASH".equals(ride.paymentMethod));
                if (Set.of("PENDING", "SUCCESS").contains(ride.paymentState)) break;
                require(ride, "AWAITING_CASH_CONFIRMATION".equals(ride.paymentState));
                paymentCommand(ride, "CashPaymentConfirmationRequested", "CONFIRM_CASH");
                ride.paymentState = "PENDING";
            }
            case "cancel" -> {
                if (state(ride, "COMPENSATING", "CANCELLED")) break;
                require(ride, state(ride, "REQUESTED", "DRIVER_ASSIGNED", "PAYMENT_PENDING", "MATCHED", "ACCEPTED"));
                cancel(ride, "passenger_cancelled");
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid operation");
        }
        return ride;
    }

    @Transactional
    public void receive(JsonNode event) {
        String eventId = canonicalId(event.path("eventId").asText());
        var payload = event.path("payload");
        String tripId = canonicalId(payload.path("tripId").asText());
        trips.lockWorkflow();
        if (processed.existsById(eventId)) return;
        Ride ride = trips.findById(tripId).orElse(null);
        if (ride == null || ride.paymentMethod == null || ride.correlationId == null
                || !ride.correlationId.equals(event.path("correlationId").asText())) return;
        String type = event.path("eventType").asText();
        boolean cancelling = state(ride, "COMPENSATING");
        switch (type) {
            case "DriverAssigned" -> assign(ride, payload, cancelling);
            case "NoDriverAvailable" -> {
                if (ride.driverId == null && state(ride, "REQUESTED", "COMPENSATING")) {
                    ride.driverReleased = true;
                    if (ride.cancelReason == null) ride.cancelReason = "no_driver_available";
                    finishCancellation(ride);
                }
            }
            case "DriverStarted" -> {
                if (state(ride, "STARTING") && sameDriver(ride, payload)) {
                    String occurredAt = event.path("occurredAt").asText(event.path("timestamp").asText());
                    ride.startedAt = Instant.parse(occurredAt);
                    ride.status = "IN_PROGRESS";
                    emit(ride, "TripStarted", recipients(ride));
                }
            }
            case "DriverReleased" -> {
                if ((cancelling || state(ride, "COMPLETED")) && sameDriver(ride, payload) && !ride.driverReleased) {
                    ride.driverReleased = true;
                    if (cancelling) advanceCancellation(ride);
                }
            }
            case "PaymentSucceeded", "PaymentFailed", "PaymentReviewRequired", "PaymentRefunded",
                 "PaymentRefundFailed" -> applyPayment(ride, type, payload, cancelling);
            default -> throw new IllegalArgumentException("Unsupported event type");
        }
        processed.save(new ProcessedTripEvent(eventId));
    }

    private void assign(Ride ride, JsonNode payload, boolean cancelling) {
        String driverId = canonicalId(payload.path("driverId").asText());
        if (state(ride, "CANCELLED", "COMPLETED")) {
            release(ride, driverId, "terminal_trip");
        } else if (ride.driverId == null && (state(ride, "REQUESTED") || cancelling)) {
            ride.driverId = driverId;
            if (!payload.path("driverUserId").isMissingNode() && !payload.path("driverUserId").isNull()) {
                ride.driverUserId = canonicalId(payload.path("driverUserId").asText());
            }
            if ("F01".equals(ride.demoFault) && payload.path("reservationReused").asBoolean()) ride.demoFaultAt = Instant.now();
            if (cancelling) {
                release(ride, driverId, ride.cancelReason);
            } else if ("CASH".equals(ride.paymentMethod)) {
                ride.status = "MATCHED";
                emit(ride, "TripConfirmed", recipients(ride));
            } else {
                ride.status = "DRIVER_ASSIGNED";
            }
        }
    }

    private void applyPayment(Ride ride, String type, JsonNode payload, boolean cancelling) {
        String paymentId = canonicalId(payload.path("paymentId").asText());
        if (!ride.passengerId.equals(payload.path("passengerId").asText())
                || !ride.paymentMethod.equals(payload.path("method").asText())
                || ride.fare != payload.path("amount").asDouble()
                || !"VND".equals(payload.path("currency").asText())
                || ride.paymentId != null && !ride.paymentId.equals(paymentId)) {
            throw new IllegalArgumentException("Payment result does not match ride");
        }
        String operation = payload.path("operationType").asText();
        if ("REFUND".equals(operation)) {
            if (!cancelling || !Set.of("REFUND_PENDING", "REFUND_FAILED").contains(ride.paymentState)) return;
            if ("PaymentRefunded".equals(type)) {
                if (payload.path("refundedAmount").asDouble() != ride.fare) throw new IllegalArgumentException("Incorrect refund amount");
                ride.paymentState = "REFUNDED";
                ride.paymentId = paymentId;
                advanceCancellation(ride);
            } else if ("PaymentRefundFailed".equals(type)) {
                ride.paymentState = "REFUND_FAILED";
                ride.paymentId = paymentId;
            }
            return;
        }
        String expected = "TRANSFER".equals(ride.paymentMethod) ? "PAY" : "CONFIRM_CASH";
        if (!expected.equals(operation) || !Set.of("PENDING", "MANUAL_REVIEW").contains(ride.paymentState)) return;
        switch (type) {
            case "PaymentSucceeded" -> {
                ride.paymentId = paymentId;
                ride.paymentState = "SUCCESS";
                if (cancelling) advanceCancellation(ride);
                else if (state(ride, "PAYMENT_PENDING")) {
                    ride.status = "MATCHED";
                    emit(ride, "TripConfirmed", recipients(ride));
                }
            }
            case "PaymentFailed" -> {
                ride.paymentId = paymentId;
                ride.paymentState = "FAILED";
                if (cancelling) advanceCancellation(ride);
                else if (state(ride, "PAYMENT_PENDING")) cancel(ride, "payment_failed");
            }
            case "PaymentReviewRequired" -> {
                ride.paymentId = paymentId;
                ride.paymentState = "MANUAL_REVIEW";
            }
            default -> { }
        }
    }

    private void cancel(Ride ride, String reason) {
        if (ride.driverId != null) release(ride, ride.driverId, reason);
        ride.status = "COMPENSATING";
        ride.cancelReason = reason;
    }

    private void advanceCancellation(Ride ride) {
        if (!ride.driverReleased) return;
        switch (ride.paymentState) {
            case "SUCCESS" -> {
                paymentCommand(ride, "PaymentRefundRequested", "REFUND");
                ride.paymentState = "REFUND_PENDING";
            }
            case "PENDING", "MANUAL_REVIEW", "REFUND_PENDING", "REFUND_FAILED" -> { }
            default -> finishCancellation(ride);
        }
    }

    private void finishCancellation(Ride ride) {
        emit(ride, "TripCancelled", recipients(ride).put("reason", ride.cancelReason));
        ride.status = "CANCELLED";
        ride.cancelledAt = Instant.now();
    }

    private void release(Ride ride, String driverId, String reason) {
        emit(ride, "DriverReleaseRequested", json.createObjectNode().put("driverId", driverId).put("reason", reason));
    }

    private void paymentCommand(Ride ride, String type, String operation) {
        emit(ride, type, payment(ride).put("idempotencyKey", ride.id + ":" + operation));
    }

    private ObjectNode payment(Ride ride) {
        return json.createObjectNode().put("passengerId", ride.passengerId).put("method", ride.paymentMethod)
                .put("amount", ride.fare.longValue()).put("currency", "VND");
    }

    private ObjectNode recipients(Ride ride) {
        return json.createObjectNode().put("passengerId", ride.passengerId)
                .put("driverId", ride.driverId).put("driverUserId", ride.driverUserId);
    }

    private void emit(Ride ride, String type, ObjectNode payload) {
        var event = new TripOutboxEvent();
        event.id = UUID.randomUUID().toString();
        event.aggregateId = ride.id;
        event.correlationId = ride.correlationId;
        event.eventType = type;
        event.createdAt = Instant.now();
        event.payload = payload.put("tripId", ride.id);
        outbox.save(event);
    }

    private static boolean state(Ride ride, String... allowed) {
        return java.util.Arrays.asList(allowed).contains(ride.status);
    }

    private static boolean sameDriver(Ride ride, JsonNode payload) {
        return ride.driverId != null && ride.driverId.equals(payload.path("driverId").asText());
    }

    private static void require(Ride ride, boolean condition) {
        if (!condition) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "RIDE_STATE_CONFLICT: " + ride.status + " (payment " + ride.paymentState + ")");
    }

    static String canonicalId(String value) {
        if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("Invalid UUID");
        return value;
    }
}
