-- Initial MySQL 8.4 schema. Timestamps are UTC, written by the application.
-- IF NOT EXISTS supports repeat startup; future schema changes need migrations.
CREATE TABLE IF NOT EXISTS payments (
    id CHAR(36) NOT NULL,
    trip_id CHAR(36) NOT NULL,
    passenger_id CHAR(36) NOT NULL,
    method VARCHAR(16) NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'VND',
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    refunded_amount DECIMAL(19,2) NOT NULL DEFAULT 0,
    paid_at DATETIME(6) NULL,
    refunded_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_payments_trip UNIQUE (trip_id),
    CONSTRAINT ck_payments_method CHECK (method IN ('CASH', 'TRANSFER')),
    CONSTRAINT ck_payments_status CHECK (status IN
        ('PENDING', 'SUCCESS', 'FAILED', 'REFUND_PENDING', 'REFUNDED', 'REFUND_FAILED')),
    CONSTRAINT ck_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_payments_refund CHECK (
        refunded_amount = 0 OR (method = 'TRANSFER' AND refunded_amount = amount)),
    INDEX ix_payments_passenger_created (passenger_id, created_at),
    INDEX ix_payments_status_updated (status, updated_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS payment_operations (
    id CHAR(36) NOT NULL,
    payment_id CHAR(36) NOT NULL,
    operation_type VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NULL,
    last_error TEXT NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_operations_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT uq_operations_key UNIQUE (idempotency_key),
    CONSTRAINT uq_operations_payment_type UNIQUE (payment_id, operation_type),
    CONSTRAINT ck_operations_type CHECK (operation_type IN ('PAY', 'REFUND', 'CONFIRM_CASH')),
    CONSTRAINT ck_operations_status CHECK (status IN
        ('PENDING', 'PROCESSING', 'SUCCESS', 'FAILED', 'MANUAL_REVIEW')),
    CONSTRAINT ck_operations_amount CHECK (amount > 0),
    CONSTRAINT ck_operations_attempt CHECK (attempt_count >= 0),
    INDEX ix_operations_retry (status, next_retry_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS processed_events (
    consumer_name VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (consumer_name, event_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS outbox_events (
    event_id CHAR(36) NOT NULL,
    aggregate_id CHAR(36) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    correlation_id CHAR(36) NOT NULL,
    payload LONGTEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NULL,
    last_error TEXT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'MANUAL_REVIEW')),
    CONSTRAINT ck_outbox_attempt CHECK (attempt_count >= 0),
    INDEX ix_outbox_retry (status, next_retry_at),
    INDEX ix_outbox_aggregate (aggregate_id, created_at)
) ENGINE=InnoDB;

ALTER TABLE payment_operations ADD COLUMN correlation_id CHAR(36) NULL;
