CREATE TABLE IF NOT EXISTS ride_notifications (
    id CHAR(36) PRIMARY KEY,
    event_id CHAR(36) NOT NULL,
    trip_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    correlation_id CHAR(36) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    stored_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    read_at TIMESTAMP(6) NULL,
    UNIQUE KEY one_notification_per_delivery (event_id, user_id, event_type),
    INDEX notifications_by_recipient (user_id, trip_id, stored_at)
);
