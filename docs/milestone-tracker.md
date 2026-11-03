# Project Milestone Tracker
## Ride Sharing Platform Backend

---

## Phase 4: Implementation Roadmap & Milestone Status

| Phase / Milestone | Service | Status | Verified Tests | Completion Date |
| :--- | :--- | :---: | :---: | :---: |
| **Phase 4 — Milestone 1**: User Management Service & JWT Authentication | `user-service` | **COMPLETE** | 38 / 38 Passing | 2026-10-21 |
| **Phase 4 — Milestone 2**: Driver Availability, Proximity Discovery & Distributed Lease Protocol | `user-service` | **COMPLETE** | 79 / 79 Passing (cumulative) | 2026-10-21 |
| **Phase 4 — Milestone 3**: Pricing Service & Fare Quotes | `pricing-service` | **COMPLETE** | 29 / 29 Passing (108 cumulative) | 2026-10-21 |
| **Phase 4 — Milestone 4**: Ride Lifecycle & Distributed State Machine | `ride-service` | **COMPLETE** | 51 / 51 Passing (159 cumulative) | 2026-10-21 |
| **Phase 4 — Milestone 5**: Notification Service & Event Persistence | `notification-service` | **COMPLETE** | 25 / 25 Passing (184 cumulative) | 2026-10-21 |
| **Phase 4 — Milestone 6**: API Gateway & System Integration | `api-gateway` | **COMPLETE** | 76 / 76 Passing (260 cumulative) | 2026-10-21 |
| **Phase 4 — Milestone 7**: End-to-End System Integration & Workflow Verification | `system-integration-tests` | **COMPLETE** | 19 / 19 Passing (279 cumulative) | 2026-10-21 |
| **Milestone 8 — Phase 1**: Documentation, Config Harmonization & Secret Hardening | Root & Gateway | **COMPLETE** | 279 / 279 Passing (reconciled) | 2026-10-22 |
| **Milestone 8 — Phase 2A**: Actuator Health Checks & Probes | All 5 Services | **COMPLETE** | 297 / 297 Passing (+18 tests) | 2026-10-22 |
| **Milestone 8 — Phase 2B**: Containerization (Dockerfiles & Compose) | All 5 Services & Compose | **COMPLETE** | 297 / 297 Passing (baseline preserved) | 2026-10-22 |

---

## Test Evidence Reconciliation & Audit Record

### Historical Test Reports vs. Freshly Verified Tests:
- **Verified Tests in Repository**: **297 passing tests** across 43 Surefire XML test report files in `*/target/surefire-reports` (0 failures, 0 errors, 0 skips).
  - `user-service`: 83 tests (+4 actuator health & security tests)
  - `pricing-service`: 33 tests (+4 actuator health & security tests)
  - `ride-service`: 56 tests (+5 actuator health & security tests)
  - `notification-service`: 30 tests (+5 actuator health & security tests)
  - `api-gateway`: 76 tests (probes enabled, show-details: never)
  - `system-integration-tests`: 19 tests
- **Reconciliation Note**: The milestone tracker originally recorded **259** tests at the close of Milestone 7, **279** after Milestone 8 Phase 1, and now **297** in Phase 2A with the introduction of Spring Boot Actuator health checks (`/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`) and security access-control verification across all downstream services.
- **Freshly Verified Tests (Phase 2A Audit & Hardening)**: Full reactor run `mvnw.cmd test` passed all 297 tests cleanly across all 7 projects. All tests run exclusively against isolated in-memory H2 databases with zero local MySQL port 3306 contact.

---

## Detailed Milestone 7 Status & Deferred Items

### Scope Delivered:
- **Dedicated E2E Verification Module**: Standalone Maven module `system-integration-tests` registered in root reactor, providing an automated multi-service cluster test harness (`SystemIntegrationCluster`) running all 5 microservices (`user-service`, `pricing-service`, `ride-service`, `notification-service`, `api-gateway`) on dynamic ephemeral ports with isolated in-memory H2 databases and zero local MySQL 8 contact.
- **Driver Operational Lease Identity Contract Resolution**:
  - Standardized driver lease endpoints (`/reserve`, `/confirm-ride`, `/release-reservation`, `/complete-ride`) on `userId`, resolving the associated driver profile exclusively via `driverProfileRepository.findByUserId(driverUserId)`.
  - Resolved profile mismatch where user ID and driver-profile ID diverge under auto-increment identity generation.
  - Added collision prevention regression tests proving that user IDs matching unrelated driver-profile IDs throw `404 Not Found` without modifying other drivers' state.
