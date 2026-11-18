CREATE TABLE IF NOT EXISTS driver_db.drivers (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL,
    accepting_trips BOOLEAN NOT NULL DEFAULT FALSE,
    current_trip_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    latitude DECIMAL(10,7) NULL,
    longitude DECIMAL(10,7) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_drivers_user_id UNIQUE (user_id),
    CONSTRAINT uq_drivers_current_trip_id UNIQUE (current_trip_id),
    CONSTRAINT chk_drivers_status CHECK (status IN ('OFFLINE', 'AVAILABLE', 'RESERVED', 'BUSY')),
    CONSTRAINT chk_drivers_trip_state CHECK (
        (status IN ('OFFLINE', 'AVAILABLE') AND current_trip_id IS NULL)
        OR (status IN ('RESERVED', 'BUSY') AND current_trip_id IS NOT NULL)
    ),
    CONSTRAINT chk_drivers_coordinates CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (latitude IS NOT NULL AND longitude IS NOT NULL
            AND latitude BETWEEN -90 AND 90
            AND longitude BETWEEN -180 AND 180)
    ),
    CONSTRAINT chk_drivers_available CHECK (
        status <> 'AVAILABLE'
        OR (accepting_trips = TRUE AND latitude IS NOT NULL AND longitude IS NOT NULL)
    ),
    INDEX idx_drivers_status_accepting (status, accepting_trips)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS driver_db.vehicles (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    driver_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    license_plate VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    vehicle_type VARCHAR(16) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_vehicles_driver_id UNIQUE (driver_id),
    CONSTRAINT uq_vehicles_license_plate UNIQUE (license_plate),
    CONSTRAINT chk_vehicles_type CHECK (vehicle_type IN ('BIKE', 'CAR')),
    CONSTRAINT fk_vehicles_driver FOREIGN KEY (driver_id)
        REFERENCES driver_db.drivers (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS driver_db.processed_events (
    id VARCHAR(140) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    consumer_name VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_driver_processed_events_consumer_event UNIQUE (consumer_name, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS driver_db.outbox_events (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    correlation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NULL,
    last_error VARCHAR(512) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT chk_driver_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'MANUAL_REVIEW')),
    CONSTRAINT chk_driver_outbox_attempt CHECK (attempt_count >= 0),
    INDEX idx_driver_outbox_retry (status, next_retry_at),
    INDEX idx_driver_outbox_aggregate (aggregate_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
