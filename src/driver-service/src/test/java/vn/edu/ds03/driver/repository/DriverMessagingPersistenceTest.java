package vn.edu.ds03.driver.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.edu.ds03.driver.DriverServiceApplication;
import vn.edu.ds03.driver.event.DriverInboundEvent;
import vn.edu.ds03.driver.exception.InvalidDriverEventException;
import vn.edu.ds03.driver.messaging.publisher.ConfirmedRabbitPublisher;
import vn.edu.ds03.driver.messaging.publisher.DriverOutboxPublisher;
import vn.edu.ds03.driver.model.Driver;
import vn.edu.ds03.driver.model.DriverStatus;
import vn.edu.ds03.driver.model.OutboxEvent;
import vn.edu.ds03.driver.model.OutboxStatus;
import vn.edu.ds03.driver.model.Vehicle;
import vn.edu.ds03.driver.model.VehicleType;
import vn.edu.ds03.driver.service.DriverEventService;
import vn.edu.ds03.driver.service.DriverReservationService;

@SpringBootTest(
        classes = DriverServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
                "spring.datasource.url=jdbc:h2:mem:driver_messaging_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "app.messaging.max-retry-attempts=3",
                "app.messaging.outbox-retry-seconds=5",
                "app.messaging.outbox-jitter-factor=0.0",
                "app.messaging.outbox-scheduler-enabled=false"
        })
@Import(DriverMessagingPersistenceTest.MutableClockConfig.class)
class DriverMessagingPersistenceTest {

