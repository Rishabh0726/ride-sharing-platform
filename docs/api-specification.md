# REST API Specification
## Ride Sharing Platform Backend

All endpoints are versioned with the `/api/v1` prefix. When accessing through the API Gateway, requests are directed to `http://localhost:8080`.

---

## 1. Authentication & User Management (`user-service`: 8081)

### `POST /api/v1/auth/register`
* **Access**: Public
* **Request Body**:
  ```json
  {
    "email": "john.doe@example.com",
    "password": "SecurePassword123!",
    "firstName": "John",
    "lastName": "Doe",
    "phoneNumber": "+919876543210",
    "role": "PASSENGER"
  }
  ```
  *(For `role: "DRIVER"`, `vehicleModel` and `licensePlate` are mandatory).*
* **Response (201 Created)**:
  ```json
  {
    "userId": 101,
    "email": "john.doe@example.com",
    "role": "PASSENGER",
    "createdAt": "2026-10-21T00:00:00Z"
  }
  ```

### `POST /api/v1/auth/login`
* **Access**: Public
* **Request Body**:
  ```json
  {
    "email": "john.doe@example.com",
    "password": "SecurePassword123!"
  }
  ```
* **Response (200 OK)**:
  ```json
  {
    "accessToken": "eyJhbGciOiJIUzI1NiIsIn...",
    "tokenType": "Bearer",
    "expiresIn": 86400,
    "userId": 101,
    "role": "PASSENGER"
  }
  ```

### `GET /api/v1/users/me`
* **Access**: Authenticated (`PASSENGER` / `DRIVER` / `ADMIN`)
* **Response (200 OK)**: User profile details and driver profile (if driver).

### `PATCH /api/v1/drivers/availability`
* **Access**: Authenticated (`ROLE_DRIVER`)
* **Request Body**: `{ "online": true, "latitude": 12.9716, "longitude": 77.5946 }`
* **Response (200 OK)**: `{ "status": "AVAILABLE", "isOnline": true }`

---

## 2. Pricing & Fare Estimation (`pricing-service`: 8083)

> [!NOTE]
> Pricing quotes calculate trip distance and estimated fare based on vehicle rate configuration. In accordance with the MVP specification, actual third-party payment collection and card processing are intentionally excluded.

### `POST /api/v1/pricing/estimate`
* **Access**: Authenticated
* **Request Body**:
  ```json
  {
    "pickupLatitude": 12.9716,
    "pickupLongitude": 77.5946,
    "destinationLatitude": 12.9352,
    "destinationLongitude": 77.6245,
    "vehicleType": "SEDAN"
  }
  ```
* **Response (200 OK)**:
  ```json
  {
    "quoteToken": "quote-uuid-abc-123",
    "distanceKm": 5.42,
    "baseFare": 50.00,
    "distanceFare": 81.30,
    "bookingFee": 15.00,
    "estimatedFare": 146.30,
    "currency": "INR",
    "expiresAt": "2026-10-21T00:10:00Z"
  }
  ```

### `GET /api/v1/pricing/quotes/{quoteToken}`
* **Access**: Authenticated (`ROLE_INTERNAL_SERVICE`)
* **Response (200 OK)**:
  ```json
  {
    "quoteToken": "quote-uuid-abc-123",
    "pickupLatitude": 12.9716,
    "pickupLongitude": 77.5946,
    "destinationLatitude": 12.9352,
    "destinationLongitude": 77.6245,
    "distanceKm": 5.42,
    "vehicleType": "SEDAN",
    "baseFare": 50.00,
    "distanceFare": 81.30,
    "bookingFee": 15.00,
    "estimatedFare": 146.30,
    "currency": "INR",
    "expiresAt": "2026-10-21T00:10:00Z",
    "createdAt": "2026-10-21T00:00:00Z"
  }
  ```
* **Response (404 Not Found)**: Unknown quote token.
* **Response (410 Gone)**: Quote has expired (exceeded 10-minute validity window).

---

## 3. Ride Lifecycle Operations (`ride-service`: 8082)

### `POST /api/v1/rides`
* **Access**: Authenticated (`ROLE_PASSENGER`)
* **Request Body**:
  ```json
  {
    "quoteToken": "quote-uuid-abc-123",
    "pickupAddress": "MG Road, Bengaluru",
    "pickupLatitude": 12.9716,
    "pickupLongitude": 77.5946,
    "destinationAddress": "Koramangala, Bengaluru",
    "destinationLatitude": 12.9352,
    "destinationLongitude": 77.6245
  }
  ```
