package vn.edu.ds03.notification;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class NotificationBroker {
    static final String QUEUE = "notification.commands";
    private static final String DLQ = "notification.dlq";
    static final List<String> TYPES = List.of("TripConfirmed", "TripStarted", "TripCompleted", "TripCancelled",
            "PaymentSucceeded", "PaymentRefunded", "PaymentRefundFailed");

    @Bean
    Declarables notificationTopology() {
        var exchange = new TopicExchange("ride.events", true, false);
        var commands = QueueBuilder.durable(QUEUE).deadLetterExchange("").deadLetterRoutingKey(DLQ).build();
        var items = new ArrayList<Declarable>();
        items.add(exchange);
        items.add(commands);
        items.add(QueueBuilder.durable(DLQ).build());
        for (String type : TYPES) {
            items.add(BindingBuilder.bind(commands).to(exchange).with(type));
        }
        return new Declarables(items);
    }
}

@Component
class NotificationConsumer {
    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;

    NotificationConsumer(ObjectMapper json, JdbcTemplate jdbc) {
        this.json = json;
        this.jdbc = jdbc;
    }

    @RabbitListener(queues = NotificationBroker.QUEUE)
    @Transactional
    public void receive(Message message) {
        if (message.getBody().length > 65536) throw new AmqpRejectAndDontRequeueException("Notification event too large");
        final UUID eventId;
        final UUID tripId;
        final UUID passengerId;
        final UUID driverUserId;
        final UUID correlationId;
        final String type;
        try {
            var event = json.readTree(message.getBody());
            eventId = UUID.fromString(event.path("eventId").asText());
            correlationId = UUID.fromString(event.path("correlationId").asText());
            var payload = event.path("payload");
            tripId = UUID.fromString(payload.path("tripId").asText());
            passengerId = UUID.fromString(payload.path("passengerId").asText());
            type = event.path("eventType").asText();
            var driver = payload.path("driverUserId");
            driverUserId = (type.equals("TripConfirmed") || type.equals("TripCancelled"))
                    && driver.isTextual() ? UUID.fromString(driver.asText()) : null;
            if (!NotificationBroker.TYPES.contains(type)
                    || !type.equals(message.getMessageProperties().getReceivedRoutingKey())) {
                throw new IllegalArgumentException("Unexpected notification event type");
            }
        } catch (RuntimeException ex) {
            throw new AmqpRejectAndDontRequeueException("Invalid notification event", ex);
        }
        save(eventId, tripId, passengerId, correlationId, type);
        if (driverUserId != null) save(eventId, tripId, driverUserId, correlationId, type);
        log.info("service=notification-service event=NotificationStored type={} eventId={} tripId={} correlationId={}",
                type, eventId, tripId, correlationId);
    }

    private void save(UUID eventId, UUID tripId, UUID userId, UUID correlationId, String type) {
        jdbc.update("""
                INSERT INTO ride_notifications(id, event_id, trip_id, user_id, correlation_id, event_type)
                VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE event_id = event_id
                """, UUID.randomUUID().toString(), eventId.toString(), tripId.toString(),
                userId.toString(), correlationId.toString(), type);
    }
}

@RestController
class NotificationQueryController {
    record Receipt(String id, String eventId, String tripId, String userId, String correlationId, String eventType,
                   Instant storedAt, Instant readAt) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserProfile(UUID id) { }

    private final JdbcTemplate jdbc;
    private final RestClient users;

    NotificationQueryController(JdbcTemplate jdbc, @Value("${USER_SERVICE_URL:http://localhost:8081}") String userServiceUrl) {
        this.jdbc = jdbc;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(2000);
        this.users = RestClient.builder().baseUrl(userServiceUrl).requestFactory(factory).build();
    }

    @GetMapping("/notifications/me")
    List<Receipt> mine(@RequestHeader(value = "Authorization", defaultValue = "") String authorization,
                       @RequestParam(required = false) UUID tripId) {
        UUID userId = authenticate(authorization);
        return query(userId, tripId);
    }

    private List<Receipt> query(UUID userId, UUID tripId) {
        String tripFilter = tripId == null ? "" : " AND trip_id = ?";
        Object[] parameters = tripId == null ? new Object[]{userId.toString()}
                : new Object[]{userId.toString(), tripId.toString()};
        return jdbc.query("""
                SELECT id, event_id, trip_id, user_id, correlation_id, event_type, stored_at, read_at
                FROM ride_notifications WHERE user_id = ?
                """ + tripFilter + " ORDER BY stored_at DESC, id LIMIT 100",
                (rs, row) -> {
                    var read = rs.getTimestamp("read_at");
                    return new Receipt(rs.getString("id"), rs.getString("event_id"), rs.getString("trip_id"),
                            rs.getString("user_id"), rs.getString("correlation_id"), rs.getString("event_type"),
                            rs.getTimestamp("stored_at").toInstant(), read == null ? null : read.toInstant());
                }, parameters);
    }

    @PatchMapping("/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markRead(@PathVariable UUID id, @RequestHeader(value = "Authorization", defaultValue = "") String authorization) {
        UUID userId = authenticate(authorization);
        int matched = jdbc.update("""
                UPDATE ride_notifications SET read_at = COALESCE(read_at, CURRENT_TIMESTAMP(6))
                WHERE id = ? AND user_id = ?
                """, id.toString(), userId.toString());
        if (matched == 0) {
            boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM ride_notifications WHERE id = ? AND user_id = ?)",
                    Boolean.class, id.toString(), userId.toString()));
            if (!exists) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private UUID authenticate(String authorization) {
        if (!authorization.startsWith("Bearer ")) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        try {
            var profile = users.get().uri("/users/me").header("Authorization", authorization)
                    .retrieve().body(UserProfile.class);
            if (profile == null || profile.id() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            return profile.id();
        } catch (HttpClientErrorException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Identity service unavailable");
        }
    }
}
