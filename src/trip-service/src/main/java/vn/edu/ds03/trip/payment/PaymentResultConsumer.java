package vn.edu.ds03.trip.payment;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class PaymentResultConsumer {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TripPaymentGateway gateway;

    PaymentResultConsumer(JdbcTemplate db, ObjectMapper json, TripPaymentGateway gateway) {
        this.db = db;
        this.json = json;
        this.gateway = gateway;
    }

    @RabbitListener(queues = PaymentMessaging.QUEUE)
    @Transactional
    public void receive(Message message) {
        try {
            var event = json.readTree(message.getBody());
            var id = uuid(event, "eventId");
            String type = text(event, "eventType");
            if (!PaymentContract.RESULTS.contains(type) ||
                    !type.equals(message.getMessageProperties().getReceivedRoutingKey()))
                throw new IllegalArgumentException("Unexpected payment result routing key");
            uuid(event, "correlationId");
            String timestamp = text(event, "timestamp");
            if (!timestamp.endsWith("Z") && !timestamp.endsWith("+00:00"))
                throw new IllegalArgumentException("Expected UTC timestamp");
            Instant.parse(timestamp);
            var p = event.path("payload");
            var trip = uuid(p, "tripId");
            var method = PaymentContract.Method.valueOf(text(p, "method"));
            if (!"VND".equals(text(p, "currency")) || !p.path("amount").isNumber())
                throw new IllegalArgumentException("Invalid payment amount or currency");
            var terms = new PaymentContract.Terms(trip, uuid(p, "passengerId"), method, p.get("amount").decimalValue());
            for (String field : new String[]{"operationType", "idempotencyKey", "status", "operationStatus"})
                text(p, field);
            uuid(p, "paymentId");
            uuid(p, "operationId");
            var refunded = p.path("refundedAmount");
            if (!refunded.isNumber() || (refunded.decimalValue().signum() != 0 &&
                    refunded.decimalValue().compareTo(terms.amount()) != 0))
                throw new IllegalArgumentException("Expected zero or full refund");
            gateway.requireSameTerms(terms);
            db.update("""
                INSERT INTO trip_payment_results(event_id,trip_id,event_type,body) VALUES (?,?,?,?)
                ON DUPLICATE KEY UPDATE event_id=event_id
                """, id.toString(), trip.toString(), type, event.toString());
        } catch (IllegalArgumentException | DateTimeParseException | JacksonException ex) {
            throw new AmqpRejectAndDontRequeueException("Invalid payment result", ex);
        }
    }

    private static String text(JsonNode node, String key) {
        var value = node.path(key);
        if (!value.isString() || value.stringValue().isBlank())
            throw new IllegalArgumentException("Missing " + key);
        return value.stringValue();
    }

    private static UUID uuid(JsonNode node, String key) {
        String raw = text(node, key);
        var id = UUID.fromString(raw);
        if (!id.toString().equals(raw)) throw new IllegalArgumentException("Invalid UUID: " + key);
        return id;
    }
}
