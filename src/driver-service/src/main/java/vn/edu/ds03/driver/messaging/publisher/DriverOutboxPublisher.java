package vn.edu.ds03.driver.messaging.publisher;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import vn.edu.ds03.driver.config.DriverMessagingProperties;
import vn.edu.ds03.driver.model.OutboxEvent;
import vn.edu.ds03.driver.model.OutboxStatus;
import vn.edu.ds03.driver.repository.OutboxEventRepository;

@Component
@ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class DriverOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(DriverOutboxPublisher.class);
    private static final int BATCH_SIZE = 50;
    private static final String SAFE_OUTBOX_ERROR =
            "Publish not confirmed by broker; scheduled for retry or manual review";

    private final OutboxEventRepository outboxEventRepository;
    private final ConfirmedRabbitPublisher rabbitPublisher;
    private final DriverMessagingProperties properties;
    private final Clock clock;
    private final ReentrantLock publishLock = new ReentrantLock();

    public DriverOutboxPublisher(
            OutboxEventRepository outboxEventRepository,
            ConfirmedRabbitPublisher rabbitPublisher,
            DriverMessagingProperties properties,
            Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.rabbitPublisher = rabbitPublisher;
        this.properties = properties;
        this.clock = clock;
    }

    public void publishDueEvents() {
        if (!publishLock.tryLock()) {
            return;
        }
        try {
            Instant now = clock.instant();
            List<OutboxEvent> dueEvents =
                    outboxEventRepository.findDuePendingEvents(now, PageRequest.of(0, BATCH_SIZE));
            for (OutboxEvent event : dueEvents) {
                publishSingleEvent(event);
            }
        } finally {
            publishLock.unlock();
        }
    }

    private void publishSingleEvent(OutboxEvent event) {
        int attempt = event.getAttemptCount() + 1;
        try {
            Message message = MessageBuilder.withBody(
                            event.getPayload().getBytes(StandardCharsets.UTF_8))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setMessageId(event.getEventId())
                    .setCorrelationId(event.getCorrelationId())
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .build();
            rabbitPublisher.send(
                    properties.eventsExchange(),
                    event.getEventType(),
                    message,
                    false);
            outboxEventRepository.markPublished(event.getEventId(), attempt);
        } catch (RuntimeException ex) {
            OutboxStatus nextStatus = attempt >= properties.maxRetryAttempts()
                    ? OutboxStatus.MANUAL_REVIEW
                    : OutboxStatus.PENDING;
            Instant nextRetryAt = nextStatus == OutboxStatus.PENDING
                    ? clock.instant().plusSeconds(properties.computeBackoffSeconds(attempt))
                    : null;
            outboxEventRepository.recordPublishFailure(
                    event.getEventId(),
                    nextStatus,
                    attempt,
                    nextRetryAt,
                    SAFE_OUTBOX_ERROR);
            log.warn(
                    "service=driver-service event=OutboxPublishRetry eventId={} correlationId={} attempt={} status={}",
                    event.getEventId(),
                    event.getCorrelationId(),
                    attempt,
                    nextStatus);
        }
    }
}
