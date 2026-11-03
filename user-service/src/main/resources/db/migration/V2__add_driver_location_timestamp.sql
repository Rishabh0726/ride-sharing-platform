-- =====================================================================
-- Migration V2: Add dedicated location_updated_at to driver_profiles
-- Strict separation of GPS freshness from general entity lifecycle
-- =====================================================================

ALTER TABLE driver_profiles 
    ADD COLUMN location_updated_at TIMESTAMP NULL 
    AFTER current_longitude;
