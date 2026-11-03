# Database Design & Schema Management
## Ride Sharing Platform Backend

---

## 1. Schema Isolation Principle
Each microservice maintains exclusive ownership over an independent MySQL 8 database schema on the local instance (`localhost:3306`).
- **No Cross-Service SQL Queries**: Microservices communicate only through HTTP REST / OpenFeign APIs.
- **No Shared Tables or Cross-Database Foreign Keys**: References across databases are stored as logical IDs (`passenger_id`, `driver_id`, `reference_ride_id`).
- **Managed Migrations**: Schema changes are managed strictly via Flyway (`src/main/resources/db/migration/V1__*.sql`). Hibernate is set to `spring.jpa.hibernate.ddl-auto=validate`.

---

## 2. Entity Relationship Overview

```mermaid
erDiagram
    %% USER SERVICE
    users ||--o| driver_profiles : owns
    users {
        bigint id PK
        varchar email UK
        varchar password_hash
        varchar first_name
        varchar last_name
        varchar phone_number
        varchar role "ROLE_PASSENGER, ROLE_DRIVER"
        varchar status "ACTIVE, SUSPENDED"
        datetime created_at
        datetime updated_at
    }
    driver_profiles {
        bigint id PK
        bigint user_id FK
        varchar vehicle_model
        varchar license_plate UK
        varchar status "AVAILABLE, RESERVED, ON_RIDE, OFFLINE"
        bigint reserved_by_ride_id "Logical ref"
        datetime reservation_expires_at "TTL lease"
        bigint last_released_by_ride_id "Transient idempotency marker"
        bigint active_ride_id "Logical ref"
        decimal current_latitude
        decimal current_longitude
        datetime location_updated_at "GPS ping timestamp"
        decimal rating_avg
        bigint version "Optimistic lock"
        datetime updated_at
    }

    %% PRICING SERVICE
    pricing_configurations {
        bigint id PK
        varchar vehicle_type
        decimal base_fare
        decimal rate_per_km
        decimal booking_fee
        varchar currency "INR"
        boolean active
        datetime created_at
    }
    fare_quotes {
        bigint id PK
        varchar quote_token UK
        decimal pickup_latitude
        decimal pickup_longitude
        decimal destination_latitude
        decimal destination_longitude
        decimal distance_km
        decimal estimated_fare
        varchar vehicle_type
        decimal base_fare
        decimal distance_fare
        decimal booking_fee
        varchar currency "INR"
        datetime expires_at
        datetime created_at
    }

    %% RIDE SERVICE
    rides ||--o{ ride_events : logs
    rides {
        bigint id PK
        varchar ride_uuid UK
        bigint passenger_id "Logical ref"
        bigint driver_id "Logical ref"
        varchar status "REQUESTED, ACCEPTED, DRIVER_ARRIVING, IN_PROGRESS, COMPLETED, CANCELLED"
        varchar pickup_location_name
        decimal pickup_latitude
        decimal pickup_longitude
        varchar destination_location_name
        decimal destination_latitude
        decimal destination_longitude
        decimal distance_km
        varchar fare_quote_token
        decimal final_fare
        varchar cancellation_reason
        datetime started_at
        datetime completed_at
        bigint version "Optimistic lock"
        datetime created_at
        datetime updated_at
    }
    ride_events {
        bigint id PK
        bigint ride_id FK
        varchar from_status
        varchar to_status
        bigint actor_id
        varchar actor_role
        varchar remarks
        datetime created_at
    }

    %% NOTIFICATION SERVICE
    notifications {
        bigint id PK
        bigint recipient_user_id "Logical ref"
        varchar recipient_role
        varchar event_type
        varchar title
        text message
        bigint reference_ride_id
        boolean is_read
        datetime created_at
    }
```

---

## 3. Critical Indexes & Performance Optimizations

1. **`user_db.users`**:
   - `UNIQUE INDEX idx_users_email (email)`: Enforces uniqueness on email addresses.
2. **`user_db.driver_profiles`**:
   - `UNIQUE INDEX idx_driver_license (license_plate)`: Enforces uniqueness on license plates.
   - `INDEX idx_driver_status_lookup (status, reservation_expires_at)`: Accelerates finding available drivers and evaluating expired leases.
3. **`pricing_db.fare_quotes`**:
   - `UNIQUE INDEX idx_quote_token (quote_token)`: Quick lookup for validating quote tokens.
   - `INDEX idx_quote_expires_at (expires_at)`: Accelerates expiration purge.
4. **`ride_db.rides`**:
   - `UNIQUE INDEX idx_rides_uuid (ride_uuid)`: External public identifier for ride queries.
   - `INDEX idx_rides_passenger (passenger_id, created_at DESC)`: Rapid retrieval of passenger ride history.
   - `INDEX idx_rides_driver (driver_id, status)`: Ensures fast checking of driver active rides.
5. **`notification_db.notifications`**:
   - `INDEX idx_notif_recipient (recipient_user_id, is_read, created_at DESC)`: Optimizes user in-app notification queries.
