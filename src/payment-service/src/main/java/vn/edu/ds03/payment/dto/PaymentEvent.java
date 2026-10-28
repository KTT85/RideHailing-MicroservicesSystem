package vn.edu.ds03.payment.dto;

import vn.edu.ds03.payment.exception.InvalidCommand;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentEvent(String eventId, String eventType, String correlationId,
                           Instant timestamp, Payload payload) {
    public record Payload(String tripId, String passengerId, String method,
                          BigDecimal amount, String currency, String idempotencyKey) { }

    public void validate() {
        uuid(eventId); uuid(correlationId);
        if (timestamp == null || payload == null) throw new InvalidCommand("Missing envelope fields");
        uuid(payload.tripId()); uuid(payload.passengerId());
        if (!"VND".equals(payload.currency()) || payload.amount() == null
                || payload.amount().signum() <= 0 || payload.amount().scale() > 2
                || payload.amount().precision() - payload.amount().scale() > 17)
            throw new InvalidCommand("Invalid amount or currency");
        if (!"CASH".equals(payload.method()) && !"TRANSFER".equals(payload.method()))
            throw new InvalidCommand("Invalid payment method");
        String operation = operationType();
        if (operation != null && !(payload.tripId() + ":" + operation).equals(payload.idempotencyKey()))
            throw new InvalidCommand("Expected stable key tripId:operationType");
        if (("PAY".equals(operation) || "REFUND".equals(operation)) && !"TRANSFER".equals(payload.method()))
            throw new InvalidCommand("PAY/REFUND require TRANSFER");
        if ("CONFIRM_CASH".equals(operation) && !"CASH".equals(payload.method()))
            throw new InvalidCommand("Cash confirmation requires CASH");
    }

    public String operationType() {
        return switch (eventType == null ? "" : eventType) {
            case "PaymentRequested" -> "PAY";
            case "PaymentRefundRequested" -> "REFUND";
            case "CashPaymentConfirmationRequested" -> "CONFIRM_CASH";
            case "TripCompleted" -> null;
            default -> throw new InvalidCommand("Unsupported event type");
        };
    }

    public static void uuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) { throw new InvalidCommand("Expected canonical UUID"); }
    }
}
