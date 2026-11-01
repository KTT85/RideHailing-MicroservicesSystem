package vn.edu.ds03.payment.integration;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import vn.edu.ds03.payment.config.MessagingConfiguration;
import vn.edu.ds03.payment.dto.PaymentEvent;
import vn.edu.ds03.payment.messaging.ConfirmedPublisher;

/** Real RabbitMQ, isolated payment-test vhost; H2 stands in for MySQL only. */
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_RABBIT", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:payment-live;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.flyway.enabled=false", "spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:schema-h2.sql",
    "spring.jpa.hibernate.ddl-auto=none", "spring.rabbitmq.host=127.0.0.1", "spring.rabbitmq.port=5672",
    "spring.rabbitmq.username=guest", "spring.rabbitmq.password=guest", "spring.rabbitmq.virtual-host=payment-test",
    "app.payment.workers-enabled=true", "app.payment.worker-delay-ms=100", "app.payment.retry-seconds=1",
    "app.jwt.secret=MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=",
    "app.internal-token=test-service-token-at-least-32-characters-long"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RabbitLiveIntegrationTest {
    @Autowired RabbitTemplate rabbit;
    @Autowired ConfirmedPublisher publisher;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitListenerEndpointRegistry listeners;
    RabbitAdmin admin;
    final String results = "payment.live.results." + UUID.randomUUID();

    @BeforeAll void observeResults() {
        admin = new RabbitAdmin(rabbit);
        admin.declareQueue(new Queue(results, true, false, false));
        admin.declareBinding(new Binding(results, Binding.DestinationType.QUEUE,
                MessagingConfiguration.EVENTS, "#", null));
    }
    @AfterAll void cleanupObservationQueue() throws InterruptedException {
        await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE status<>'PUBLISHED'", Integer.class) == 0);
        admin.deleteQueue(results);
    }

    PaymentEvent event(String type, String method, String trip) {
        String operation = switch (type) {
            case "PaymentRequested" -> "PAY";
            case "PaymentRefundRequested" -> "REFUND";
            case "CashPaymentConfirmationRequested" -> "CONFIRM_CASH";
            default -> null;
        };
        return new PaymentEvent(UUID.randomUUID().toString(), type, trip, Instant.now(),
                new PaymentEvent.Payload(trip, "455c5f5e-785c-4112-af43-8aa0a145b21c", method,
                        new BigDecimal("50000"), "VND", operation == null ? null : trip + ":" + operation));
    }
    void send(PaymentEvent event) {
        publisher.send(MessagingConfiguration.EVENTS, event.eventType(), MessageBuilder
                .withBody(json.writeValueAsString(event).getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT).build());
    }
    String state(String trip) {
        return jdbc.queryForList("SELECT status FROM payments WHERE trip_id=?", String.class, trip)
                .stream().findFirst().orElse("ABSENT");
    }
    void await(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        fail("Real RabbitMQ flow did not finish within 20 seconds");
    }
    @Test void payAndDuplicateThenRefundTravelThroughRealBroker() throws Exception {
        String trip = UUID.randomUUID().toString();
        var pay = event("PaymentRequested", "TRANSFER", trip); send(pay); send(pay);
        await(() -> "SUCCESS".equals(state(trip)));
        send(event("PaymentRefundRequested", "TRANSFER", trip));
        await(() -> "REFUNDED".equals(state(trip)));
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM payment_operations o JOIN payments p ON p.id=o.payment_id
            WHERE p.trip_id=? AND o.status='SUCCESS'
            """, Integer.class, trip)).isEqualTo(2);
        await(() -> jdbc.queryForObject("""
            SELECT COUNT(*) FROM outbox_events e JOIN payments p ON p.id=e.aggregate_id
            WHERE p.trip_id=? AND e.status='PUBLISHED'
            """, Integer.class, trip) == 2);
    }
    @Test void confirmationBeforeCashCompletionDoesNotDowngradeSuccess() throws Exception {
        String trip = UUID.randomUUID().toString();
        send(event("CashPaymentConfirmationRequested", "CASH", trip));
        await(() -> "SUCCESS".equals(state(trip)));
        var completed = event("TripCompleted", "CASH", trip); send(completed);
        await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id=?",
                Integer.class, completed.eventId()) == 1);
        assertThat(state(trip)).isEqualTo("SUCCESS");
    }
    @Test void malformedMessageIsDeadLetteredByRealBroker() throws Exception {
        String id = UUID.randomUUID().toString();
        publisher.send(MessagingConfiguration.EVENTS, "PaymentRequested", MessageBuilder
                .withBody("invalid-json".getBytes(StandardCharsets.UTF_8)).setMessageId(id)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT).build());
        final boolean[] found = {false};
        await(() -> {
            Message dead = rabbit.receive(MessagingConfiguration.DEAD);
            if (dead != null && id.equals(dead.getMessageProperties().getMessageId())) found[0] = true;
            return found[0];
        });
    }
    @Test void queuedCommandContinuesAfterListenerRestarts() throws Exception {
        String trip = UUID.randomUUID().toString();
        listeners.stop();
        try {
            send(event("PaymentRequested", "TRANSFER", trip));
            assertThat(state(trip)).isEqualTo("ABSENT");
            assertThat(admin.getQueueInfo(MessagingConfiguration.COMMANDS).getMessageCount()).isGreaterThanOrEqualTo(1);
        } finally { listeners.start(); }
        await(() -> "SUCCESS".equals(state(trip)));
    }
    @Test void smallLiveBrokerWorkloadReportsCompletionThroughput() throws Exception {
        int count = 50;
        var trips = new ArrayList<String>();
        long started = System.nanoTime();
        for (int i = 0; i < count; i++) {
            String trip = UUID.randomUUID().toString(); trips.add(trip);
            send(event("PaymentRequested", "TRANSFER", trip));
        }
        await(() -> trips.stream().allMatch(trip -> "SUCCESS".equals(state(trip))));
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        System.out.printf(Locale.ROOT,
                "LIVE_RABBIT_H2_WORKLOAD count=%d completed=%d seconds=%.3f completedPerSecond=%.2f%n",
                count, count, seconds, count / seconds);
    }
}
