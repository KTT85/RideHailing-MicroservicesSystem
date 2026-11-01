package vn.edu.ds03.payment.service;

import vn.edu.ds03.payment.exception.InvalidCommand;
import vn.edu.ds03.payment.repository.OperationRepository;
import vn.edu.ds03.payment.model.Payment;
import vn.edu.ds03.payment.dto.PaymentEvent;
import vn.edu.ds03.payment.model.PaymentOperation;
import vn.edu.ds03.payment.repository.PaymentRepository;
import vn.edu.ds03.payment.config.PaymentSettings;

import java.time.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String CONSUMER = "payment.commands.v1";
    private final PaymentRepository payments;
    private final OperationRepository operations;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PaymentSettings settings;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, OperationRepository operations,
                          JdbcTemplate jdbc, ObjectMapper json, PaymentSettings settings, Clock clock) {
        this.payments = payments; this.operations = operations; this.jdbc = jdbc;
        this.json = json; this.settings = settings; this.clock = clock;
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }

    @Transactional
    public void accept(PaymentEvent event) {
        event.validate();
        if (!remember(event.eventId())) return;
        var command = event.payload();
        if ("TripCompleted".equals(event.eventType()) && "TRANSFER".equals(command.method())) {
            return;
        }
        jdbc.update("""
            INSERT INTO payments (id, trip_id, passenger_id, method, amount, currency,
                status, refunded_amount, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
            ON DUPLICATE KEY UPDATE trip_id = VALUES(trip_id)
            """, UUID.randomUUID().toString(), command.tripId(), command.passengerId(),
                command.method(), command.amount(), command.currency(), now(), now());
        Payment payment = payments.lockByTripId(command.tripId()).orElseThrow();
        if (!payment.getPassengerId().equals(command.passengerId()) || !payment.getMethod().equals(command.method())
                || payment.getAmount().compareTo(command.amount()) != 0 || !payment.getCurrency().equals(command.currency()))
            throw new InvalidCommand("Command conflicts with immutable payment details");
        String type = event.operationType();
        if (type != null) {
            var existing = operations.findByPaymentIdAndOperationType(payment.getId(), type);
            if (existing.isEmpty()) {
                PaymentOperation op = new PaymentOperation();
                op.setId(UUID.randomUUID().toString()); op.setPaymentId(payment.getId());
                op.setOperationType(type); op.setIdempotencyKey(command.idempotencyKey());
                op.setAmount(payment.getAmount()); op.setStatus("PENDING");
                op.setCorrelationId(event.correlationId()); op.setCreatedAt(now()); op.setNextRetryAt(now());
                operations.save(op);
            } else if (!existing.get().getIdempotencyKey().equals(command.idempotencyKey())) {
                throw new InvalidCommand("Conflicting idempotency key");
            }
        }
        log.info("service=payment-service event={} tripId={} correlationId={} result=accepted",
                event.eventType(), command.tripId(), event.correlationId());
    }

    private boolean remember(String eventId) {
        return jdbc.update("""
            INSERT IGNORE INTO processed_events (consumer_name,event_id,processed_at) VALUES (?,?,?)
            """, CONSUMER, eventId, now()) == 1;
    }

    @Transactional
    public void execute(String operationId) {
        List<String> trips = jdbc.queryForList("""
            SELECT p.trip_id FROM payments p JOIN payment_operations o ON p.id=o.payment_id WHERE o.id=?
            """, String.class, operationId);
        if (trips.isEmpty()) return;
        Payment p = payments.lockByTripId(trips.getFirst()).orElseThrow();
        // Locking reads see the latest committed state under MySQL REPEATABLE READ.
        // A plain findById could reuse the snapshot taken before waiting for the payment lock.
        PaymentOperation op = operations.lockById(operationId).orElseThrow();
        if (!"PENDING".equals(op.getStatus()) || (op.getNextRetryAt() != null && op.getNextRetryAt().isAfter(now()))) return;
        op.setAttemptCount(op.getAttemptCount() + 1);
        if ("REFUND".equals(op.getOperationType())) {
            var pay = operations.findByPaymentIdAndOperationType(p.getId(), "PAY");
            if (pay.isEmpty() || !"SUCCESS".equals(pay.get().getStatus())) {
                retry(p, op, "Awaiting successful PAY; no refund performed");
                return;
            }
            p.setStatus("REFUND_PENDING");
        }
        PaymentSettings.Outcome outcome = switch (op.getOperationType()) {
            case "PAY" -> settings.simulator().pay();
            case "REFUND" -> settings.simulator().refund();
            case "CONFIRM_CASH" -> PaymentSettings.Outcome.SUCCESS;
            default -> throw new InvalidCommand("Unsupported persisted operation");
        };
        if (!"CONFIRM_CASH".equals(op.getOperationType())
                && (op.getAttemptCount() <= settings.simulator().transientAttempts()
                    || outcome == PaymentSettings.Outcome.TRANSIENT)) {
            retry(p, op, "Simulated temporary failure (no money effect)");
            return;
        }
        if (outcome == PaymentSettings.Outcome.FAILED) {
            if ("REFUND".equals(op.getOperationType())) {
                retry(p, op, "Simulated refund failure (no money effect)");
            } else {
                op.setStatus("FAILED"); op.setCompletedAt(now()); op.setNextRetryAt(null);
                op.setLastError("Simulated payment declined"); p.setStatus("FAILED"); p.setUpdatedAt(now());
                emit(p, op, "PaymentFailed");
            }
            return;
        }

        if ("REFUND".equals(op.getOperationType())) {
            p.setStatus("REFUNDED"); p.setRefundedAmount(p.getAmount()); p.setRefundedAt(now());
        } else {
            p.setStatus("SUCCESS"); p.setPaidAt(now());
        }
        p.setUpdatedAt(now()); op.setStatus("SUCCESS"); op.setCompletedAt(now());
        op.setNextRetryAt(null); op.setLastError(null);
        emit(p, op, "REFUND".equals(op.getOperationType()) ? "PaymentRefunded" : "PaymentSucceeded");
    }

    private void retry(Payment p, PaymentOperation op, String reason) {
        op.setLastError(reason);
        if (op.getAttemptCount() >= settings.maxAttempts()) {
            op.setStatus("MANUAL_REVIEW"); op.setNextRetryAt(null);
            if ("REFUND".equals(op.getOperationType())) {
                if (p.getPaidAt() != null) p.setStatus("REFUND_FAILED");
                emit(p, op, "PaymentRefundFailed");
            } else {
                emit(p, op, "PaymentReviewRequired");
            }
        } else {
            op.setNextRetryAt(now().plusSeconds(settings.backoff(op.getAttemptCount())));
        }
        p.setUpdatedAt(now());
        log.warn("service=payment-service event=OperationRetry operationId={} correlationId={} attempt={} result={}",
                op.getId(), op.getCorrelationId(), op.getAttemptCount(), op.getStatus());
    }

    private void emit(Payment p, PaymentOperation op, String type) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tripId", p.getTripId()); payload.put("passengerId", p.getPassengerId());
        payload.put("paymentId", p.getId()); payload.put("operationId", op.getId());
        payload.put("operationType", op.getOperationType()); payload.put("idempotencyKey", op.getIdempotencyKey());
        payload.put("method", p.getMethod()); payload.put("amount", p.getAmount()); payload.put("currency", p.getCurrency());
        payload.put("status", p.getStatus()); payload.put("operationStatus", op.getStatus());
        payload.put("refundedAmount", p.getRefundedAmount());
        String correlation = op.getCorrelationId() != null ? op.getCorrelationId() : p.getTripId();
        var envelope = Map.of("eventId", eventId, "eventType", type, "correlationId", correlation,
                "timestamp", clock.instant().toString(), "payload", payload);
        jdbc.update("""
            INSERT INTO outbox_events (event_id,aggregate_id,event_type,correlation_id,payload,
                status,attempt_count,next_retry_at,created_at) VALUES (?,?,?,?,?,'PENDING',0,?,?)
            """, eventId, p.getId(), type, correlation, json.writeValueAsString(envelope), now(), now());
        log.info("service=payment-service event={} tripId={} correlationId={} result={}",
                type, p.getTripId(), correlation, p.getStatus());
    }
}