* **Response (201 Created)**:
  ```json
  {
    "rideId": 501,
    "rideUuid": "ride-uuid-789",
    "status": "REQUESTED",
    "passengerId": 101,
    "estimatedFare": 146.30,
    "createdAt": "2026-10-21T00:02:00Z"
  }
  ```

### `GET /api/v1/rides/{rideId}`
* **Access**: Authenticated (`ROLE_PASSENGER`, `ROLE_DRIVER`, `ROLE_ADMIN`, `ROLE_INTERNAL_SERVICE`)
* **Description**: Retrieve detailed information for a specific ride by its identifier.
* **Ownership Validation**:
  - `ROLE_PASSENGER`: Allowed only if the authenticated passenger created the ride.
  - `ROLE_DRIVER`: Allowed only if the authenticated driver is currently assigned to the ride.
  - `ROLE_ADMIN` / `ROLE_INTERNAL_SERVICE`: Unrestricted access across all rides.
* **Response (200 OK)**:
  ```json
  {
    "id": 501,
    "rideUuid": "ride-uuid-789",
    "passengerId": 101,
    "driverId": 202,
    "status": "ACCEPTED",
    "pickupLocationName": "MG Road, Bengaluru",
    "pickupLatitude": 12.9716,
    "pickupLongitude": 77.5946,
    "destinationLocationName": "Koramangala, Bengaluru",
    "destinationLatitude": 12.9352,
    "destinationLongitude": 77.6245,
    "distanceKm": 5.42,
    "fareQuoteToken": "quote-uuid-abc-123",
    "estimatedFare": 146.30,
    "finalFare": null,
    "baseFare": 50.00,
    "distanceFare": 81.30,
    "bookingFee": 15.00,
    "currency": "INR",
    "vehicleType": "SEDAN",
    "cancellationReason": null,
    "cancelledBy": null,
    "acceptedAt": "2026-10-21T00:03:00Z",
    "arrivedAt": null,
    "startedAt": null,
    "completedAt": null,
    "cancelledAt": null,
    "version": 1,
    "createdAt": "2026-10-21T00:02:00Z",
    "updatedAt": "2026-10-21T00:03:00Z"
  }
  ```
* **Response (401 Unauthorized)**: Missing or invalid JWT token.
* **Response (403 Forbidden)**: Caller is not the ride's passenger or assigned driver.
* **Response (404 Not Found)**: Ride not found with the requested ID.

### `GET /api/v1/rides/passenger/me`
* **Access**: Authenticated (`ROLE_PASSENGER`)
* **Description**: Retrieve chronological ride history for the calling authenticated passenger, ordered newest first (`createdAt DESC`).
* **Identity Binding**: Derived strictly from the authenticated JWT `UserPrincipal`.
* **Response (200 OK)**: List of `RideResponse` objects. Returns `[]` if the passenger has created no rides.
* **Response (401 Unauthorized)**: Missing or invalid JWT token.
* **Response (403 Forbidden)**: Caller lacks `ROLE_PASSENGER`.

### `GET /api/v1/rides/driver/available`
* **Access**: Authenticated (`ROLE_DRIVER`)
* **Description**: Retrieve all active ride requests currently awaiting driver acceptance (filtered strictly by `REQUESTED` status; unassigned by lifecycle invariant).
* **Ordering**: Deterministically ordered newest first (`createdAt DESC, id DESC`).
* **Response (200 OK)**: List of `RideResponse` objects awaiting acceptance. Returns empty array `[]` when no requested rides are available.
* **Response (401 Unauthorized)**: Missing or invalid JWT token.
* **Response (403 Forbidden)**: Caller lacks `ROLE_DRIVER`.

### `GET /api/v1/rides/driver/active`
* **Access**: Authenticated (`ROLE_DRIVER`)
* **Description**: Retrieve the current active ride assigned to the authenticated driver (in `ACCEPTED`, `DRIVER_ARRIVING`, or `IN_PROGRESS` status).
* **Identity Binding**: Driver identity derived strictly from the authenticated JWT `UserPrincipal`.
* **Response (200 OK)**: `RideResponse` object representing the driver's current active ride.
* **Response (401 Unauthorized)**: Missing or invalid JWT token.
* **Response (403 Forbidden)**: Caller lacks `ROLE_DRIVER`.
* **Response (404 Not Found)**: Driver has no active ride currently in progress.

