-- CreateTable
CREATE TABLE IF NOT EXISTS `trips` (
    `id` CHAR(36) NOT NULL,
    `passenger_id` CHAR(36) NOT NULL,
    `driver_id` CHAR(36) NULL,
    `status` ENUM('REQUESTED', 'MATCHING', 'DRIVER_ASSIGNED', 'MATCHED', 'ACCEPTED', 'STARTING', 'REJECTED', 'IN_PROGRESS', 'COMPLETED', 'PAYMENT_PENDING', 'PAYMENT_FAILED', 'COMPENSATING', 'CANCELLED') NOT NULL DEFAULT 'REQUESTED',
    `pickup` JSON NOT NULL,
    `dropoff` JSON NOT NULL,
    `fare` DOUBLE NULL,
    `payment_id` VARCHAR(191) NULL,
    `idempotency_key` VARCHAR(255) NOT NULL,
    `requested_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `accepted_at` DATETIME(3) NULL,
    `started_at` DATETIME(3) NULL,
    `completed_at` DATETIME(3) NULL,
    `cancelled_at` DATETIME(3) NULL,
    `cancel_reason` VARCHAR(191) NULL,
    `correlation_id` CHAR(36) NULL,
    `vehicle_type` VARCHAR(191) NULL,
    `payment_method` VARCHAR(191) NULL,
    `payment_state` VARCHAR(191) NULL,
    `driver_released` BOOLEAN NOT NULL DEFAULT false,
    `driver_user_id` CHAR(36) NULL,
    `demo_fault` VARCHAR(191) NULL,
    `demo_fault_at` DATETIME(3) NULL,

    UNIQUE INDEX `trips_idempotency_key_key`(`idempotency_key`),
    INDEX `trips_passenger_id_requested_at_idx`(`passenger_id`, `requested_at`),
    INDEX `trips_driver_id_requested_at_idx`(`driver_id`, `requested_at`),
    INDEX `trips_status_idx`(`status`),
    PRIMARY KEY (`id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

-- CreateTable
CREATE TABLE IF NOT EXISTS `outbox_events` (
    `id` CHAR(36) NOT NULL,
    `aggregate_id` CHAR(36) NOT NULL,
    `event_type` VARCHAR(100) NOT NULL,
    `payload` JSON NOT NULL,
    `correlation_id` VARCHAR(255) NOT NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `published_at` DATETIME(3) NULL,

    INDEX `outbox_events_published_at_created_at_idx`(`published_at`, `created_at`),
    PRIMARY KEY (`id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

-- CreateTable
CREATE TABLE IF NOT EXISTS `processed_events` (
    `event_id` CHAR(36) NOT NULL,
    `processed_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (`event_id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;

-- CreateTable
CREATE TABLE IF NOT EXISTS `trip_workflow_lock` (
    `id` INTEGER NOT NULL,

    PRIMARY KEY (`id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;


INSERT IGNORE INTO trip_workflow_lock (id) VALUES (1);
