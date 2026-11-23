CREATE TABLE IF NOT EXISTS trip_payment_terms (
    trip_id CHAR(36) PRIMARY KEY,
    passenger_id CHAR(36) NOT NULL,
    method VARCHAR(8) NOT NULL,
    amount DECIMAL(15,2) NOT NULL
);
CREATE TABLE IF NOT EXISTS trip_payment_outbox (
    seq BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id CHAR(36) NOT NULL UNIQUE,
    trip_id CHAR(36) NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    body TEXT NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (trip_id, event_type),
    INDEX (published, seq)
);
CREATE TABLE IF NOT EXISTS trip_payment_results (
    event_id CHAR(36) PRIMARY KEY,
    trip_id CHAR(36) NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    body JSON NOT NULL,
    INDEX (trip_id)
);
