package vn.edu.ds03.trip.payment;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class TripPaymentGateway {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public TripPaymentGateway(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    /** Called inside the owning Trip lifecycle transaction after authorization and state checks. */
    @Transactional
    public UUID enqueue(PaymentContract.Command command, PaymentContract.Terms terms, UUID correlationId) {
        var event = PaymentContract.command(command, terms, correlationId);
        String trip = terms.tripId().toString();
        db.update("""
            INSERT INTO trip_payment_terms(trip_id, passenger_id, method, amount) VALUES (?,?,?,?)
            ON DUPLICATE KEY UPDATE trip_id=trip_id
            """, trip, terms.passengerId().toString(), terms.method().name(), terms.amount());
        requireSameTerms(terms);
        db.update("""
            INSERT INTO trip_payment_outbox(event_id,trip_id,event_type,body) VALUES (?,?,?,?)
            ON DUPLICATE KEY UPDATE event_id=event_id
            """, event.eventId().toString(), trip, event.eventType(), json.writeValueAsString(event));
        return UUID.fromString(db.queryForObject("""
            SELECT event_id FROM trip_payment_outbox WHERE trip_id=? AND event_type=?
            """, String.class, trip, event.eventType()));
    }

    void requireSameTerms(PaymentContract.Terms terms) {
        var matches = db.queryForObject("""
            SELECT COUNT(*) FROM trip_payment_terms WHERE trip_id=? AND passenger_id=? AND method=? AND amount=?
            """, Integer.class, terms.tripId().toString(), terms.passengerId().toString(),
            terms.method().name(), terms.amount());
        if (!Integer.valueOf(1).equals(matches)) throw new IllegalArgumentException("Payment terms differ from trip");
    }
}
