package vn.edu.ds03.trip.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class PaymentContract {
    private PaymentContract() {}

    public enum Method { CASH, TRANSFER }
    public enum Command {
        PaymentRequested(Method.TRANSFER, "PAY"),
        PaymentRefundRequested(Method.TRANSFER, "REFUND"),
        CashPaymentConfirmationRequested(Method.CASH, "CONFIRM_CASH"),
        TripCompleted(null, null);

        private final Method method;
        private final String operation;
        Command(Method method, String operation) {
            this.method = method;
            this.operation = operation;
        }
    }

    public static final Set<String> RESULTS = Set.of("PaymentSucceeded", "PaymentFailed",
            "PaymentRefunded", "PaymentRefundFailed", "PaymentReviewRequired");

    public record Terms(UUID tripId, UUID passengerId, Method method, BigDecimal amount) {
        public Terms {
            Objects.requireNonNull(tripId);
            Objects.requireNonNull(passengerId);
            Objects.requireNonNull(method);
            Objects.requireNonNull(amount);
            if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2)
                throw new IllegalArgumentException("Expected positive VND amount with at most two decimal places");
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
            if (amount.precision() > 15) throw new IllegalArgumentException("Amount exceeds DECIMAL(15,2)");
        }
    }

    public record Envelope(UUID eventId, String eventType, UUID correlationId, String timestamp,
                           Map<String, Object> payload) {}

    public static Envelope command(Command type, Terms terms, UUID correlationId) {
        Objects.requireNonNull(correlationId);
        if (type.method != null && type.method != terms.method())
            throw new IllegalArgumentException("Payment method incompatible with " + type);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("tripId", terms.tripId());
        payload.put("passengerId", terms.passengerId());
        payload.put("method", terms.method());
        payload.put("amount", terms.amount());
        payload.put("currency", "VND");
        if (type.operation != null) payload.put("idempotencyKey", terms.tripId() + ":" + type.operation);
        return new Envelope(UUID.randomUUID(), type.name(), correlationId, Instant.now().toString(), payload);
    }
}