    private static final byte[] TEST_KEY_BYTES =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final AtomicReference<Instant> CURRENT_INSTANT =
            new AtomicReference<>(Instant.parse("2026-11-18T06:30:00Z"));

    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return new Clock() {
                @Override
                public ZoneOffset getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return CURRENT_INSTANT.get();
                }
            };
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> Base64.getEncoder().encodeToString(TEST_KEY_BYTES));
        registry.add("app.jwt.issuer", () -> "ds03-user-service");
        registry.add("MYSQL_PASSWORD", () -> "");
        registry.add("RABBITMQ_USER", () -> "unused");
        registry.add("RABBITMQ_PASSWORD", () -> "unused");
    }

    @Autowired
    private DriverRepository driverRepository;

    @Autowired
    private DriverReservationService driverReservationService;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private DriverEventService driverEventService;

    @Autowired
    private DriverOutboxPublisher outboxPublisher;

    @MockitoBean
    private ConfirmedRabbitPublisher confirmedPublisher;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        processedEventRepository.deleteAll();
        driverRepository.deleteAll();
        reset(confirmedPublisher);
        CURRENT_INSTANT.set(Instant.parse("2026-11-18T06:30:00Z"));
    }

    private Driver createReservedDriver(String plate, String tripId) {
        Driver driver = new Driver(UUID.randomUUID().toString());
        driver.attachVehicle(new Vehicle(plate, VehicleType.CAR));
        driver.updateLocation(new BigDecimal("10.7768890"), new BigDecimal("106.7008060"));
        driver.updateAvailability(true);
        Driver saved = driverRepository.saveAndFlush(driver);
        driverReservationService.reserveDriver(saved.getId(), tripId);
        return driverRepository.findById(saved.getId()).orElseThrow();
    }

    @Test
    @DisplayName("TripStarted transitions RESERVED driver to BUSY and deduplicates repeat eventId")
    void processEvent_tripStarted_transitionsToBusyAndDeduplicates() {
        String tripId = UUID.randomUUID().toString();
        Driver driver = createReservedDriver("51A-70001", tripId);
        String eventId = UUID.randomUUID().toString();
        var startEvent = new DriverInboundEvent(
                eventId,
                "TripStarted",
                UUID.randomUUID().toString(),
                CURRENT_INSTANT.get(),
                null,
                new DriverInboundEvent.Payload(tripId, driver.getId(), null));

        boolean createdOutboxFirst = driverEventService.processEvent(startEvent);
        boolean createdOutboxDuplicate = driverEventService.processEvent(startEvent);

        assertThat(createdOutboxFirst).isFalse();
        assertThat(createdOutboxDuplicate).isFalse();
        assertThat(driverEventService.isAlreadyProcessed(eventId)).isTrue();
        Driver updated = driverRepository.findById(driver.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(DriverStatus.BUSY);
        assertThat(updated.getCurrentTripId()).isEqualTo(tripId);
        assertThat(processedEventRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("DriverReleaseRequested releases driver, enqueues DriverReleased outbox, and deduplicates")
    void processEvent_releaseRequested_releasesDriverAndCreatesSingleOutboxRow() {
        String tripId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();
        Driver driver = createReservedDriver("51A-70002", tripId);
        String eventId = UUID.randomUUID().toString();
        var releaseEvent = new DriverInboundEvent(
                eventId,
                "DriverReleaseRequested",
                correlationId,
                CURRENT_INSTANT.get(),
                null,
                new DriverInboundEvent.Payload(tripId, driver.getId(), "payment_failed"));

        boolean queuedFirst = driverEventService.processEvent(releaseEvent);
        boolean queuedSecond = driverEventService.processEvent(releaseEvent);

        assertThat(queuedFirst).isTrue();
        assertThat(queuedSecond).isFalse();

        Driver released = driverRepository.findById(driver.getId()).orElseThrow();
        assertThat(released.getStatus()).isEqualTo(DriverStatus.AVAILABLE);
        assertThat(released.getCurrentTripId()).isNull();
        assertThat(processedEventRepository.count()).isEqualTo(1);

        var outboxRows = outboxEventRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        OutboxEvent outbox = outboxRows.getFirst();
        assertThat(outbox.getEventType()).isEqualTo("DriverReleased");
        assertThat(outbox.getAggregateId()).isEqualTo(tripId);
        assertThat(outbox.getCorrelationId()).isEqualTo(correlationId);
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);

        JsonNode json = jsonMapper.readTree(outbox.getPayload());
        assertThat(json.get("eventType").asString()).isEqualTo("DriverReleased");
        assertThat(json.get("payload").get("tripId").asString()).isEqualTo(tripId);
        assertThat(json.get("payload").get("driverId").asString()).isEqualTo(driver.getId());
        assertThat(json.get("payload").get("released").asBoolean()).isTrue();
        assertThat(json.get("payload").get("driverStatus").asString()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("TripCompleted without driverId releases BUSY driver back to AVAILABLE")
    void processEvent_tripCompletedWithoutDriverId_releasesBusyDriver() {
        String tripId = UUID.randomUUID().toString();
        Driver driver = createReservedDriver("51A-70003", tripId);
        driverReservationService.startTrip(driver.getId(), tripId);

        var completed = new DriverInboundEvent(
                UUID.randomUUID().toString(),
                "TripCompleted",
                UUID.randomUUID().toString(),
                CURRENT_INSTANT.get(),
                null,
                new DriverInboundEvent.Payload(tripId, null, null));

        boolean queued = driverEventService.processEvent(completed);

        assertThat(queued).isTrue();
        Driver refreshed = driverRepository.findById(driver.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(DriverStatus.AVAILABLE);
        assertThat(refreshed.getCurrentTripId()).isNull();
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("late DriverReleaseRequested for old tripA does not disturb driver reassigned to tripB")
    void processEvent_lateReleaseWhenDriverOnNewTrip_preservesNewTripAndEmitsConfirmation() {
        String tripA = UUID.randomUUID().toString();
        String tripB = UUID.randomUUID().toString();
        Driver driver = createReservedDriver("51A-70004", tripA);
        driverReservationService.releaseDriver(driver.getId(), tripA);
        driverReservationService.reserveDriver(driver.getId(), tripB);

        var lateReleaseForA = new DriverInboundEvent(
                UUID.randomUUID().toString(),
                "DriverReleaseRequested",
                UUID.randomUUID().toString(),
                CURRENT_INSTANT.get(),
                null,
                new DriverInboundEvent.Payload(tripA, driver.getId(), "late_cancel"));

        boolean queued = driverEventService.processEvent(lateReleaseForA);

        assertThat(queued).isTrue();
        Driver stillOnTripB = driverRepository.findById(driver.getId()).orElseThrow();
        assertThat(stillOnTripB.getStatus()).isEqualTo(DriverStatus.RESERVED);
        assertThat(stillOnTripB.getCurrentTripId()).isEqualTo(tripB);

        OutboxEvent outbox = outboxEventRepository.findAll().getFirst();
        JsonNode json = jsonMapper.readTree(outbox.getPayload());
        assertThat(json.get("payload").get("tripId").asString()).isEqualTo(tripA);
        assertThat(json.get("payload").get("released").asBoolean()).isFalse();
        assertThat(json.get("payload").get("driverStatus").isNull()).isTrue();
    }

    @Test
    @DisplayName("late TripStarted without driverId and TripCancelled without assigned driver complete cleanly")
    void processEvent_unassignedOrAlreadyReleasedTrip_completesWithoutOutbox() {
        String unassignedTrip = UUID.randomUUID().toString();
        boolean startQueued = driverEventService.processEvent(new DriverInboundEvent(
                UUID.randomUUID().toString(),
                "trip.started",
                UUID.randomUUID().toString(),
                null,
                CURRENT_INSTANT.get(),
                new DriverInboundEvent.Payload(unassignedTrip, null, null)));
        boolean cancelQueued = driverEventService.processEvent(new DriverInboundEvent(
                UUID.randomUUID().toString(),
                "trip.cancelled",
                UUID.randomUUID().toString(),
                null,
                CURRENT_INSTANT.get(),
                new DriverInboundEvent.Payload(unassignedTrip, null, "passenger_cancelled")));

        assertThat(startQueued).isFalse();
        assertThat(cancelQueued).isFalse();
        assertThat(processedEventRepository.count()).isEqualTo(2);
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("release event with mismatched driverId or unknown driver throws InvalidDriverEventException")
    void processEvent_mismatchedOrUnknownDriver_throwsInvalidDriverEvent() {
        String tripId = UUID.randomUUID().toString();
        createReservedDriver("51A-70005", tripId);
        String wrongDriverId = UUID.randomUUID().toString();

        var badRelease = new DriverInboundEvent(
                UUID.randomUUID().toString(),
                "DriverReleaseRequested",
                UUID.randomUUID().toString(),
                CURRENT_INSTANT.get(),
                null,
                new DriverInboundEvent.Payload(tripId, wrongDriverId, "wrong_driver"));

        assertThatThrownBy(() -> driverEventService.processEvent(badRelease))
                .isInstanceOf(InvalidDriverEventException.class);

        var unknownDriverRelease = new DriverInboundEvent(
                UUID.randomUUID().toString(),
                "DriverReleaseRequested",
                UUID.randomUUID().toString(),
                CURRENT_INSTANT.get(),
                null,
                new DriverInboundEvent.Payload(UUID.randomUUID().toString(), wrongDriverId, "missing"));

        assertThatThrownBy(() -> driverEventService.processEvent(unknownDriverRelease))
                .isInstanceOf(InvalidDriverEventException.class);
    }

    @Test
    @DisplayName("DriverOutboxPublisher backs off on transient broker error, escalates to MANUAL_REVIEW, and publishes on recovery")
    void outboxPublisher_retriesWithBackoffAndMarksPublishedOrManualReview() {
        String tripA = UUID.randomUUID().toString();
        Driver driverA = createReservedDriver("51A-70006", tripA);
        driverEventService.processEvent(new DriverInboundEvent(
                UUID.randomUUID().toString(),
                "DriverReleaseRequested",
                UUID.randomUUID().toString(),
                CURRENT_INSTANT.get(),
                null,
                new DriverInboundEvent.Payload(tripA, driverA.getId(), "cancel")));

        doThrow(new IllegalStateException("broker unreachable"))
                .when(confirmedPublisher).send(eq("ride.events"), eq("DriverReleased"), any(), eq(false));

        // Attempt 1 fails -> PENDING with backoff +5s and sanitized error message
        outboxPublisher.publishDueEvents();
        OutboxEvent afterAttempt1 = outboxEventRepository.findAll().getFirst();
        assertThat(afterAttempt1.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(afterAttempt1.getAttemptCount()).isEqualTo(1);
        assertThat(afterAttempt1.getNextRetryAt()).isEqualTo(CURRENT_INSTANT.get().plusSeconds(5));
        assertThat(afterAttempt1.getLastError())
                .isEqualTo("Publish not confirmed by broker; scheduled for retry or manual review")
                .doesNotContain("broker unreachable");

        // Before nextRetryAt -> skipped
        outboxPublisher.publishDueEvents();
        assertThat(outboxEventRepository.findAll().getFirst().getAttemptCount()).isEqualTo(1);

        // Attempt 2 at +5s fails -> PENDING with backoff +10s
        CURRENT_INSTANT.set(CURRENT_INSTANT.get().plusSeconds(5));
        outboxPublisher.publishDueEvents();
        OutboxEvent afterAttempt2 = outboxEventRepository.findAll().getFirst();
        assertThat(afterAttempt2.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(afterAttempt2.getAttemptCount()).isEqualTo(2);

        // Attempt 3 at +10s fails -> reaches maxRetryAttempts (3) -> MANUAL_REVIEW
        CURRENT_INSTANT.set(CURRENT_INSTANT.get().plusSeconds(10));
        outboxPublisher.publishDueEvents();
        OutboxEvent afterAttempt3 = outboxEventRepository.findAll().getFirst();
        assertThat(afterAttempt3.getStatus()).isEqualTo(OutboxStatus.MANUAL_REVIEW);
        assertThat(afterAttempt3.getAttemptCount()).isEqualTo(3);

        // Second trip outbox succeeds immediately when broker recovers
        reset(confirmedPublisher);
        doNothing().when(confirmedPublisher).send(eq("ride.events"), eq("DriverReleased"), any(), eq(false));

        String tripB = UUID.randomUUID().toString();
        Driver driverB = createReservedDriver("51A-70007", tripB);
        driverEventService.processEvent(new DriverInboundEvent(
                UUID.randomUUID().toString(),
                "TripCompleted",
                UUID.randomUUID().toString(),
                CURRENT_INSTANT.get(),
                null,
                new DriverInboundEvent.Payload(tripB, driverB.getId(), null)));

        outboxPublisher.publishDueEvents();
        verify(confirmedPublisher, times(1))
                .send(eq("ride.events"), eq("DriverReleased"), any(), eq(false));

        OutboxEvent publishedRow = outboxEventRepository.findAll().stream()
                .filter(e -> tripB.equals(e.getAggregateId()))
                .findFirst()
                .orElseThrow();
        assertThat(publishedRow.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(publishedRow.getAttemptCount()).isEqualTo(1);
        assertThat(publishedRow.getLastError()).isNull();
    }
}