- **Streaming Chunked Request Payload Limit Enforcement**:
  - Implemented non-blocking reactive filter `StreamingRequestSizeGlobalFilter` at order `-85` in `api-gateway`.
  - For chunked requests (where `Content-Length` is absent), counts bytes streamed through Netty `DataBuffer` chunks; when exceeding 2 MiB (2,097,152 bytes), immediately releases offending `DataBuffer` via `DataBufferUtils.release(dataBuffer)` and emits HTTP 413 `Payload Too Large`.
  - Truncates downstream TCP transport connection, preventing downstream microservices from receiving completed HTTP requests or processing oversized chunked bodies.
  - Verified with automated tests covering exact 2 MiB boundary, under-limit chunked payloads, and oversized chunked transfers.
- **Full Lifecycle End-to-End Verification**:
  - Executed ordered 18-step test suite `EndToEndRideLifecycleIntegrationTest` through API Gateway:
    1. Driver registration (`POST /api/v1/auth/register` with role `DRIVER`) -> `201 Created`.
    2. Passenger registration (`POST /api/v1/auth/register` with role `PASSENGER`) -> `201 Created`.
    3. Driver toggles availability to `AVAILABLE` with location coordinates -> `200 OK`.
    4. Passenger discovers nearby available driver -> `200 OK` (verifying vehicle model and license plate).
    5. Passenger requests fare estimation quote token -> `200 OK` (verifying quote token and fare calculation).
    6. Passenger creates ride using valid quote token -> `201 Created`.
    7. Driver accepts ride (triggering internal distributed lease protocol) -> `200 OK`.
    8. Driver arrives at pickup location (`DRIVER_ARRIVING`) -> `200 OK`.
    9. Driver starts ride (`IN_PROGRESS`) -> `200 OK`.
    10. Driver completes ride (`COMPLETED` and driver returned to `AVAILABLE`) -> `200 OK`.
    11. Passenger verifies 5 lifecycle event notifications (`REQUESTED`, `ASSIGNED`, `ARRIVING`, `STARTED`, `COMPLETED`), unread count tracking (5 initially, marks all read, unread count becomes 0) -> `200 OK`.
    12. Driver verifies persisted notifications (`RIDE_ACCEPTED`, `RIDE_COMPLETED`) -> `200 OK`.
    13. Negative authorization: Passenger attempting to accept a ride is rejected with `403 Forbidden`.
    14. Negative authorization: Driver attempting to create a ride is rejected with `403 Forbidden`.
    15. Negative state machine conflict: Attempting to cancel an already completed ride is rejected with `409 Conflict`.
    16. Perimeter defense: External call to internal driver lease endpoint `/api/v1/drivers/{id}/reserve` blocked with `403 Forbidden` RFC 7807 problem detail.
    17. Perimeter defense: External call to internal notification ingestion endpoint `/api/v1/notifications` blocked with `403 Forbidden` RFC 7807 problem detail.
    18. Notification tenant isolation: Passenger attempting to mark a driver's notification as read is rejected with `404 Not Found`.
- **Reactor Test Results**: Full reactor suite builds and tests cleanly with 259 / 259 passing tests, 0 failures, 0 errors, and 0 skips across all 7 projects.

### Explicitly Deferred Items & Verification Boundaries:
1. **Physical MySQL 8 Verification (DEFERRED)**:
   - All tests executed against isolated in-memory H2 databases (`MODE=MySQL`).
   - Local MySQL (`MySQL80` on port 3306) has not been connected to, modified, or populated with physical schema migrations.
2. **Docker Compose & Deployment Topology (DEFERRED)**:
   - Containerization, orchestration, and Docker Compose configurations remain scheduled for subsequent deployment milestones.
3. **Production Readiness Disclaimer**:
   - Production readiness is **NOT** marked as verified at this milestone. Clustered multi-node topologies, external caching, distributed rate-limiting, and third-party push notifications remain to be verified in operational staging environments.

---

## Detailed Milestone 6 Status & Deferred Items

