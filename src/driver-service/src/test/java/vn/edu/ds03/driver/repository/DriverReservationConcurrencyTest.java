package vn.edu.ds03.driver.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.ds03.driver.DriverServiceApplication;
import vn.edu.ds03.driver.dto.CreateDriverProfileRequest;
import vn.edu.ds03.driver.dto.DriverProfileResponse;
import vn.edu.ds03.driver.dto.UpdateDriverAvailabilityRequest;
import vn.edu.ds03.driver.dto.UpdateDriverLocationRequest;
import vn.edu.ds03.driver.model.DriverStatus;
import vn.edu.ds03.driver.model.VehicleType;
import vn.edu.ds03.driver.service.DriverProfileService;
import vn.edu.ds03.driver.service.DriverReservationService;

@SpringBootTest(
        classes = DriverServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
                "spring.datasource.url=jdbc:h2:mem:driver_occ_db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.hikari.maximum-pool-size=12",
                "spring.jpa.hibernate.ddl-auto=create-drop"
        })
class DriverReservationConcurrencyTest {

    private static final byte[] TEST_KEY_BYTES =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

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
    private DriverProfileService driverProfileService;

    @Autowired
    private DriverReservationService driverReservationService;

    @BeforeEach
    void cleanDatabase() {
        driverRepository.deleteAll();
    }