### `GET /api/v1/rides/driver/history`
* **Access**: Authenticated (`ROLE_DRIVER`)
* **Description**: Retrieve completed ride history for the calling authenticated driver. Restricted exclusively to the driver's own rides that reached `COMPLETED` status.
* **Identity Binding**: Driver identity derived strictly from the authenticated JWT `UserPrincipal`. Does not accept a driver ID from URL path or query parameters (preventing Insecure Direct Object References).
* **Ordering**: Deterministically ordered newest first (`createdAt DESC, id DESC`).
* **Response (200 OK)**: List of `RideResponse` objects representing the driver's completed trips. Returns empty array `[]` if the driver has completed no rides.
* **Payment Boundary**: Displays recorded `finalFare` snapshot equal to `estimatedFare`. This operation reflects trip history and does not represent financial settlement or prove that payment was collected.
* **Response (401 Unauthorized)**: Missing or invalid JWT token.
* **Response (403 Forbidden)**: Caller lacks `ROLE_DRIVER`.

### `POST /api/v1/rides/{rideId}/accept`
* **Access**: Authenticated (`ROLE_DRIVER`)
* **Response (200 OK)**: Ride state advanced to `ACCEPTED`, driver assigned.
* **Response (409 Conflict)**: Ride already accepted by another driver or lease conflict.

### `POST /api/v1/rides/{rideId}/arrive`
* **Access**: Authenticated (`ROLE_DRIVER` assigned to ride)
* **Response (200 OK)**: Ride state advanced to `DRIVER_ARRIVING`.

### `POST /api/v1/rides/{rideId}/start`
* **Access**: Authenticated (`ROLE_DRIVER` assigned to ride)
* **Response (200 OK)**: Ride state advanced to `IN_PROGRESS`.

### `POST /api/v1/rides/{rideId}/complete`
* **Access**: Authenticated (`ROLE_DRIVER` assigned to ride)
* **Response (200 OK)**: Ride state advanced to `COMPLETED`, `finalFare` recorded from `estimatedFare`.
* **Payment Boundary**: Freezes `finalFare` equal to `estimatedFare` for trip and audit records. This operation does not create a payment transaction, capture credit cards, or prove that money was collected. Third-party payment settlement (Stripe/Razorpay) is intentionally excluded from the MVP scope.

### `POST /api/v1/rides/{rideId}/cancel`
* **Access**: Authenticated (`ROLE_PASSENGER` or assigned `ROLE_DRIVER`)
* **Request Body**: `{ "reason": "Passenger changed plans" }`
* **Response (200 OK)**: Ride state transitioned to `CANCELLED`.

### `GET /api/v1/rides/{rideId}/events`
* **Access**: Authenticated (`ROLE_PASSENGER`, `ROLE_DRIVER`, `ROLE_ADMIN`, `ROLE_INTERNAL_SERVICE`)
* **Description**: Retrieve chronological audit log of lifecycle event transitions for a specific ride, ordered oldest first (`createdAt ASC`).
* **Ownership / Access Validation**: Validates that the caller is the ride's passenger, the assigned driver, an admin, or an internal service.
* **Response (200 OK)**: List of `RideEventResponse` objects representing state transitions.
  ```json
  [
    {
      "id": 1,
      "rideId": 501,
      "fromStatus": null,
      "toStatus": "REQUESTED",
      "actorId": 101,
      "actorRole": "ROLE_PASSENGER",
      "remarks": "Ride requested by passenger",
      "createdAt": "2026-10-21T00:02:00Z"
    },
    {
      "id": 2,
      "rideId": 501,
      "fromStatus": "REQUESTED",
      "toStatus": "ACCEPTED",
      "actorId": 202,
      "actorRole": "ROLE_DRIVER",
      "remarks": "Driver accepted ride",
      "createdAt": "2026-10-21T00:03:00Z"
    }
  ]
  ```
* **Response (401 Unauthorized)**: Missing or invalid JWT token.
* **Response (403 Forbidden)**: Caller does not have permission to access this ride's event history.
* **Response (404 Not Found)**: Ride not found with the requested ID.

---

## 4. Notifications (`notification-service`: 8084)

### `GET /api/v1/notifications`
* **Access**: Authenticated
* **Response (200 OK)**: List of in-app notifications for the calling user.

### `PATCH /api/v1/notifications/{id}/read`
* **Access**: Authenticated (Notification Owner)
* **Response (200 OK)**: `{ "id": 12, "isRead": true }`

---

## 5. Centralized Error Responses (RFC 7807)
All business and validation errors follow the RFC 7807 `ProblemDetail` specification:
```json
{
  "type": "https://api.rideshare.com/errors/conflict",
  "title": "Conflict",
  "status": 409,
  "detail": "Driver is currently reserved by another ride",
  "instance": "/api/v1/drivers/42/release-reservation",
  "timestamp": "2026-10-21T00:05:00Z"
}
```
