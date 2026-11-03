# Project Requirements & MVP Scope
## Ride Sharing Platform Backend (Simplified Uber-Style Architecture)

---

## 1. Product Objective
Build a portfolio-grade, distributed backend simulating the core ride lifecycle of a ride-sharing platform using Java 21, Spring Boot 4.1.x, Spring Cloud 2025.1.x, MySQL 8, Spring Security with JWT, and Spring Cloud OpenFeign.

The platform allows passengers to request rides with guaranteed fare quotes, drivers to manage their availability and accept ride assignments with distributed concurrency protection, tracks ride state transitions, and generates persisted in-app notifications.

---

## 2. Actors & Roles
1. **`ROLE_PASSENGER`**:
   - Registers account, authenticates via JWT.
   - Requests fare estimates based on pickup and destination coordinates.
   - Creates ride requests referencing a valid, unexpired fare quote.
   - Cancels rides according to defined lifecycle rules.
   - Views own ride history and notifications.
2. **`ROLE_DRIVER`**:
   - Registers account with vehicle details (model, license plate).
   - Toggles online/offline availability.
   - Accepts requested rides within proximity.
   - Advances ride states (`ACCEPTED` $\rightarrow$ `DRIVER_ARRIVING` $\rightarrow$ `IN_PROGRESS` $\rightarrow$ `COMPLETED`).
   - Cancels accepted rides under permitted exception scenarios.
   - Views own ride history and notifications.
3. **`ROLE_ADMIN`**:
   - Administrative monitoring, viewing ride records across users, managing user accounts.

---

## 3. In-Scope MVP Features
1. **User Management Service (`user-service`)**:
   - User registration (Passenger / Driver) with role validation.
   - Credential verification and BCrypt password hashing (strength 12).
   - Stateless JWT issuance (HMAC-SHA256, 24-hour expiration).
   - User and driver profile retrieval (`/api/v1/users/me`).
   - Driver availability toggle (`is_available = true/false`).
   - Distributed driver reservation and release endpoints with transient lease TTL.
2. **Pricing Service (`pricing-service`)**:
   - Configurable fare model: $\text{Fare} = \text{baseFare} + (\text{distanceKm} \times \text{ratePerKm}) + \text{bookingFee}$ in INR (`BigDecimal`).
   - Deterministic distance calculation using the Haversine spherical formula.
   - Expiring fare quote generation with unique `quoteToken` (10-minute validity).
   - Quote verification and final fare confirmation.
3. **Ride Lifecycle Service (`ride-service`)**:
   - Ride creation referencing a valid fare quote token.
   - Online driver discovery.
   - Two-step driver reservation and acceptance protocol.
   - Strict lifecycle state transitions (`REQUESTED` $\rightarrow$ `ACCEPTED` $\rightarrow$ `DRIVER_ARRIVING` $\rightarrow$ `IN_PROGRESS` $\rightarrow$ `COMPLETED` / `CANCELLED`).
   - Race condition defense via pessimistic row locking and optimistic `@Version` checks.
   - Compensating zero-row rollback handling and audit quarantine.
4. **Notification Service (`notification-service`)**:
   - Persisted in-app event notifications (`RIDE_REQUESTED`, `DRIVER_ASSIGNED`, `RIDE_ACCEPTED`, `DRIVER_ARRIVING`, `RIDE_STARTED`, `RIDE_COMPLETED`, `RIDE_CANCELLED`).
   - User notification retrieval and mark-as-read tracking.
   - Best-effort Feign integration (failures do not roll back completed rides).
5. **API Gateway (`api-gateway`)**:
   - Central reverse proxy on port 8080.
   - Routing rules to backend microservices.
   - Centralized CORS and security header forwarding (`X-User-Id`, `X-User-Role`).

---

## 4. Explicitly Out-of-Scope (MVP Exclusions)
- Real third-party payment gateways (Stripe/Razorpay) or banking transfers.
- Live GPS WebSocket tracking or turn-by-turn road navigation (Google Maps API).
- SMS or email notification delivery providers (Twilio, SendGrid).
- Distributed message brokers (Kafka/RabbitMQ) or distributed caching (Redis).
- Service discovery registries (Eureka/Consul) or Kubernetes orchestration.
- Frontend web or mobile application (all testing via OpenAPI/Swagger, Postman, curl).

---

## 5. Non-Functional Requirements
- **Security**: Stateless JWT verification, BCrypt password hashing, role-based endpoint protection, and strict resource ownership checks. No secrets committed to source.
- **Maintainability**: Independent Maven modules, clean package structure, separation of controller, service, repository, and DTO layers.
- **Reliability & Consistency**: Bounded timeouts on Feign calls, optimistic versioning, conditional updates, self-healing TTL leases.
- **Auditability**: RFC 7807 standard error payloads, correlation IDs in inter-service headers, structured logs.

---

## 6. Milestone Implementation Status
For the live implementation status of microservices, milestone deliverables, test verification results, and deferred integration items, see [milestone-tracker.md](file:///c:/Users/Rishabh%20Tiwari/OneDrive/Desktop/Ride%20Sharing%20Platform/docs/milestone-tracker.md).

