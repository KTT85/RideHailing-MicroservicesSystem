package vn.edu.ds03.driver.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import org.springframework.data.domain.Persistable;

@Entity
@Table(
        name = "processed_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_driver_processed_events_consumer_event",
                columnNames = {"consumer_name", "event_id"}))
public class ProcessedEvent implements Persistable<String> {

    @Id
    @Column(name = "id", nullable = false, length = 140)
    private String id;

    @Column(name = "consumer_name", nullable = false, length = 100)
    private String consumerName;

    @Column(name = "event_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Transient
    private boolean newEntity = true;

    protected ProcessedEvent() {
        this.newEntity = false;
    }

    public ProcessedEvent(String consumerName, String eventId, String eventType, Instant processedAt) {
        this.consumerName = Objects.requireNonNull(consumerName, "consumerName must not be null");
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.processedAt = Objects.requireNonNull(processedAt, "processedAt must not be null");
        this.id = consumerName + ":" + eventId;
        this.newEntity = true;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.newEntity = false;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public String getConsumerName() {
        return consumerName;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
