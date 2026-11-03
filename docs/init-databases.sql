-- =====================================================================
-- Ride Sharing Platform Backend - Database Schema Initialization
-- Execute this script in MySQL Workbench or MySQL Shell (localhost:3306)
-- to create the 4 empty database schemas before running Flyway migrations.
-- =====================================================================

CREATE DATABASE IF NOT EXISTS user_db 
    CHARACTER SET utf8mb4 
    COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS ride_db 
    CHARACTER SET utf8mb4 
    COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS pricing_db 
    CHARACTER SET utf8mb4 
    COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS notification_db 
    CHARACTER SET utf8mb4 
    COLLATE utf8mb4_unicode_ci;

-- Optional dedicated service user (development default: root/root)
-- CREATE USER IF NOT EXISTS 'rideshare'@'%' IDENTIFIED BY 'rideshare_secret';
-- GRANT ALL PRIVILEGES ON user_db.* TO 'rideshare'@'%';
-- GRANT ALL PRIVILEGES ON ride_db.* TO 'rideshare'@'%';
-- GRANT ALL PRIVILEGES ON pricing_db.* TO 'rideshare'@'%';
-- GRANT ALL PRIVILEGES ON notification_db.* TO 'rideshare'@'%';
-- FLUSH PRIVILEGES;
