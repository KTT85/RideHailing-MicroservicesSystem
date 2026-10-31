USE payment_db;

-- Both queries must return zero rows.
SELECT trip_id, COUNT(*) AS payment_count
FROM payments GROUP BY trip_id HAVING COUNT(*) > 1;

SELECT payment_id, operation_type, COUNT(*) AS operation_count
FROM payment_operations GROUP BY payment_id, operation_type HAVING COUNT(*) > 1;

-- For each trip: transferred money retained = amount paid minus amount refunded.
SELECT p.trip_id, p.method, p.status, p.amount, p.refunded_amount,
       SUM(CASE WHEN o.operation_type = 'PAY' AND o.status = 'SUCCESS' THEN o.amount ELSE 0 END) AS paid,
       SUM(CASE WHEN o.operation_type = 'REFUND' AND o.status = 'SUCCESS' THEN o.amount ELSE 0 END) AS refunded
FROM payments p LEFT JOIN payment_operations o ON o.payment_id = p.id
GROUP BY p.id, p.trip_id, p.method, p.status, p.amount, p.refunded_amount;

SELECT id, payment_id, operation_type, status, attempt_count, next_retry_at, last_error
FROM payment_operations ORDER BY created_at;
SELECT event_id, event_type, correlation_id, status, attempt_count, next_retry_at, last_error
FROM outbox_events ORDER BY created_at;
