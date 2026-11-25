package vn.edu.ds03.trip;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TripMessagingTest {
    @Test
    void paymentCommandIncludesRequiredTimestamp() {
        var json = JsonMapper.builder().build();
        var publisher = new TripMessaging(null, null, null, null, json);
        var event = new TripOutboxEvent();
        event.id = UUID.randomUUID().toString();
        event.correlationId = UUID.randomUUID().toString();
        event.eventType = "PaymentRequested";
        event.createdAt = Instant.parse("2026-11-25T10:00:00Z");
        event.payload = json.createObjectNode().put("amount", 12000);

        var message = publisher.outboxMessage(event);
        var body = json.readTree(message.getBody());

        assertEquals(event.createdAt.toString(), body.path("timestamp").asText());
        assertEquals(body.path("timestamp"), body.path("occurredAt"));
        assertEquals(event.id, message.getMessageProperties().getMessageId());
    }
}
