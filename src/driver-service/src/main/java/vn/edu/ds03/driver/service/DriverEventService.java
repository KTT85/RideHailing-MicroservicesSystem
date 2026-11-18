package vn.edu.ds03.driver.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vn.edu.ds03.driver.event.DriverInboundEvent;
import vn.edu.ds03.driver.event.DriverReleasedEvent;
import vn.edu.ds03.driver.exception.InvalidDriverEventException;
import vn.edu.ds03.driver.model.Driver;
import vn.edu.ds03.driver.model.DriverStatus;
import vn.edu.ds03.driver.model.OutboxEvent;
import vn.edu.ds03.driver.model.ProcessedEvent;
import vn.edu.ds03.driver.repository.DriverRepository;
import vn.edu.ds03.driver.repository.OutboxEventRepository;
import vn.edu.ds03.driver.repository.ProcessedEventRepository;

@Service
@ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class DriverEventService {

    public static final String CONSUMER_NAME = "driver-service";
    private static final Logger log = LoggerFactory.getLogger(DriverEventService.class);

    private final DriverRepository driverRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DriverEventService(
            DriverRepository driverRepository,
            ProcessedEventRepository processedEventRepository,
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.driverRepository = driverRepository;
        this.processedEventRepository = processedEventRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean isAlreadyProcessed(String eventId) {
        return processedEventRepository.existsByConsumerNameAndEventId(CONSUMER_NAME, eventId);
    }

    @Transactional
    public boolean processEvent(DriverInboundEvent event) {
        event.validate();
        if (processedEventRepository.existsByConsumerNameAndEventId(CONSUMER_NAME, event.eventId())) {
            log.debug("service=driver-service event=DuplicateEventIgnored eventId={}", event.eventId());
            return false;
        }

        Instant now = clock.instant();
        processedEventRepository.saveAndFlush(
                new ProcessedEvent(CONSUMER_NAME, event.eventId(), event.eventType(), now));

        if (event.isStartEvent()) {
            handleStartEvent(event);
            return false;
        }
        return handleReleaseEvent(event, now);
    }

    private void handleStartEvent(DriverInboundEvent event) {
        String tripId = event.payload().resolvedTripId();
        String driverId = event.payload().driverId();
        if (driverId != null) {
            int updated = driverRepository.startReservedTrip(driverId, tripId);
            if (updated == 0) {
                Driver driver = driverRepository.findById(driverId)
                        .orElseThrow(() -> new InvalidDriverEventException(
                                "Driver not found for TripStarted: id=" + driverId));
                if (driver.getStatus() != DriverStatus.BUSY || !tripId.equals(driver.getCurrentTripId())) {
                    log.warn(
                            "service=driver-service event=LateTripStartedIgnored eventId={} tripId={} driverId={}",
                            event.eventId(), tripId, driverId);
                }
            }
            return;
        }
        driverRepository.startReservedTripByTripId(tripId);
    }

    private boolean handleReleaseEvent(DriverInboundEvent event, Instant now) {
        String tripId = event.payload().resolvedTripId();
        String driverId = event.payload().driverId();
        Optional<Driver> holderOpt = driverRepository.findForUpdateByCurrentTripId(tripId);

        if (holderOpt.isPresent()) {
            Driver holder = holderOpt.get();
            if (driverId != null && !driverId.equals(holder.getId())) {
                throw new InvalidDriverEventException(
                        "Trip is assigned to another driver: tripId=" + tripId);
            }
            int updated = driverRepository.releaseDriverFromTrip(holder.getId(), tripId);
            Driver refreshed = driverRepository.findById(holder.getId()).orElseThrow();
            enqueueDriverReleased(
                    event.correlationId(),
                    tripId,
                    refreshed.getId(),
                    updated == 1,
                    refreshed.getStatus(),
                    now);
            return true;
        }

        if (driverId == null) {
            return false;
        }

        Optional<Driver> targetOpt = driverRepository.findById(driverId);
        if (targetOpt.isEmpty()) {
            if (event.isExplicitReleaseCommand()) {
                throw new InvalidDriverEventException("Driver not found for release: id=" + driverId);
            }
            return false;
        }

        Driver target = targetOpt.get();
        DriverStatus reportedStatus = target.getCurrentTripId() == null ? target.getStatus() : null;
        enqueueDriverReleased(event.correlationId(), tripId, driverId, false, reportedStatus, now);
        return true;
    }

    private void enqueueDriverReleased(
            String correlationId,
            String tripId,
            String driverId,
            boolean released,
            DriverStatus driverStatus,
            Instant now) {
        String outboxEventId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        var outbound = new DriverReleasedEvent(
                outboxEventId,
                DriverReleasedEvent.EVENT_TYPE,
                correlationId,
                now,
                new DriverReleasedEvent.Payload(tripId, driverId, released, driverStatus, now));
        String serialized = objectMapper.writeValueAsString(outbound);
        outboxEventRepository.saveAndFlush(new OutboxEvent(
                outboxEventId,
                tripId,
                DriverReleasedEvent.EVENT_TYPE,
                correlationId,
                serialized,
                now));
    }
}
