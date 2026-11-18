package vn.edu.ds03.driver.messaging.publisher;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = {"app.messaging.enabled", "app.messaging.outbox-scheduler-enabled"},
        havingValue = "true",
        matchIfMissing = true)
public class DriverOutboxScheduler {

    private final DriverOutboxPublisher outboxPublisher;

    public DriverOutboxScheduler(DriverOutboxPublisher outboxPublisher) {
        this.outboxPublisher = outboxPublisher;
    }

    @Scheduled(fixedDelayString = "${app.messaging.outbox-delay-ms:2000}")
    public void pollOutbox() {
        outboxPublisher.publishDueEvents();
    }
}
