-- Preserve correlation across worker retries and process restarts.
ALTER TABLE payment_operations ADD COLUMN correlation_id CHAR(36) NULL;
