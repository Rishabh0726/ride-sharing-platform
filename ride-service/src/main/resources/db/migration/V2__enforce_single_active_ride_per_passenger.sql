-- =====================================================================
-- Migration V2: Enforce Single Active Ride Per Passenger
-- MySQL 8.0 / InnoDB Stored Generated Column & Unique Constraint
-- =====================================================================

ALTER TABLE rides
    ADD COLUMN active_passenger_id BIGINT
        GENERATED ALWAYS AS (
            CASE
                WHEN status IN ('REQUESTED', 'ACCEPTED', 'DRIVER_ARRIVING', 'IN_PROGRESS') THEN passenger_id
                ELSE NULL
            END
        ) STORED
        AFTER passenger_id;

ALTER TABLE rides
    ADD CONSTRAINT uq_rides_active_passenger UNIQUE (active_passenger_id);
