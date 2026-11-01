package vn.edu.ds03.payment.integration;

import vn.edu.ds03.payment.messaging.ConfirmedPublisher;
import vn.edu.ds03.payment.exception.InvalidCommand;
import vn.edu.ds03.payment.repository.OperationRepository;
import vn.edu.ds03.payment.messaging.OutboxPublisher;
import vn.edu.ds03.payment.dto.PaymentEvent;
import vn.edu.ds03.payment.model.PaymentOperation;
import vn.edu.ds03.payment.service.PaymentQueries;
import vn.edu.ds03.payment.repository.PaymentRepository;
import vn.edu.ds03.payment.service.PaymentService;
import vn.edu.ds03.payment.config.PaymentSettings;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:payment;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.flyway.enabled=false", "spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:schema-h2.sql",
    "spring.jpa.hibernate.ddl-auto=none", "spring.rabbitmq.listener.simple.auto-startup=false",
    "app.payment.workers-enabled=false", "management.health.rabbit.enabled=false",
    "app.jwt.secret=MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=",
    "app.internal-token=test-service-token-at-least-32-characters-long",
    "app.payment.max-attempts=3", "app.payment.retry-seconds=1",
    "app.payment.simulator.pay=SUCCESS", "app.payment.simulator.refund=SUCCESS",
    "app.payment.simulator.transient-attempts=0"
})
class PaymentIntegrationTest {
    @DynamicPropertySource
    static void optionalMySql(DynamicPropertyRegistry registry) {
        String url = System.getenv("PAYMENT_TEST_MYSQL_URL");
        if (url == null || url.isBlank()) return;
        if (!url.matches("jdbc:mysql://[^/]+/payment_test(?:\\?.*)?"))
            throw new IllegalArgumentException("Integration tests require a dedicated database named payment_test");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("PAYMENT_TEST_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("PAYMENT_TEST_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }
    @Autowired PaymentService service;
    @Autowired PaymentQueries queries;
    @Autowired PaymentRepository payments;
    @Autowired OperationRepository operations;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxPublisher outbox;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MutableClock clock;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @MockitoBean ConfirmedPublisher publisher;
    @LocalServerPort int port;
    final String passenger = UUID.randomUUID().toString();
    String trip;

    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary MutableClock mutableClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        Instant current = Instant.parse("2026-10-16T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return current; }
        void advance() { current = current.plusSeconds(4000); }
    }

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM payment_operations"); jdbc.update("DELETE FROM payments");
        jdbc.update("DELETE FROM processed_events"); jdbc.update("DELETE FROM outbox_events");
        reset(publisher); trip = UUID.randomUUID().toString();
    }
    PaymentEvent event(String type, String method) {
        String op = switch (type) {
            case "PaymentRequested" -> "PAY";
            case "PaymentRefundRequested" -> "REFUND";
            case "CashPaymentConfirmationRequested" -> "CONFIRM_CASH";
            default -> null;
        };
        return new PaymentEvent(UUID.randomUUID().toString(), type, UUID.randomUUID().toString(), clock.instant(),
                new PaymentEvent.Payload(trip, passenger, method, new BigDecimal("50000.00"), "VND",
                        op == null ? null : trip + ":" + op));
    }
    String op(String type) {
        return operations.findByPaymentIdAndOperationType(payments.findByTripId(trip).orElseThrow().getId(), type)
                .orElseThrow().getId();
    }
    void pay() { service.accept(event("PaymentRequested", "TRANSFER")); service.execute(op("PAY")); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    String status() { return payments.findByTripId(trip).orElseThrow().getStatus(); }

    @Test void transferPaySucceedsAndCreatesTransactionalResult() {
        pay(); assertThat(status()).isEqualTo("SUCCESS");
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(queries.byTrip(trip, passenger, false).paidAt()).isEqualTo(clock.instant());
    }
    @Test void fullRefundSucceedsExactlyOnce() {
        pay(); var refund = event("PaymentRefundRequested", "TRANSFER");
        service.accept(refund); service.execute(op("REFUND"));
        service.accept(refund); service.accept(event("PaymentRefundRequested", "TRANSFER")); service.execute(op("REFUND"));
        assertThat(status()).isEqualTo("REFUNDED");
        assertThat(payments.findByTripId(trip).orElseThrow().getRefundedAmount()).isEqualByComparingTo("50000");
        assertThat(count("payment_operations")).isEqualTo(2); assertThat(count("outbox_events")).isEqualTo(2);
    }
    @Test void cashCompletionDoesNotMeanMoneyReceived() {
        service.accept(event("TripCompleted", "CASH"));
        assertThat(status()).isEqualTo("PENDING"); assertThat(count("payment_operations")).isZero();
    }
    @Test void cashConfirmationWorksBeforeCompletionEvent() {
        service.accept(event("CashPaymentConfirmationRequested", "CASH")); service.execute(op("CONFIRM_CASH"));
        service.accept(event("TripCompleted", "CASH"));
        assertThat(status()).isEqualTo("SUCCESS"); assertThat(count("outbox_events")).isEqualTo(1);
    }
    @Test void transferCompletionNeverChargesAgain() {
        pay(); service.accept(event("TripCompleted", "TRANSFER"));
        assertThat(count("payment_operations")).isEqualTo(1); assertThat(count("outbox_events")).isEqualTo(1);
    }
    @Test void redeliveryAndNewEventForSamePayHaveOneMoneyEffect() {
        var e = event("PaymentRequested", "TRANSFER"); service.accept(e); service.accept(e);
        service.accept(event("PaymentRequested", "TRANSFER")); service.execute(op("PAY")); service.execute(op("PAY"));
        assertThat(count("payments")).isEqualTo(1); assertThat(count("payment_operations")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
    }
    @Test void refundArrivingBeforePayWaitsThenRecovers() {
        service.accept(event("PaymentRefundRequested", "TRANSFER")); service.execute(op("REFUND"));
        assertThat(status()).isEqualTo("PENDING"); assertThat(count("outbox_events")).isZero();
        pay(); clock.advance(); service.execute(op("REFUND")); assertThat(status()).isEqualTo("REFUNDED");
    }
    @Test void refundWithoutSuccessfulPayNeverRefundsAndStopsRetrying() {
        service.accept(event("PaymentRefundRequested", "TRANSFER"));
        for (int i = 0; i < 3; i++) { service.execute(op("REFUND")); clock.advance(); }
        assertThat(operations.findById(op("REFUND")).orElseThrow().getStatus()).isEqualTo("MANUAL_REVIEW");
        assertThat(payments.findByTripId(trip).orElseThrow().getRefundedAmount()).isEqualByComparingTo("0");
    }
    @Test void amountConflictIsRejectedAndNotRemembered() {
        pay(); var e = event("PaymentRequested", "TRANSFER");
        var bad = new PaymentEvent(e.eventId(), e.eventType(), e.correlationId(), e.timestamp(),
                new PaymentEvent.Payload(trip, passenger, "TRANSFER", new BigDecimal("1"), "VND", trip + ":PAY"));
        assertThatThrownBy(() -> service.accept(bad)).isInstanceOf(InvalidCommand.class);
        assertThat(count("processed_events")).isEqualTo(1);
    }
    @Test void eventIdCannotCreateAnotherPaymentOnRedelivery() {
        var original = event("PaymentRequested", "TRANSFER"); service.accept(original);
        trip = UUID.randomUUID().toString(); var changed = event("PaymentRequested", "TRANSFER");
        service.accept(new PaymentEvent(original.eventId(), changed.eventType(), changed.correlationId(),
                changed.timestamp(), changed.payload()));
        assertThat(count("payments")).isEqualTo(1); assertThat(count("payment_operations")).isEqualTo(1);
    }
    PaymentSettings settings(PaymentSettings.Outcome pay, PaymentSettings.Outcome refund, int transientAttempts) {
        return new PaymentSettings(3, 1, 65536, new PaymentSettings.Simulator(pay, refund, transientAttempts));
    }
    void executeWith(String id, PaymentSettings config) {
        // New service instance reads persisted work; no operation state is kept in memory.
        var restarted = new PaymentService(payments, operations, jdbc, json, config, clock);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> restarted.execute(id));
    }
    @Test void definitivePayFailureEmitsFailureWithoutRefund() {
        service.accept(event("PaymentRequested", "TRANSFER"));
        executeWith(op("PAY"), settings(PaymentSettings.Outcome.FAILED, PaymentSettings.Outcome.SUCCESS, 0));
        assertThat(status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT event_type FROM outbox_events", String.class)).isEqualTo("PaymentFailed");
    }
    @Test void persistedTransientPayContinuesInNewServiceInstance() {
        service.accept(event("PaymentRequested", "TRANSFER")); String id = op("PAY");
        var config = settings(PaymentSettings.Outcome.SUCCESS, PaymentSettings.Outcome.SUCCESS, 1);
        executeWith(id, config); assertThat(status()).isEqualTo("PENDING");
        assertThat(operations.findById(id).orElseThrow().getAttemptCount()).isEqualTo(1);
        clock.advance(); executeWith(id, config);
        assertThat(status()).isEqualTo("SUCCESS"); assertThat(count("outbox_events")).isEqualTo(1);
    }
    @Test void exhaustedPayIsNotMisreportedAsDeclinedAndCanResume() {
        service.accept(event("PaymentRequested", "TRANSFER")); String id = op("PAY");
        for (int i = 0; i < 3; i++) {
            executeWith(id, settings(PaymentSettings.Outcome.TRANSIENT, PaymentSettings.Outcome.SUCCESS, 0)); clock.advance();
        }
        assertThat(status()).isEqualTo("PENDING");
        assertThat(operations.findById(id).orElseThrow().getStatus()).isEqualTo("MANUAL_REVIEW");
        assertThat(jdbc.queryForObject("SELECT event_type FROM outbox_events", String.class)).isEqualTo("PaymentReviewRequired");
        queries.retryOperation(id); service.execute(id); assertThat(status()).isEqualTo("SUCCESS");
    }
    @Test void refundFailureRetainsPaidMoneyAndCanResumeWithoutChargingAgain() {
        pay(); service.accept(event("PaymentRefundRequested", "TRANSFER")); String id = op("REFUND");
        for (int i = 0; i < 3; i++) {
            executeWith(id, settings(PaymentSettings.Outcome.SUCCESS, PaymentSettings.Outcome.FAILED, 0)); clock.advance();
        }
        assertThat(status()).isEqualTo("REFUND_FAILED");
        assertThat(payments.findByTripId(trip).orElseThrow().getRefundedAmount()).isEqualByComparingTo("0");
        queries.retryOperation(id); service.execute(id);
        assertThat(status()).isEqualTo("REFUNDED");
        assertThat(operations.findById(op("PAY")).orElseThrow().getAttemptCount()).isEqualTo(1);
    }
    @Test void concurrentPaymentRequestsAndWorkersHaveOneEffect() throws Exception {
        runTogether(() -> service.accept(event("PaymentRequested", "TRANSFER")),
                    () -> service.accept(event("PaymentRequested", "TRANSFER")));
        String id = op("PAY"); runTogether(() -> service.execute(id), () -> service.execute(id));
        assertThat(count("payments")).isEqualTo(1); assertThat(count("outbox_events")).isEqualTo(1);
    }
    @Test void concurrentRefundsHaveOneEffect() throws Exception {
        pay();
        runTogether(() -> service.accept(event("PaymentRefundRequested", "TRANSFER")),
                    () -> service.accept(event("PaymentRefundRequested", "TRANSFER")));
        String id = op("REFUND"); runTogether(() -> service.execute(id), () -> service.execute(id));
        assertThat(status()).isEqualTo("REFUNDED"); assertThat(count("outbox_events")).isEqualTo(2);
    }
    void runTogether(Runnable a, Runnable b) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            var first = pool.submit(() -> { await(barrier); a.run(); });
            var second = pool.submit(() -> { await(barrier); b.run(); });
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        }
    }
    static void await(CyclicBarrier barrier) {
        try { barrier.await(5, TimeUnit.SECONDS); } catch (Exception ex) { throw new RuntimeException(ex); }
    }
    @Test void failureBeforeCommitRollsBackMoneyOperationAndOutboxTogether() {
        service.accept(event("PaymentRequested", "TRANSFER")); String id = op("PAY");
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            service.execute(id); throw new IllegalStateException("Simulated crash before commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(status()).isEqualTo("PENDING"); assertThat(count("outbox_events")).isZero();
        service.execute(id); assertThat(status()).isEqualTo("SUCCESS");
    }
    @Test void brokerFailureKeepsOutboxAndResendsSameEventAfterRecovery() {
        pay(); String id = jdbc.queryForObject("SELECT event_id FROM outbox_events", String.class);
        doThrow(new IllegalStateException("Broker down")).when(publisher).send(anyString(), anyString(), any());
        outbox.publishDue();
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events", String.class)).isEqualTo("PENDING");
        reset(publisher); clock.advance(); outbox.publishDue();
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events", String.class)).isEqualTo("PUBLISHED");
        verify(publisher).send(eq("ride.events"), eq("PaymentSucceeded"),
                argThat(m -> id.equals(m.getMessageProperties().getMessageId())));
    }
    @Test void outboxExhaustionIsVisibleAndAdminCanResume() {
        pay(); doThrow(new IllegalStateException("unroutable")).when(publisher).send(anyString(), anyString(), any());
        for (int i = 0; i < 3; i++) { outbox.publishDue(); clock.advance(); }
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events", String.class)).isEqualTo("MANUAL_REVIEW");
        queries.retryOutbox(jdbc.queryForObject("SELECT event_id FROM outbox_events", String.class));
        reset(publisher); outbox.publishDue();
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events", String.class)).isEqualTo("PUBLISHED");
    }
    String token(String user, String role) {
        var key = new SecretKeySpec(Base64.getDecoder().decode("MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI="), "HmacSHA256");
        var encoder = NimbusJwtEncoder.withSecretKey(key).build();
        var claims = JwtClaimsSet.builder().issuer("ds03-user-service").subject(user)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).claim("role", role).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
    int get(String path, String name, String value) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (name != null) request.header(name, value);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }
    @Test void missingJwtCannotReadPayment() throws Exception {
        pay(); assertThat(get("/payments/by-trip/" + trip, null, null)).isEqualTo(401);
    }
    @Test void otherPassengerAndDriverCannotReadPayment() throws Exception {
        pay();
        assertThat(get("/payments/by-trip/" + trip, "Authorization", "Bearer " + token(UUID.randomUUID().toString(), "PASSENGER"))).isEqualTo(403);
        assertThat(get("/payments/by-trip/" + trip, "Authorization", "Bearer " + token(passenger, "DRIVER"))).isEqualTo(403);
    }
    @Test void ownerCanReadAndInternalApiRequiresServiceCredential() throws Exception {
        pay();
        assertThat(get("/payments/by-trip/" + trip, "Authorization", "Bearer " + token(passenger, "PASSENGER"))).isEqualTo(200);
        assertThat(get("/internal/payments/by-trip/" + trip, null, null)).isEqualTo(401);
        assertThat(get("/internal/payments/by-trip/" + trip, "X-Service-Token", "test-service-token-at-least-32-characters-long")).isEqualTo(200);
    }
    @Test void retryApiRequiresAdminAndCannotRetrySuccessfulMoneyOperation() throws Exception {
        pay(); String path = "http://localhost:" + port + "/admin/payments/operations/" + op("PAY") + "/retry";
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(URI.create(path)).POST(HttpRequest.BodyPublishers.noBody());
        assertThat(client.send(request.header("Authorization", "Bearer " + token(passenger, "PASSENGER")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        request.setHeader("Authorization", "Bearer " + token(passenger, "ADMIN"));
        assertThat(client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
        assertThat(count("outbox_events")).isEqualTo(1);
    }
    @Test void utcDatabaseColumnsAgreeWithApiAndPersistedRetrySchedule() {
        service.accept(event("PaymentRequested", "TRANSFER"));
        LocalDateTime stored = jdbc.queryForObject("SELECT created_at FROM payments", LocalDateTime.class);
        assertThat(stored).isEqualTo(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        PaymentOperation op = operations.findById(op("PAY")).orElseThrow();
        assertThat(op.getNextRetryAt()).isEqualTo(stored);
    }
    @Test void apiPreservesNotFoundAndInvalidInputStatuses() throws Exception {
        String auth = "Bearer " + token(passenger, "PASSENGER");
        assertThat(get("/payments/by-trip/" + trip, "Authorization", auth)).isEqualTo(404);
        assertThat(get("/payments/by-trip/not-a-uuid", "Authorization", auth)).isEqualTo(400);
    }
}
