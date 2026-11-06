ALTER TABLE trips ADD COLUMN payment_id TEXT;
ALTER TABLE outbox_events ALTER COLUMN correlation_id TYPE VARCHAR(255) USING correlation_id::text;
