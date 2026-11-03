-- =====================================================================
-- Phase 4 Milestone 5: Notification Service Schema
-- Database: notification_db (MySQL 8 / InnoDB)
-- =====================================================================

CREATE TABLE IF NOT EXISTS notifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    recipient_user_id BIGINT NOT NULL,
    recipient_role VARCHAR(50) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    title VARCHAR(100) NOT NULL,
    message TEXT NOT NULL,
    reference_ride_id BIGINT NULL,
    amount DECIMAL(10, 2) NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    read_at DATETIME NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT uk_notifications_idempotency_key UNIQUE (idempotency_key),
    INDEX idx_notifications_recipient (recipient_user_id, is_read, created_at DESC),
    INDEX idx_notifications_ride (reference_ride_id, event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
