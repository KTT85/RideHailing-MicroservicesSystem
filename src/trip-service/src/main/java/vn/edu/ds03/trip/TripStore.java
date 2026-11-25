package vn.edu.ds03.trip;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tools.jackson.databind.JsonNode;

interface TripStore extends JpaRepository<Ride, String> {
    Optional<Ride> findByIdempotencyKey(String key);

    @Query(value = "SELECT id FROM trip_workflow_lock WHERE id = 1 FOR UPDATE", nativeQuery = true)
    int lockWorkflow();
}

@Entity
@Table(name = "outbox_events")
class TripOutboxEvent {
    @Id String id;
    String aggregateId, eventType, correlationId;
    Instant createdAt, publishedAt;
    @JdbcTypeCode(SqlTypes.JSON) JsonNode payload;
}

interface TripOutbox extends JpaRepository<TripOutboxEvent, String> {
    List<TripOutboxEvent> findTop100ByPublishedAtIsNullOrderByCreatedAtAscIdAsc();
}

@Entity
@Table(name = "processed_events")
class ProcessedTripEvent {
    @Id String eventId;
    Instant processedAt;

    protected ProcessedTripEvent() {}

    ProcessedTripEvent(String eventId) {
        this.eventId = eventId;
        this.processedAt = Instant.now();
    }
}

interface ProcessedTripEvents extends JpaRepository<ProcessedTripEvent, String> {}
