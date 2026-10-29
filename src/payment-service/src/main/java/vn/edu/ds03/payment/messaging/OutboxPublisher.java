package vn.edu.ds03.payment.messaging;

import vn.edu.ds03.payment.config.MessagingConfiguration;
import vn.edu.ds03.payment.config.PaymentSettings;

import java.nio.charset.StandardCharsets;
import java.time.*;
import org.slf4j.*;
import org.springframework.amqp.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcTemplate jdbc;
    private final ConfirmedPublisher publisher;
    private final PaymentSettings settings;
    private final Clock clock;
    public OutboxPublisher(JdbcTemplate jdbc, ConfirmedPublisher publisher, PaymentSettings settings, Clock clock) {
        this.jdbc = jdbc; this.publisher = publisher; this.settings = settings; this.clock = clock;
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    public void publishDue() {
        var rows = jdbc.queryForList("""
            SELECT event_id,event_type,correlation_id,payload,attempt_count FROM outbox_events
            WHERE status='PENDING' AND (next_retry_at IS NULL OR next_retry_at<=?)
            ORDER BY created_at LIMIT 50
            """, now());
        for (var row : rows) {
            String id = (String) row.get("event_id");
            int attempt = ((Number) row.get("attempt_count")).intValue() + 1;
            try {
                Message message = MessageBuilder.withBody(row.get("payload").toString().getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setMessageId(id).setCorrelationId((String) row.get("correlation_id"))
                        .setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
                publisher.send(MessagingConfiguration.EVENTS, (String) row.get("event_type"), message);
                jdbc.update("""
                    UPDATE outbox_events SET status='PUBLISHED',attempt_count=?,next_retry_at=NULL,last_error=NULL
                    WHERE event_id=? AND status='PENDING'
                    """, attempt, id);
            } catch (RuntimeException ex) {
                String status = attempt >= settings.maxAttempts() ? "MANUAL_REVIEW" : "PENDING";
                jdbc.update("""
                    UPDATE outbox_events SET status=?,attempt_count=?,next_retry_at=?,last_error=?
                    WHERE event_id=? AND status='PENDING'
                    """, status, attempt, "PENDING".equals(status) ? now().plusSeconds(settings.backoff(attempt)) : null,
                        "Publish not confirmed/routed; inspect broker and retry via internal API", id);
                log.warn("service=payment-service event=OutboxRetry eventId={} correlationId={} attempt={} result={}",
                        id, row.get("correlation_id"), attempt, status);
            }
        }
    }
}