### Scope Delivered:
- **Module Architecture**: Reactive Spring Cloud Gateway Server WebFlux module `api-gateway` running on port 8080 on Netty (zero Servlet/Spring MVC runtime), registered in root `pom.xml`.
- **Authoritative 2 MiB Request Limit**: Enforces 2 MiB request body ceiling globally using Spring Cloud Gateway's `RequestSize` filter factory (`f.setRequestSize(2097152L)`) and WebFlux `spring.codec.max-in-memory-size=2MB`. Requests exceeding 2 MiB are rejected with RFC 7807 `413 Payload Too Large` without consuming or leaking Netty DataBuffers.
- **Path Sanitization & Edge Defense**: `PathSanitizationGlobalFilter` (Order -100) rejects malformed percent-encoding, encoded separators (`%2F`, `%5C`), double encoding (`%25`), dot segment traversal (`/../`, `/./`), duplicate slashes (`//`), and null/control bytes with RFC 7807 `400 Bad Request`.
- **Internal Endpoint Block Filter**: `InternalEndpointBlockFilter` (Order -80) intercepts all internal microservice operational RPC endpoints before downstream routing with RFC 7807 `403 Forbidden`:
  - Driver distributed lease operations: `POST /api/v1/drivers/{id}/(reserve|confirm-ride|release-reservation|complete-ride)`
  - Pricing quote snapshots: `GET /api/v1/pricing/quotes/**`
  - Internal notification ingestion: `POST /api/v1/notifications`
  - Trailing slash and case insensitivity are enforced on canonical paths (`rawPath.toLowerCase(Locale.ROOT)`).
