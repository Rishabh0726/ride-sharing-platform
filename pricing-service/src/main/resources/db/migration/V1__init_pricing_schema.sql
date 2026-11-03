-- =====================================================================
-- Migration V1: Initialize Pricing and Fare Quote Schema
-- Pricing Service (pricing_db)
-- =====================================================================

CREATE TABLE IF NOT EXISTS pricing_configurations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vehicle_type VARCHAR(50) NOT NULL,
    base_fare DECIMAL(10, 2) NOT NULL,
    rate_per_km DECIMAL(10, 2) NOT NULL,
    booking_fee DECIMAL(10, 2) NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'INR',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_pricing_config_vehicle UNIQUE (vehicle_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS fare_quotes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    quote_token VARCHAR(255) NOT NULL,
    pickup_latitude DECIMAL(10, 8) NOT NULL,
    pickup_longitude DECIMAL(11, 8) NOT NULL,
    destination_latitude DECIMAL(10, 8) NOT NULL,
    destination_longitude DECIMAL(11, 8) NOT NULL,
    distance_km DECIMAL(8, 2) NOT NULL,
    estimated_fare DECIMAL(10, 2) NOT NULL,
    vehicle_type VARCHAR(50) NOT NULL DEFAULT 'SEDAN',
    base_fare DECIMAL(10, 2) NOT NULL,
    distance_fare DECIMAL(10, 2) NOT NULL,
    booking_fee DECIMAL(10, 2) NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'INR',
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_fare_quote_token UNIQUE (quote_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_quote_expires_at ON fare_quotes (expires_at);

-- Seed approved default SEDAN pricing configuration
INSERT INTO pricing_configurations (vehicle_type, base_fare, rate_per_km, booking_fee, currency, active, created_at)
VALUES ('SEDAN', 50.00, 15.00, 15.00, 'INR', TRUE, CURRENT_TIMESTAMP);
