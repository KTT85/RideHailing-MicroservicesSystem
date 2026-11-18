package vn.edu.ds03.matching.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.edu.ds03.matching.config.MatchingProperties;
import vn.edu.ds03.matching.dto.DriverCandidateDto;
import vn.edu.ds03.matching.dto.ReservedDriverDto;
import vn.edu.ds03.matching.event.MatchingResultEvent;
import vn.edu.ds03.matching.event.TripRequestedEvent;

@Service
public class MatchingService {

    private static final Logger log = LoggerFactory.getLogger(MatchingService.class);
    private static final int MAX_DEDUP_ENTRIES = 5000;
    private static final String NO_DRIVER_REASON = "NO_AVAILABLE_DRIVER";

    private final DriverServiceClient driverClient;
    private final MatchingProperties properties;
    private final Clock clock;
    private final ReentrantLock stateLock = new ReentrantLock();
    private final Set<String> completedEventIds = Collections.newSetFromMap(createBoundedMap());
    private final Map<String, MatchingResultEvent> assignedTripsByTripId = createBoundedMap();

    public MatchingService(
            DriverServiceClient driverClient,
            MatchingProperties properties,
            Clock clock) {
        this.driverClient = driverClient;
        this.properties = properties;
        this.clock = clock;
    }

    public Optional<MatchingResultEvent> matchTrip(TripRequestedEvent event) {
        event.validate();
        String tripId = event.payload().resolvedTripId();

        stateLock.lock();
        try {
            if (completedEventIds.contains(event.eventId())) {
                log.debug(
                        "service=matching-service event=DuplicateTripRequestedIgnored eventId={}",
                        event.eventId());
                return Optional.empty();
            }
            MatchingResultEvent existingAssignment = assignedTripsByTripId.get(tripId);
            if (existingAssignment != null) {
                return Optional.of(existingAssignment);
            }
        } finally {
            stateLock.unlock();
        }

        String requestedVehicle = event.payload().resolvedVehicleType();
        List<DriverCandidateDto> candidates = driverClient.fetchCandidates(
                event.payload().resolvedLatitude(),
                event.payload().resolvedLongitude(),
                requestedVehicle,
                properties.candidateLimit());

        Instant now = clock.instant();
        for (DriverCandidateDto candidate : candidates) {
            if (candidate == null || candidate.driverId() == null) {
                continue;
            }
            Optional<ReservedDriverDto> reservedOpt =
                    driverClient.tryReserveDriver(candidate.driverId(), tripId);
            if (reservedOpt.isPresent()) {
                ReservedDriverDto reserved = reservedOpt.get();
                MatchingResultEvent assignedEvent = buildAssignedEvent(event, tripId, candidate, reserved, now);
                cacheAssignment(tripId, assignedEvent);
                return Optional.of(assignedEvent);
            }
        }

        return Optional.of(buildUnavailableEvent(event, tripId, now));
    }

    public void markEventCompleted(String eventId) {
        stateLock.lock();
        try {
            completedEventIds.add(eventId);
        } finally {
            stateLock.unlock();
        }
    }

    private void cacheAssignment(String tripId, MatchingResultEvent assignedEvent) {
        stateLock.lock();
        try {
            assignedTripsByTripId.put(tripId, assignedEvent);
        } finally {
            stateLock.unlock();
        }
    }

    private MatchingResultEvent buildAssignedEvent(
            TripRequestedEvent inbound,
            String tripId,
            DriverCandidateDto candidate,
            ReservedDriverDto reserved,
            Instant now) {
        String outType = inbound.isDotCaseConvention() ? "driver.assigned" : "DriverAssigned";
        String plate = reserved.vehicle() != null && reserved.vehicle().licensePlate() != null
                ? reserved.vehicle().licensePlate()
                : candidate.licensePlate();
        String vehicleType = reserved.vehicle() != null && reserved.vehicle().vehicleType() != null
                ? reserved.vehicle().vehicleType()
                : candidate.vehicleType();
        return new MatchingResultEvent(
                UUID.randomUUID().toString().toLowerCase(Locale.ROOT),
                outType,
                inbound.correlationId(),
                now,
                new MatchingResultEvent.Payload(
                        tripId,
                        reserved.driverId() != null ? reserved.driverId() : candidate.driverId(),
                        vehicleType,
                        plate,
                        now,
                        null));
    }

    private MatchingResultEvent buildUnavailableEvent(
            TripRequestedEvent inbound, String tripId, Instant now) {
        String outType = inbound.isDotCaseConvention() ? "driver.unavailable" : "NoDriverAvailable";
        return new MatchingResultEvent(
                UUID.randomUUID().toString().toLowerCase(Locale.ROOT),
                outType,
                inbound.correlationId(),
                now,
                new MatchingResultEvent.Payload(
                        tripId, null, null, null, null, NO_DRIVER_REASON));
    }

    private static <K, V> Map<K, V> createBoundedMap() {
        return new LinkedHashMap<>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > MAX_DEDUP_ENTRIES;
            }
        };
    }
}
