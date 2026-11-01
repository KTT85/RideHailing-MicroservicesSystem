package vn.edu.ds03.payment.service;

import vn.edu.ds03.payment.repository.OperationRepository;
import vn.edu.ds03.payment.dto.OperationView;
import vn.edu.ds03.payment.model.Payment;
import vn.edu.ds03.payment.dto.PaymentEvent;
import vn.edu.ds03.payment.repository.PaymentRepository;
import vn.edu.ds03.payment.dto.PaymentView;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentQueries {
    private final PaymentRepository payments;
    private final OperationRepository operations;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public PaymentQueries(PaymentRepository payments, OperationRepository operations, JdbcTemplate jdbc, Clock clock) {
        this.payments = payments; this.operations = operations; this.jdbc = jdbc; this.clock = clock;
    }
    private static Instant utc(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC); }

    @Transactional(readOnly = true)
    public PaymentView byTrip(String tripId, String userId, boolean privileged) {
        PaymentEvent.uuid(tripId);
        Payment p = payments.findByTripId(tripId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not created"));
        if (!privileged && !p.getPassengerId().equals(userId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not the payment owner");
        var ops = operations.findAllByPaymentId(p.getId()).stream().map(o -> new OperationView(o.getId(),
                o.getOperationType(), o.getStatus(), o.getAttemptCount(), utc(o.getNextRetryAt()), o.getLastError())).toList();
        return new PaymentView(p.getId(), p.getTripId(), p.getPassengerId(), p.getMethod(), p.getAmount(), p.getCurrency(), p.getStatus(),
                p.getRefundedAmount(), utc(p.getPaidAt()), utc(p.getRefundedAt()), ops);
    }

    @Transactional
    public void retryOperation(String id) {
        PaymentEvent.uuid(id);
        var op = operations.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String trip = jdbc.queryForObject("SELECT trip_id FROM payments WHERE id=?", String.class, op.getPaymentId());
        payments.lockByTripId(trip).orElseThrow();
        int updated = jdbc.update("""
            UPDATE payment_operations SET status='PENDING',attempt_count=0,next_retry_at=?,last_error=NULL
            WHERE id=? AND status='MANUAL_REVIEW'
            """, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), id);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only MANUAL_REVIEW can be resumed");
    }

    @Transactional
    public void retryOutbox(String id) {
        PaymentEvent.uuid(id);
        int updated = jdbc.update("""
            UPDATE outbox_events SET status='PENDING',attempt_count=0,next_retry_at=?,last_error=NULL
            WHERE event_id=? AND status='MANUAL_REVIEW'
            """, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), id);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only MANUAL_REVIEW can be resumed");
    }
}
