package vn.edu.ds03.driver.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Objects;
import org.springframework.data.domain.Persistable;

@Entity
@Table(
        name = "outbox_events",
        indexes = {
                @Index(name = "idx_driver_outbox_retry", columnList = "status, next_retry_at"),
                @Index(name = "idx_driver_outbox_aggregate", columnList = "aggregate_id, created_at")
        })
public class OutboxEvent implements Persistable<String> {

    @Id
    @Column(name = "event_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String eventId;

    @Column(name = "aggregate_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "correlation_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String correlationId;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean newEntity = true;

    protected OutboxEvent() {
        this.newEntity = false;
    }

    public OutboxEvent(
            String eventId,
            String aggregateId,
            String eventType,
            String correlationId,
            String payload,
            Instant createdAt) {
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId must not be null");
        this.payload = Objects.requireNonNull(payload, "payload must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.status = OutboxStatus.PENDING;
        this.attemptCount = 0;
        this.newEntity = true;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.newEntity = false;
    }

    @Override
    public String getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public String getEventId() {
        return eventId;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