- **Edge Authentication & Public Allowlist**: `GatewayAuthenticationFilter` (Order -70):
  - Public allowlist: `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, `GET /actuator/health`, `GET /actuator/info`.
  - Actuator isolation: Blocks non-whitelisted actuator endpoints (`/actuator/env`, `/actuator/beans`, etc.) with `404 Not Found`.
  - Token validation: Validates Bearer JWT signature and expiry using JJWT 0.12.6. Missing/invalid tokens return `401 Unauthorized`.
  - Rejection of `ROLE_INTERNAL_SERVICE`: External callers supplying `role: ROLE_INTERNAL_SERVICE` tokens are rejected with `403 Forbidden` at the edge.
  - Verbatim forwarding: Passes original `Authorization: Bearer <token>` downstream untouched for independent downstream security enforcement.
- **Identity Header Sanitization**: `UntrustedHeaderStrippingFilter` (Order -90) strips caller-supplied `X-User-Id`, `X-User-Role`, `X-User-Email`, `X-User-Phone`, and `X-Service-Name` headers before proxying. Does not inject unnecessary `X-User-*` headers.
- **Bounded Authentication Rate Limiting**: `AuthRateLimiterFilter` (Order -60) + `InMemoryRateLimiter`:
  - Enforces 20 req/min per IP on public registration and login endpoints.
  - Concurrency-safe atomic window tracking via `ConcurrentHashMap.compute(...)`.
  - Eviction safety: Periodic cleaner only purges expired windows (`now >= windowStart + 60s`); active clients are never evicted or reset prematurely.
  - Capacity ceiling: Bounded at 10,000 active clients; fails closed for new IPs when saturated, returning `429 Too Many Requests` with `Retry-After: 60`.
  - Trusted reverse-proxy IP extraction: Parses `X-Forwarded-For` right-to-left only when direct connection is from a configured trusted proxy; ignores spoofed headers from untrusted connections.
- **Swagger / OpenAPI Policy**: Disabled by default in production (`springdoc.api-docs.enabled=false`, `springdoc.swagger-ui.enabled=false`); explicit documentation routes configured for development profile (`application-dev.yml`).
- **Resilience & Error Mapping**: `GatewayErrorWebExceptionHandler` preserves downstream 4xx ProblemDetails, maps connection failures to `502 Bad Gateway`, and maps upstream timeouts to `504 Gateway Timeout`.
- **Automated Tests**: 53 WebFlux/WebTestClient automated tests covering all security filters, evasions, rate limiting, and MockWebServer downstream routing. Full Maven reactor suite achieves 234 / 234 passing tests.

### Explicitly Deferred Items & Verification Boundaries:
1. **Physical MySQL 8 Verification (DEFERRED)**:
   - External MySQL 8 (`MySQL80` on port 3306) has not been connected to or modified.
2. **Production Multi-Node Gateway Clustering (DEFERRED)**:
   - Distributed Redis rate-limiting (e.g. `RedisRateLimiter`) is deferred to production clustering; in-memory sliding window rate limiter fulfills MVP single-instance gateway scope.
3. **Production Readiness Disclaimer**:
   - Production readiness is **NOT** marked as verified at this milestone. End-to-end multi-service runtime in a clustered production environment remains to be verified in later operational milestones.

---

## Detailed Milestone 5 Status & Deferred Items

### Scope Delivered:
- **Module Architecture**: Standalone Spring Boot Maven module `notification-service` running on port 8084, registered in root `pom.xml`.
- **Internal Ingestion Endpoint**: `POST /api/v1/notifications` strictly restricted to `ROLE_INTERNAL_SERVICE` via cryptographic Bearer JWT validation. Rejects passengers, drivers, and unauthenticated callers.
- **End-User Isolation & Privacy**: `GET /api/v1/notifications`, `GET /api/v1/notifications/unread-count`, `PATCH /api/v1/notifications/{id}/read`, and `PATCH /api/v1/notifications/read-all` strictly restricted to `ROLE_PASSENGER`, `ROLE_DRIVER`, `ROLE_ADMIN`. Rejects `ROLE_INTERNAL_SERVICE` with `403 Forbidden`. End-user queries are strictly scoped to the authenticated `UserPrincipal.getId()`.
- **Idempotency & Concurrency Safety**: Required non-blank `idempotencyKey` backed by `uk_notifications_idempotency_key` unique constraint. Handled via `NotificationPersistenceHelper` with `Propagation.REQUIRES_NEW` so concurrent insert collisions cleanly catch `DataIntegrityViolationException` and return the existing saved record without contaminating the outer transaction or altering read state.
- **Deterministic Pagination & Query Ordering**: Clamped `size` (1 to 100, default 20) with deterministic sorting on `ORDER BY created_at DESC, id DESC` to eliminate pagination jumps on timestamp ties.
- **Read-Tracking Invariants**: `PATCH /api/v1/notifications/{id}/read` returns `404 Not Found` for unowned notifications (preventing ID enumeration) and behaves idempotently on already-read items. `PATCH /api/v1/notifications/read-all` marks all unread notifications in bulk.
- **Fare Contract Consistency**: Structured `amount` (`DECIMAL(10, 2) NULL`) supported in `CreateNotificationRequest`, `Notification` entity, `NotificationResponse`, and Flyway migration `V1__init_notification_schema.sql`, alongside formatted `₹` currency in the `message` string.
- **Ride-Service Integration & Failure Isolation**: `RideNotificationService` dispatches notifications strictly post-commit after local ride state transactions close. Remote Feign failures (timeouts, 500, 503) are safely absorbed and logged; ride transactions never fail or roll back due to notification issues.
- **Automated Tests**: 25 tests in `notification-service` and 8 new tests in `ride-service` (51 total in `ride-service`).

### Explicitly Deferred Items & Verification Boundaries:
1. **Physical MySQL 8 Verification (DEFERRED)**:
   - All tests executed against isolated in-memory H2 (`jdbc:h2:mem:notification_isolated_test_db`).
   - Local MySQL (`MySQL80` on port 3306) has not been connected to or modified.
2. **External Notification Providers (Push/SMS/Email) (DEFERRED)**:
   - Firebase/APNs push notifications, Twilio SMS, and SendGrid email delivery are explicitly out of MVP scope.
3. **API Gateway Routing (DEFERRED)**:
   - Central gateway routing on port 8080 is scheduled for Milestone 6.
4. **Production Readiness Disclaimer**:
   - Production readiness is **NOT** marked as verified at this milestone. End-to-end multi-service runtime and external database performance remain to be verified in later milestones.

---

## Detailed Milestone 4 Status & Deferred Items

### Scope Delivered:
- **Module Architecture**: Standalone Spring Boot Maven module `ride-service` running on port 8082, registered in root `pom.xml`.
- **Ride Creation & Verified Fare Snapshot**: `POST /api/v1/rides` restricted to `ROLE_PASSENGER`. Validates quote validity and frozen coordinate snapshot with `pricing-service` via OpenFeign. Persists `estimated_fare`, distance, base fare, distance fare, booking fee, vehicle type, and currency.
- **Idempotency & Privacy**: `uq_rides_fare_quote_token` unique constraint on `fare_quote_token`. Resubmission by the same passenger returns the existing ride representation (`HTTP 200 OK`). Resubmission by a different passenger is rejected with `HTTP 400 Bad Request` without leaking owner or ride metadata.
- **Atomic State Transitions**: Transitions (`REQUESTED` -> `ACCEPTED` -> `DRIVER_ARRIVING` -> `IN_PROGRESS` -> `COMPLETED`, and `*` -> `CANCELLED`) executed inside atomic database transactions (`@Transactional(isolation = Isolation.READ_COMMITTED)`) pairing conditional `@Version` updates with `ride_events` audit inserts.
- **Distributed Lease & Driver Acceptance Protocol**: 4-phase non-blocking acceptance:
  1. Pre-check: Status must be `REQUESTED`.
  2. Remote Reserve: Calls `user-service` `/reserve` out-of-transaction.
  3. Local Commit: Atomic conditional update claims ride. On lost race, compensating call releases driver reservation.
  4. Remote Confirm: Calls `user-service` `/confirm-ride`. On lease expiry conflict (409), rolls back local ride to `REQUESTED`. On timeout, executes safe probe disambiguation.
- **Cancellation & Driver Freeing**: Cancelling an assigned ride (`ACCEPTED` or `DRIVER_ARRIVING`) calls `user-service` `POST /complete-ride` to restore the `ON_RIDE` driver to `AVAILABLE`. Cancelling an `IN_PROGRESS` ride is strictly rejected with `HTTP 409 Conflict`.
- **Reconciliation Audit & Disambiguation**: `ride_reconciliation_audit` table and scheduled reconciliation runner (`RideReconciliationService`) safely retry pending remote compensations with exponential backoff up to 5 retries. Includes explicit handling for `CONFIRM_RIDE_PROBE` (reconciling confirmed `ON_RIDE` trips, rolling back eligible `ACCEPTED` rides on confirmed release, retaining pending status on ambiguous conflicts, and escalating unrecognized operations to `MANUAL_ATTENTION`).
- **Internal Service Authentication**: `InternalTokenService` generates HMAC-SHA256 tokens with claim `ROLE_INTERNAL_SERVICE` and in-memory cache refresh. `InternalFeignClientInterceptor` injects bearer token on outbound Feign requests.
- **Database Schema**: Authored Flyway migration `V1__init_ride_schema.sql` establishing `rides`, `ride_events`, and `ride_reconciliation_audit` tables with indexes and constraints.
- **Automated Tests**: 51 automated tests across token generation, H2 repository constraints, lifecycle transitions, remote compensation, reconciliation disambiguation, notification dispatch fan-out, failure isolation, and WebMvc security/validation slice tests.

### Explicitly Deferred Items & Verification Boundaries:
1. **Physical MySQL 8 Verification (DEFERRED)**:
   - All tests executed against isolated in-memory H2 (`jdbc:h2:mem:ride_isolated_test_db`).
   - Local MySQL (`MySQL80` on port 3306) has not been connected to or modified.
2. **Dynamic Surge & Real-Time GPS Tracking (DEFERRED)**:
   - Live turn-by-turn routing and dynamic traffic surge multipliers are out of MVP scope. `final_fare` equals `estimated_fare`.
3. **API Gateway Routing (DEFERRED)**:
   - Gateway reverse proxy routing on port 8080 is scheduled for Milestone 6.
4. **Production Readiness Disclaimer**:
   - Production readiness is **NOT** marked as verified at this milestone. End-to-end multi-service runtime, API Gateway routing, and external database performance remain to be verified in later milestones.

---

## Detailed Milestone 3 Status & Deferred Items

### Scope Delivered:
- **Module Architecture**: Standalone Spring Boot Maven module `pricing-service` running on port 8083, registered in root `pom.xml`.
- **Full-Precision Fare Estimation**: `POST /api/v1/pricing/estimate` calculates unrounded spherical Haversine distance, multiplies by approved SEDAN rate (base fare ₹50.00, ₹15.00/km, booking fee ₹15.00), and rounds only the monetary values to 2 decimal places using `RoundingMode.HALF_UP` (e.g. 5.184659... km yields distance fare ₹77.77 and total estimated fare ₹142.77), while displaying and persisting the distance rounded to 2 decimal places (5.18 km). Generates unique `quoteToken` with strict 10-minute expiry (`now + 10 minutes`).
- **Input Validation**: Rejects identical pickup and destination coordinates with HTTP 400 Bad Request (`BadRequestException`). Validates coordinate ranges $[-90.0, 90.0]$ and $[-180.0, 180.0]$.
- **Quote Verification**: `GET /api/v1/pricing/quotes/{quoteToken}` strictly restricted to `ROLE_INTERNAL_SERVICE` (passengers and drivers return HTTP 403 Forbidden).
  - Unknown quote token returns HTTP 404 Not Found (`ResourceNotFoundException`).
  - Expired quote token ($t \ge \text{expiresAt}$) returns HTTP 410 Gone (`QuoteExpiredException`).
  - Valid quote returns the complete original saved snapshot (coordinates, 2-decimal distance, vehicle type, and fare breakdown) and remains reusable until expiry.
- **Database Schema & Migrations**: Authored Flyway migration `V1__init_pricing_schema.sql` (unexecuted against external MySQL) establishing `pricing_configurations` and `fare_quotes` tables with unique index on `quote_token` and index on `expires_at`, seeded with the default SEDAN configuration.
- **Security & Authorization**: Stateless JWT authentication with externalized secret validation; estimate endpoint accessible to `ROLE_PASSENGER` and `ROLE_DRIVER`; verification endpoint restricted to `ROLE_INTERNAL_SERVICE`; unauthenticated requests rejected with RFC 7807 401 Unauthorized.
- **Automated Tests**: 29 automated tests spanning Haversine calculations, service business logic and lifecycle, H2 repository persistence and uniqueness constraints, and WebMvc security slice tests.

### Explicitly Deferred Items & Verification Boundaries:
1. **MySQL Physical Verification (DEFERRED)**:
   - All tests executed against isolated in-memory H2 (`jdbc:h2:mem:pricing_isolated_test_db`).
   - Local MySQL (`MySQL80` on port 3306) has not been connected to or modified.
   - External database migration execution and production MySQL query verification remain deferred.
2. **Dynamic Multi-Tier Surge Pricing & Currency Conversion (DEFERRED)**:
   - Dynamic real-time surge pricing multipliers and multi-currency conversion are out of MVP scope.
3. **Production Readiness Disclaimer**:
   - Production readiness is **NOT** marked as verified at this milestone. End-to-end multi-service runtime, API Gateway routing, and external database performance remain to be verified in later milestones.

---

## Detailed Milestone 2 Status & Deferred Items

### Scope Delivered:
- **Driver Availability**: `PATCH /api/v1/drivers/availability` with authenticated driver ownership check (`@AuthenticationPrincipal UserPrincipal principal`), coordinate validation, and operational-state blocking (cannot go offline while `RESERVED` or `ON_RIDE`).
- **Proximity Discovery**: `GET /api/v1/drivers/available` restricted to `ROLE_PASSENGER` and `ROLE_INTERNAL_SERVICE`. Strictly omits raw coordinates (`AvailableDriverResponse` returns only `driverId`, `vehicleModel`, `licensePlate`, `distanceKm`, and `ratingAvg`). GPS freshness cutoff (`location_updated_at >= now - 600s`) enforced at database query level.
- **Geospatial Math**: `HaversineDistanceCalculator` spherical distance computation with anti-NaN domain clamping, pole convergence clamping, and antimeridian wrap-around detection.
- **Distributed Lease Protocol**: 30-second TTL reservation leases with strict expiry boundary contract:
  - Lease acquisition/reclamation allowed when `status = 'AVAILABLE'` OR `(status = 'RESERVED' AND reservation_expires_at <= now)`.
  - Lease confirmation requires `status = 'RESERVED'` AND `reserved_by_ride_id = :rideId` AND `reservation_expires_at > now`.
- **Release Idempotency Matrix**: 6-branch decision matrix returning 200 OK (`RELEASED`) on success, 200 OK (`ALREADY_RELEASED`) on duplicate retry for same `last_released_by_ride_id`, and 409 Conflict for mismatched or stale releases.
- **Ride Completion**: Atomic `completeRideAssignment` restoring status to `AVAILABLE`. Duplicate or ambiguous requests strictly return 409 Conflict.
- **Security & Authorization**: `ROLE_DRIVER` restricted to own availability; `ROLE_PASSENGER` and `ROLE_DRIVER` forbidden from operational endpoints; `ROLE_INTERNAL_SERVICE` permitted on operational endpoints.
- **Database Schema**: Authored Flyway migration `V2__add_driver_location_timestamp.sql` (unexecuted against external MySQL). Updated JPA entity `DriverProfile.locationUpdatedAt`.

### Explicitly Deferred Items & Verification Boundaries:
1. **MySQL Concurrency & Physical Row-Locking Verification (DEFERRED)**:
   - All unit, repository, and security tests were executed against isolated in-memory H2 (`jdbc:h2:mem:user_isolated_test_db`).
   - Local MySQL (`MySQL80` on port 3306) has not been connected to or modified.
2. **Production Service-to-Service M2M Authentication (DEFERRED)**:
   - Operational endpoints (`/reserve`, `/confirm-ride`, `/release-reservation`, `/complete-ride`) currently use the interim role `hasAuthority('ROLE_INTERNAL_SERVICE')`.
