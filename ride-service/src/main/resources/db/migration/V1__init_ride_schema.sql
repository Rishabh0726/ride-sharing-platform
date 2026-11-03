-- Phase 4 Milestone 4: Ride Lifecycle & Distributed State Machine Schema
-- Schema: ride_db

CREATE TABLE rides (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ride_uuid VARCHAR(36) NOT NULL,
    passenger_id BIGINT NOT NULL,
    driver_id BIGINT NULL,
    status VARCHAR(30) NOT NULL,
    pickup_location_name VARCHAR(255) NULL,
    pickup_latitude DECIMAL(10, 8) NOT NULL,
    pickup_longitude DECIMAL(11, 8) NOT NULL,
    destination_location_name VARCHAR(255) NULL,
    destination_latitude DECIMAL(10, 8) NOT NULL,
    destination_longitude DECIMAL(11, 8) NOT NULL,
    distance_km DECIMAL(8, 2) NOT NULL,
    fare_quote_token VARCHAR(100) NOT NULL,
    estimated_fare DECIMAL(10, 2) NOT NULL,
    final_fare DECIMAL(10, 2) NULL,
    base_fare DECIMAL(10, 2) NOT NULL,
    distance_fare DECIMAL(10, 2) NOT NULL,
    booking_fee DECIMAL(10, 2) NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'INR',
    vehicle_type VARCHAR(30) NOT NULL DEFAULT 'SEDAN',
    cancellation_reason VARCHAR(255) NULL,
    cancelled_by VARCHAR(30) NULL,
    accepted_at DATETIME NULL,
    arrived_at DATETIME NULL,
    started_at DATETIME NULL,
    completed_at DATETIME NULL,
    cancelled_at DATETIME NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uq_rides_uuid UNIQUE (ride_uuid),
    CONSTRAINT uq_rides_fare_quote_token UNIQUE (fare_quote_token),
    INDEX idx_rides_passenger (passenger_id, created_at DESC),
    INDEX idx_rides_driver (driver_id, status),
    INDEX idx_rides_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ride_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ride_id BIGINT NOT NULL,
    from_status VARCHAR(30) NULL,
    to_status VARCHAR(30) NOT NULL,
    actor_id BIGINT NOT NULL,
    actor_role VARCHAR(50) NOT NULL,
    remarks VARCHAR(255) NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT fk_ride_events_ride FOREIGN KEY (ride_id) REFERENCES rides(id) ON DELETE CASCADE,
    INDEX idx_ride_events_ride_id (ride_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ride_reconciliation_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ride_id BIGINT NOT NULL,
    driver_id BIGINT NOT NULL,
    operation VARCHAR(50) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL,
    error_message TEXT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_reconciliation_status (status, retry_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