    @Test
    @DisplayName("OCC: 10 concurrent threads reserving the same AVAILABLE driver produce exactly 1 winner and 9 conflicts")
    void reserveDriver_tenConcurrentRequestsForSameDriver_allowsExactlyOneReservation() throws Exception {
        String driverId = createAvailableDriver("occ-user-1", "51A-700.01");
        int threadCount = 10;
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger conflictCount = new AtomicInteger(0);
        List<String> winningTripIds = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            List<Future<String>> futures = new ArrayList<>();
            for (int index = 0; index < threadCount; index++) {
                String tripId = "trip-concurrent-" + index;
                Callable<String> task = () -> {
                    readyLatch.countDown();
                    if (!startLatch.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for start gun");
                    }
                    try {
                        DriverProfileResponse reserved = driverReservationService.reserveDriver(driverId, tripId);
                        assertThat(reserved.status()).isEqualTo(DriverStatus.RESERVED);
                        return reserved.currentTripId();
                    } catch (ResponseStatusException ex) {
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                        conflictCount.incrementAndGet();
                        return null;
                    }
                };
                futures.add(executor.submit(task));
            }

            assertThat(readyLatch.await(10, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            for (Future<String> future : futures) {
                String winner = future.get(15, TimeUnit.SECONDS);
                if (winner != null) {
                    winningTripIds.add(winner);
                }
            }
        }

        assertThat(winningTripIds).hasSize(1);
        assertThat(conflictCount.get()).isEqualTo(threadCount - 1);

        DriverProfileResponse persisted = driverProfileService.getCurrentProfile("occ-user-1");
        assertThat(persisted.status()).isEqualTo(DriverStatus.RESERVED);
        assertThat(persisted.currentTripId()).isEqualTo(winningTripIds.get(0));
    }

    @Test
    @DisplayName("Lifecycle & Saga compensation: reserve -> start -> release, idempotency, and duplicate trip guard")
    void reserveStartAndRelease_fullDatabaseLifecycle_enforcesIdempotencyAndUniqueTripConstraint() {
        String driverOneId = createAvailableDriver("saga-user-1", "51A-800.01");
        String driverTwoId = createAvailableDriver("saga-user-2", "51A-800.02");
        String tripId = "trip-saga-001";

        DriverProfileResponse firstReserve = driverReservationService.reserveDriver(driverOneId, tripId);
        DriverProfileResponse duplicateReserve = driverReservationService.reserveDriver(driverOneId, tripId);
        assertThat(firstReserve.status()).isEqualTo(DriverStatus.RESERVED);
        assertThat(duplicateReserve.currentTripId()).isEqualTo(tripId);

        assertThatThrownBy(() -> driverReservationService.reserveDriver(driverTwoId, tripId))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        DriverProfileResponse compensated = driverReservationService.releaseDriver(driverOneId, tripId);
        DriverProfileResponse duplicateCompensation = driverReservationService.releaseDriver(driverOneId, tripId);
        assertThat(compensated.status()).isEqualTo(DriverStatus.AVAILABLE);
        assertThat(compensated.currentTripId()).isNull();
        assertThat(duplicateCompensation.status()).isEqualTo(DriverStatus.AVAILABLE);

        String secondTripId = "trip-saga-002";
        driverReservationService.reserveDriver(driverOneId, secondTripId);
        DriverProfileResponse busy = driverReservationService.startTrip(driverOneId, secondTripId);
        DriverProfileResponse duplicateStart = driverReservationService.startTrip(driverOneId, secondTripId);
        assertThat(busy.status()).isEqualTo(DriverStatus.BUSY);
        assertThat(duplicateStart.status()).isEqualTo(DriverStatus.BUSY);

        assertThatThrownBy(() -> driverReservationService.releaseDriver(driverOneId, "wrong-trip-id"))
                .asInstanceOf(InstanceOfAssertFactories.type(ResponseStatusException.class))
                .satisfies(ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        DriverProfileResponse completedRelease = driverReservationService.releaseDriver(driverOneId, secondTripId);
        assertThat(completedRelease.status()).isEqualTo(DriverStatus.AVAILABLE);
        assertThat(completedRelease.currentTripId()).isNull();
    }

    @Test
    @DisplayName("Cross-endpoint race: concurrent location and availability updates never overwrite a committed reservation")
    void reserveDriver_racingWithLocationAndAvailabilityUpdates_preservesConsistentReservationState() throws Exception {
        for (int round = 0; round < 5; round++) {
            int currentRound = round;
            String userId = "race-user-" + currentRound;
            String driverId = createAvailableDriver(userId, "51A-90" + currentRound + ".01");
            int totalThreads = 7;
            CountDownLatch ready = new CountDownLatch(totalThreads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger reserveSuccesses = new AtomicInteger(0);

            try (ExecutorService executor = Executors.newFixedThreadPool(totalThreads)) {
                List<Future<?>> futures = new ArrayList<>();
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        driverReservationService.reserveDriver(driverId, "race-trip-" + currentRound);
                        reserveSuccesses.incrementAndGet();
                    } catch (ResponseStatusException ex) {
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    }
                    return null;
                }));
                for (int i = 0; i < 4; i++) {
                    int offset = i;
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        start.await(10, TimeUnit.SECONDS);
                        driverProfileService.updateLocation(
                                userId,
                                new UpdateDriverLocationRequest(
                                        new BigDecimal("10.780000" + offset),
                                        new BigDecimal("106.700000" + offset)));
                        return null;
                    }));
                }
                for (int i = 0; i < 2; i++) {
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        start.await(10, TimeUnit.SECONDS);
                        try {
                            driverProfileService.updateAvailability(
                                    userId, new UpdateDriverAvailabilityRequest(false));
                        } catch (ResponseStatusException ex) {
                            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                        }
                        return null;
                    }));
                }

                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(15, TimeUnit.SECONDS);
                }
            }

            DriverProfileResponse finalState = driverProfileService.getCurrentProfile(userId);
            if (reserveSuccesses.get() == 1) {
                assertThat(finalState.status()).isEqualTo(DriverStatus.RESERVED);
                assertThat(finalState.currentTripId()).isEqualTo("race-trip-" + round);
            } else {
                assertThat(finalState.status()).isEqualTo(DriverStatus.OFFLINE);
                assertThat(finalState.currentTripId()).isNull();
            }
        }
    }

    private String createAvailableDriver(String userId, String licensePlate) {
        DriverProfileResponse created = driverProfileService.createProfile(
                userId, new CreateDriverProfileRequest(licensePlate, VehicleType.BIKE));
        driverProfileService.updateLocation(
                userId,
                new UpdateDriverLocationRequest(
                        new BigDecimal("10.7768890"),
                        new BigDecimal("106.7008060")));
        driverProfileService.updateAvailability(userId, new UpdateDriverAvailabilityRequest(true));
        return created.driverId();
    }
}
