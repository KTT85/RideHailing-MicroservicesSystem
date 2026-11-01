package vn.edu.ds03.payment.worker;

import vn.edu.ds03.payment.messaging.OutboxPublisher;
import vn.edu.ds03.payment.service.PaymentService;

import java.time.*;
import org.slf4j.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "app.payment.workers-enabled", havingValue = "true", matchIfMissing = true)
public class PaymentWorkers {
    private static final Logger log = LoggerFactory.getLogger(PaymentWorkers.class);
    private final JdbcTemplate jdbc;
    private final PaymentService service;
    private final OutboxPublisher outbox;
    private final Clock clock;
    public PaymentWorkers(JdbcTemplate jdbc, PaymentService service, OutboxPublisher outbox, Clock clock) {
        this.jdbc = jdbc; this.service = service; this.outbox = outbox; this.clock = clock;
    }
    @Scheduled(fixedDelayString = "${app.payment.worker-delay-ms:1000}")
    public void operations() {
        var due = jdbc.queryForList("""
            SELECT id FROM payment_operations WHERE status='PENDING'
            AND (next_retry_at IS NULL OR next_retry_at<=?) ORDER BY created_at LIMIT 50
            """, String.class, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        for (String id : due) {
            try { service.execute(id); }
            catch (RuntimeException ex) { log.error("service=payment-service event=WorkerError operationId={} result=rolled_back", id, ex); }
        }
    }
    @Scheduled(fixedDelayString = "${app.payment.worker-delay-ms:1000}")
    public void outbox() { outbox.publishDue(); }
}
