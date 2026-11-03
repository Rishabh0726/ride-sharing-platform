# Ride Lifecycle & Distributed Concurrency Architecture
## State Machine, Lease Protocol & Zero-Row Rollback Recovery

---

## 1. Ride State Machine

```mermaid
stateDiagram-v2
    [*] --> REQUESTED: Passenger books ride with valid quoteToken
    
    REQUESTED --> CANCELLED: Passenger cancels before driver acceptance
    REQUESTED --> ACCEPTED: Driver accepts (claims lease)
    
    ACCEPTED --> CANCELLED: Passenger cancels / Driver cancels with reason
    ACCEPTED --> DRIVER_ARRIVING: Driver signals arrival at pickup
    
    DRIVER_ARRIVING --> CANCELLED: Driver cancels (no-show) / Passenger cancels
    DRIVER_ARRIVING --> IN_PROGRESS: Driver starts ride (Passenger on board)
    
    IN_PROGRESS --> COMPLETED: Driver completes trip at destination
    
    COMPLETED --> [*]
    CANCELLED --> [*]
```

### Invariants:
1. **Terminal States**: `COMPLETED` and `CANCELLED` are immutable and cannot transition to any other state.
2. **Actor Enforcement**:
   - Only `PASSENGER` (owner) can create a ride or cancel while `REQUESTED`.
   - Only the assigned `DRIVER` can advance `ACCEPTED` $\rightarrow$ `DRIVER_ARRIVING` $\rightarrow$ `IN_PROGRESS` $\rightarrow$ `COMPLETED`.
   - Either actor can cancel in valid pre-trip states (`ACCEPTED`, `DRIVER_ARRIVING`).

---

## 2. Distributed Driver Reservation Protocol

To bridge `ride_db` and `user_db` without 2PC/XA, the system uses a **two-step reservation protocol with a 30-second lease TTL**:

```mermaid
sequenceDiagram
    autonumber
    actor Driver as Driver D1
    participant RideSvc as Ride Service (:8082)
    participant UserSvc as User Service (:8081)

    Driver->>RideSvc: POST /api/v1/rides/{R1}/accept
    RideSvc->>RideSvc: Check Ride R1 (SELECT FOR UPDATE)
    
    alt Ride taken or cancelled
        RideSvc-->>Driver: 409 Conflict ("Ride no longer available")
    end

    Note over RideSvc, UserSvc: Step 1: Acquire 30s Lease
    RideSvc->>UserSvc: POST /api/v1/drivers/{D1}/reserve { rideId: R1 }
    UserSvc->>UserSvc: Atomic conditional update:\nUPDATE driver_profiles SET status='RESERVED', ...\nWHERE id=D1 AND (status='AVAILABLE' OR (status='RESERVED' AND expires_at < NOW()))
    
    alt Driver unavailable (0 rows affected)
        UserSvc-->>RideSvc: 409 Conflict ("Driver busy")
        RideSvc-->>Driver: 409 Conflict
    end
    UserSvc-->>RideSvc: 200 OK (Lease granted)

    Note over RideSvc: Step 2: Commit Local Ride Assignment
    RideSvc->>RideSvc: UPDATE rides SET status='ACCEPTED', driver_id=D1 WHERE id=R1 AND status='REQUESTED'

    alt Local update failed (competing driver won)
        RideSvc->>UserSvc: POST /api/v1/drivers/{D1}/release-reservation { rideId: R1 }
        RideSvc-->>Driver: 409 Conflict
    else Local update succeeded
        Note over RideSvc, UserSvc: Step 3: Confirm Lease
        RideSvc->>UserSvc: POST /api/v1/drivers/{D1}/confirm-ride { rideId: R1 }
        alt Confirm Succeeded
            UserSvc-->>RideSvc: 200 OK (Driver status set to ON_RIDE)
            RideSvc-->>Driver: 200 OK (Ride Accepted DTO)
        else Confirm Failed (Lease expired & claimed by another ride)
            Note over RideSvc: Step 4: Conditional Rollback
            RideSvc->>RideSvc: UPDATE rides SET status='REQUESTED', driver_id=NULL WHERE id=R1 AND driver_id=D1 AND version=v
            RideSvc-->>Driver: 409 Conflict ("Acceptance timed out. Please retry.")
        end
    end
```

---

## 3. Zero-Row Rollback Recovery Strategy

When confirmation fails, `ride-service` executes a conditional rollback:
```sql
UPDATE rides 
SET status = 'REQUESTED', 
    driver_id = NULL, 
    version = version + 1, 
    updated_at = NOW() 
WHERE id = :rideId 
  AND driver_id = :expectedDriverId 
  AND status = 'ACCEPTED' 
  AND version = :expectedVersion;
```

If this returns **0 rows affected**, the state has diverged. The system executes this 4-step recovery:
1. **Audit Log**: Emits an alert log capturing `correlationId`, `rideId`, `driverId`, and `expectedVersion`.
2. **Snapshot Inspection**: Queries `SELECT * FROM rides WHERE id = :rideId`:
   - If `status == CANCELLED`: Passenger cancelled during the window. The terminal state is preserved; no reset occurs.
   - If `driver_id != D1`: The ride was reassigned through an alternate workflow. The newer assignment is protected.
3. **Compensating Driver Cleanup**: Calls `POST /api/v1/drivers/{driverId}/release-reservation` scoped to `rideId: R1` so Driver D1 is not held.
4. **Audit Quarantine**: If state divergence cannot be resolved automatically, a row is persisted in `ride_reconciliation_audit` for operational review.

---

## 4. Idempotency Contract for Driver Release

The release operation is strictly scoped:
```sql
UPDATE driver_profiles 
SET status = 'AVAILABLE', 
    reserved_by_ride_id = NULL, 
    reservation_expires_at = NULL, 
    last_released_by_ride_id = :expectedRideId,
    version = version + 1, 
    updated_at = NOW() 
WHERE id = :driverId 
  AND status = 'RESERVED' 
  AND reserved_by_ride_id = :expectedRideId;
```

### Exact Decision Matrix:
* **`rowsAffected == 1`**: Transitioned to `AVAILABLE`. Returns `200 OK { "status": "RELEASED" }`.
* **`rowsAffected == 0` & `last_released_by_ride_id == R1`**: Idempotent duplicate retry. Version is unchanged. Returns `200 OK { "status": "ALREADY_RELEASED" }`.
* **`rowsAffected == 0` & `status == RESERVED` for `R2`**: Mismatched ride ID. R2's reservation is untouched. Returns `409 Conflict`.
* **`rowsAffected == 0` & `status == ON_RIDE`**: Active trip cannot be cleared by release. Returns `409 Conflict`.
* **`rowsAffected == 0` & `status == OFFLINE`**: Driver is offline. Returns `409 Conflict`.
* **`rowsAffected == 0` & `status == AVAILABLE` with `last_released_by_ride_id != R1`**: Stale / superseded lease. Returns `409 Conflict`.
