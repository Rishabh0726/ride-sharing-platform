package com.rideshare.ride_service.service;

import com.rideshare.ride_service.client.PricingClient;
import com.rideshare.ride_service.client.UserClient;
import com.rideshare.ride_service.client.dto.*;
import com.rideshare.ride_service.dto.request.CancelRideRequest;
import com.rideshare.ride_service.dto.request.CreateRideRequest;
import com.rideshare.ride_service.dto.response.RideEventResponse;
import com.rideshare.ride_service.dto.response.RideResponse;
import com.rideshare.ride_service.entity.Ride;
import com.rideshare.ride_service.entity.RideEvent;
import com.rideshare.ride_service.entity.RideStatus;
import com.rideshare.ride_service.exception.*;
import com.rideshare.ride_service.repository.RideEventRepository;
import com.rideshare.ride_service.repository.RideRepository;
import feign.FeignException;
import feign.RetryableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RideServiceImpl implements RideService {

    private static final List<RideStatus> ACTIVE_RIDE_STATUSES = List.of(
            RideStatus.REQUESTED,
            RideStatus.ACCEPTED,
            RideStatus.DRIVER_ARRIVING,
            RideStatus.IN_PROGRESS
    );

    private final RideRepository rideRepository;
    private final RideEventRepository rideEventRepository;
    private final RideStateTransitionService transitionService;
    private final RideReconciliationService reconciliationService;
    private final RideNotificationService notificationService;
    private final PricingClient pricingClient;
    private final UserClient userClient;

    @Override
    public RideResponse createRide(Long passengerId, CreateRideRequest request) {
        log.info("Creating ride for passengerId: {} with quoteToken: {}", passengerId, request.getFareQuoteToken());

        // 1. Idempotency Check
        Optional<Ride> existingRideOpt = rideRepository.findByFareQuoteToken(request.getFareQuoteToken());
        if (existingRideOpt.isPresent()) {
            Ride existingRide = existingRideOpt.get();
            if (existingRide.getPassengerId().equals(passengerId)) {
                log.info("Idempotent ride retrieval for quote token: {} by passenger: {}",
                        request.getFareQuoteToken(), passengerId);
                return mapToRideResponse(existingRide);
            } else {
                log.warn("Quote token collision attempt. Authenticated: {}, Owner: {}",
                        passengerId, existingRide.getPassengerId());
                throw new BadRequestException("Invalid fare quote token: quote has already been consumed");
            }
        }

        // 2. Active Ride Pre-check: Passenger cannot have multiple active rides simultaneously
        if (rideRepository.existsByPassengerIdAndStatusIn(passengerId, ACTIVE_RIDE_STATUSES)) {
            log.warn("Passenger {} attempted to create ride while already having an active ride", passengerId);
            throw new ConflictException("Passenger already has an active ride in progress");
        }

        // 3. Pricing Quote Verification (Out-of-transaction Feign Call)
        FareQuoteResponse quote = verifyQuoteWithPricingService(request.getFareQuoteToken());

        // 4. Strict Coordinate Matching Validation (BigDecimal.compareTo)
        validateCoordinatesMatchQuote(request, quote);

        // 5. Build Ride Entity with Pricing Snapshot
        Ride ride = Ride.builder()
                .rideUuid(UUID.randomUUID().toString())
                .passengerId(passengerId)
                .status(RideStatus.REQUESTED)
                .pickupLocationName(request.getPickupLocationName())
                .pickupLatitude(request.getPickupLatitude())
                .pickupLongitude(request.getPickupLongitude())
                .destinationLocationName(request.getDestinationLocationName())
                .destinationLatitude(request.getDestinationLatitude())
                .destinationLongitude(request.getDestinationLongitude())
                .distanceKm(BigDecimal.valueOf(quote.getDistanceKm()))
                .fareQuoteToken(quote.getQuoteToken())
                .estimatedFare(quote.getEstimatedFare())
                .finalFare(null)
                .baseFare(quote.getBaseFare())
                .distanceFare(quote.getDistanceFare())
                .bookingFee(quote.getBookingFee())
                .currency(quote.getCurrency() != null ? quote.getCurrency() : "INR")
                .vehicleType(quote.getVehicleType() != null ? quote.getVehicleType() : "SEDAN")
                .build();

        try {
            Ride savedRide = transitionService.saveNewRide(ride, passengerId);
            notificationService.notifyRideRequested(savedRide);
            return mapToRideResponse(savedRide);
        } catch (DataIntegrityViolationException ex) {
            // 1. Detect single active ride constraint violation (uq_rides_active_passenger)
            if (isActiveRideConstraintViolation(ex)) {
                log.warn("Active ride constraint violation detected for passenger {}: {}", passengerId, ex.getMessage());
                throw new ConflictException("Passenger already has an active ride in progress");
            }

            // 2. Check for duplicate/consumed fare quote token
            log.warn("Database uniqueness constraint triggered on quote token: {}", request.getFareQuoteToken());
            Optional<Ride> existing = rideRepository.findByFareQuoteToken(request.getFareQuoteToken());
            if (existing.isPresent()) {
                if (existing.get().getPassengerId().equals(passengerId)) {
                    return mapToRideResponse(existing.get());
                }
                throw new BadRequestException("Invalid fare quote token: quote has already been consumed");
            }

            // 3. Rethrow unrelated data integrity violation to avoid misleading business exception
            log.error("Unhandled DataIntegrityViolationException during ride creation for passenger {}: {}",
                    passengerId, ex.getMessage(), ex);
            throw ex;
        }
    }

    private FareQuoteResponse verifyQuoteWithPricingService(String quoteToken) {
        try {
            ResponseEntity<FareQuoteResponse> response = pricingClient.verifyQuote(quoteToken);
            if (response.getBody() == null) {
                throw new BadRequestException("Invalid fare quote: empty response from pricing service");
            }
            return response.getBody();
        } catch (FeignException.NotFound nfEx) {
            log.warn("Pricing service returned 404 for quote token: {}", quoteToken);
            throw new BadRequestException("Invalid fare quote token: quote not found");
        } catch (FeignException feignEx) {
            int status = feignEx.status();
            log.warn("Pricing service Feign error [HTTP {}]: {}", status, feignEx.getMessage());

            if (status == 504 || feignEx instanceof RetryableException || (feignEx.getCause() != null && feignEx.getCause() instanceof SocketTimeoutException)) {
                throw new GatewayTimeoutException("Pricing service request timed out. Please retry shortly");
            }
            if (status == 410) {
                throw new BadRequestException("Fare quote has expired. Please request a new fare estimate");
            }
            if (status == 401 || status == 403) {
                log.error("CRITICAL: Internal service authorization failure on pricing-service: {}", feignEx.getMessage());
                throw new ServiceUnavailableException("Internal authentication error contacting pricing service");
            }
            if (status >= 500) {
                throw new ServiceUnavailableException("Pricing service temporarily unavailable. Please retry shortly");
            }

            throw new BadRequestException("Failed to verify fare quote: " + feignEx.getMessage());
        } catch (Exception ex) {
            log.error("Unexpected error contacting pricing service: {}", ex.getMessage());
            throw new ServiceUnavailableException("Pricing service temporarily unavailable. Please retry shortly");
        }
    }

    private void validateCoordinatesMatchQuote(CreateRideRequest request, FareQuoteResponse quote) {
        if (request.getPickupLatitude().compareTo(quote.getPickupLatitude()) != 0 ||
            request.getPickupLongitude().compareTo(quote.getPickupLongitude()) != 0 ||
            request.getDestinationLatitude().compareTo(quote.getDestinationLatitude()) != 0 ||
            request.getDestinationLongitude().compareTo(quote.getDestinationLongitude()) != 0) {
            throw new BadRequestException("Pickup and destination coordinates do not match the fare quote snapshot");
        }
    }

    @Override
    public RideResponse acceptRide(Long rideId, Long driverId) {
        log.info("Driver {} attempting to accept rideId: {}", driverId, rideId);

        // Pre-check: Driver cannot accept if they already have an active ride
        if (rideRepository.existsByDriverIdAndStatusIn(driverId, List.of(RideStatus.ACCEPTED, RideStatus.DRIVER_ARRIVING, RideStatus.IN_PROGRESS))) {
            throw new ConflictException("Driver already has an active ride in progress");
        }

        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> new ResourceNotFoundException("Ride not found with id: " + rideId));

        if (ride.getStatus() != RideStatus.REQUESTED) {
            throw new ConflictException("Ride is no longer available for acceptance (current status: " + ride.getStatus() + ")");
        }

        Long expectedVersion = ride.getVersion();

        // Step 2: Acquire Distributed Reservation Lease via user-service (Out-of-transaction Feign Call)
        try {
            userClient.reserveDriver(driverId, DriverReservationRequest.builder().rideId(rideId).build());
        } catch (FeignException.Conflict conflictEx) {
            log.warn("user-service reserveDriver returned 409: {}", conflictEx.getMessage());
            throw new ConflictException("Driver is unavailable, offline, or already reserved");
        } catch (FeignException.NotFound nfEx) {
            throw new ResourceNotFoundException("Driver profile not found with id: " + driverId);
        } catch (Exception ex) {
            log.error("Network failure or timeout during reserveDriver: {}", ex.getMessage());
            // Best effort release
            try {
                userClient.releaseReservation(driverId, DriverReleaseRequest.builder().rideId(rideId).build());
            } catch (Exception ignored) {}
            throw new GatewayTimeoutException("Driver reservation timed out; lease released");
        }

        // Step 3: Local Commit (Atomic Conditional Update)
        boolean claimed = transitionService.claimAndAcceptRideLocally(rideId, driverId, expectedVersion);
        if (!claimed) {
            log.warn("Local claim failed for rideId: {}. Releasing remote driver reservation...", rideId);
            try {
                userClient.releaseReservation(driverId, DriverReleaseRequest.builder().rideId(rideId).build());
            } catch (Exception ex) {
                reconciliationService.recordCompensationFailure(rideId, driverId, "RELEASE_RESERVATION", ex.getMessage());
            }
            throw new ConflictException("Ride was concurrently accepted by another driver or cancelled");
        }

        // Step 4: Remote Confirm via user-service (Out-of-transaction Feign Call)
        try {
            userClient.confirmRide(driverId, DriverActionRequest.builder().rideId(rideId).build());
            Ride acceptedRide = rideRepository.findById(rideId).orElseThrow();
            notificationService.notifyRideAccepted(acceptedRide);
            return mapToRideResponse(acceptedRide);
        } catch (FeignException.Conflict conflictEx) {
            log.warn("confirmRide returned 409 Conflict (lease expired): {}", conflictEx.getMessage());
            // Rollback local ride back to REQUESTED
            transitionService.rollbackAcceptanceLocally(rideId, driverId, "Reservation lease expired before confirmation");
            throw new ConflictException("Reservation lease expired before confirmation could complete");
        } catch (Exception ex) {
            log.warn("Ambiguous remote outcome on confirmRide for rideId: {}, driverId: {}. Probing...", rideId, driverId);
            // Probe user-service to disambiguate
            try {
                userClient.releaseReservation(
                        driverId, DriverReleaseRequest.builder().rideId(rideId).build()
                );
                // If releaseReservation succeeded (200 OK), driver was RESERVED, so confirm never happened
                transitionService.rollbackAcceptanceLocally(rideId, driverId, "Confirmation timed out and was rolled back");
                throw new GatewayTimeoutException("Confirmation timed out; driver assignment rolled back");
            } catch (FeignException.Conflict probeConflict) {
                String msg = probeConflict.getMessage() != null ? probeConflict.getMessage().toLowerCase() : "";
                if (msg.contains("active trip")) {
                    // Driver reached ON_RIDE! Confirmation actually succeeded!
                    log.info("Probe confirmed driver is ON_RIDE! Considering acceptance successful.");
                    Ride acceptedRide = rideRepository.findById(rideId).orElseThrow();
                    notificationService.notifyRideAccepted(acceptedRide);
                    return mapToRideResponse(acceptedRide);
                }
                // Driver state ambiguous
                reconciliationService.recordCompensationFailure(rideId, driverId, "CONFIRM_RIDE_PROBE", ex.getMessage());
                throw new GatewayTimeoutException("Driver confirmation timed out and is pending reconciliation");
            } catch (GatewayTimeoutException gte) {
                throw gte;
            } catch (Exception probeEx) {
                reconciliationService.recordCompensationFailure(rideId, driverId, "CONFIRM_RIDE_PROBE", ex.getMessage());
                throw new GatewayTimeoutException("Driver confirmation timed out and is pending reconciliation");
            }
        }
    }

    @Override
    public RideResponse arriveAtPickup(Long rideId, Long driverId) {
        log.info("Driver {} arrived at pickup for rideId: {}", driverId, rideId);
        Ride updated = transitionService.transitionToDriverArriving(rideId, driverId);
        notificationService.notifyDriverArrived(updated);
        return mapToRideResponse(updated);
    }

    @Override
    public RideResponse startRide(Long rideId, Long driverId) {
        log.info("Driver {} starting trip for rideId: {}", driverId, rideId);
        Ride updated = transitionService.transitionToInProgress(rideId, driverId);
        notificationService.notifyRideStarted(updated);
        return mapToRideResponse(updated);
    }

    @Override
    public RideResponse completeRide(Long rideId, Long driverId) {
        log.info("Driver {} completing trip for rideId: {}", driverId, rideId);

        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> new ResourceNotFoundException("Ride not found with id: " + rideId));

        if (ride.getStatus() != RideStatus.IN_PROGRESS) {
            throw new ConflictException("Cannot complete ride: status is " + ride.getStatus());
        }
        if (ride.getDriverId() == null || !ride.getDriverId().equals(driverId)) {
            throw new ConflictException("Driver is not assigned to this ride");
        }

        // Final fare assigned from estimated fare upon completion
        BigDecimal finalFare = ride.getEstimatedFare();

        Ride completedRide = transitionService.transitionToCompleted(rideId, driverId, finalFare);

        // Remote Complete in user-service (Out-of-transaction Feign Call)
        try {
            userClient.completeRide(driverId, DriverActionRequest.builder().rideId(rideId).build());
        } catch (Exception ex) {
            log.warn("Remote completeRide failed for driverId: {}, rideId: {}. Queuing reconciliation: {}",
                    driverId, rideId, ex.getMessage());
            reconciliationService.recordCompensationFailure(rideId, driverId, "COMPLETE_RIDE", ex.getMessage());
        }

        notificationService.notifyRideCompleted(completedRide);
        return mapToRideResponse(completedRide);
    }

    @Override
    public RideResponse cancelRide(Long rideId, Long actorId, String actorRole, CancelRideRequest request) {
        log.info("Cancelling rideId: {} by actorId: {} ({})", rideId, actorId, actorRole);

        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> new ResourceNotFoundException("Ride not found with id: " + rideId));

        if (ride.getStatus() == RideStatus.IN_PROGRESS) {
            throw new ConflictException("Ride is already in progress and cannot be cancelled");
        }
        if (ride.getStatus() == RideStatus.COMPLETED || ride.getStatus() == RideStatus.CANCELLED) {
            throw new ConflictException("Ride is already in terminal status: " + ride.getStatus());
        }

        // Ownership Validation
        String cancelledBy;
        if ("ROLE_PASSENGER".equalsIgnoreCase(actorRole)) {
            if (!ride.getPassengerId().equals(actorId)) {
                throw new ForbiddenException("You can only cancel your own rides");
            }
            cancelledBy = "PASSENGER";
        } else if ("ROLE_DRIVER".equalsIgnoreCase(actorRole)) {
            if (ride.getDriverId() == null || !ride.getDriverId().equals(actorId)) {
                throw new ForbiddenException("You are not assigned to this ride");
            }
            cancelledBy = "DRIVER";
        } else {
            cancelledBy = "ADMIN";
        }

        Collection<RideStatus> allowedStatuses = List.of(RideStatus.REQUESTED, RideStatus.ACCEPTED, RideStatus.DRIVER_ARRIVING);
        String reason = (request != null && request.getReason() != null) ? request.getReason().trim() : "Cancelled by " + cancelledBy;

        Long assignedDriverId = ride.getDriverId();

        Ride cancelledRide = transitionService.transitionToCancelled(
                rideId, allowedStatuses, cancelledBy, reason, actorId, actorRole
        );

        // Remote Compensation to free driver if assigned
        if (assignedDriverId != null) {
            log.info("Freeing assigned driver {} for cancelled ride {}", assignedDriverId, rideId);
            try {
                // If driver was ON_RIDE, completeRide restores AVAILABLE
                userClient.completeRide(assignedDriverId, DriverActionRequest.builder().rideId(rideId).build());
            } catch (FeignException.Conflict conflictEx) {
                // If driver was only RESERVED, releaseReservation releases lease
                try {
                    userClient.releaseReservation(assignedDriverId, DriverReleaseRequest.builder().rideId(rideId).build());
                } catch (Exception ex) {
                    reconciliationService.recordCompensationFailure(rideId, assignedDriverId, "RELEASE_RESERVATION", ex.getMessage());
                }
            } catch (Exception ex) {
                log.warn("Remote driver release failed during cancel. Queuing reconciliation: {}", ex.getMessage());
                reconciliationService.recordCompensationFailure(rideId, assignedDriverId, "COMPLETE_RIDE", ex.getMessage());
            }
        }

        notificationService.notifyRideCancelled(cancelledRide, cancelledBy, reason);
        return mapToRideResponse(cancelledRide);
    }

    @Override
    public RideResponse getRideById(Long rideId, Long requestingUserId, String requestingUserRole) {
        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> new ResourceNotFoundException("Ride not found with id: " + rideId));

        validateRideAccess(ride, requestingUserId, requestingUserRole);
        return mapToRideResponse(ride);
    }

    @Override
    public List<RideResponse> getPassengerRides(Long passengerId) {
        return rideRepository.findByPassengerIdOrderByCreatedAtDesc(passengerId).stream()
                .map(this::mapToRideResponse)
                .toList();
    }

    @Override
    public RideResponse getDriverActiveRide(Long driverId) {
        return rideRepository.findFirstByDriverIdAndStatusInOrderByCreatedAtDesc(
                driverId, List.of(RideStatus.ACCEPTED, RideStatus.DRIVER_ARRIVING, RideStatus.IN_PROGRESS)
        ).map(this::mapToRideResponse)
         .orElseThrow(() -> new ResourceNotFoundException("No active ride found for driver with id: " + driverId));
    }

    @Override
    public List<RideResponse> getAvailableRides() {
        return rideRepository.findByStatusOrderByCreatedAtDescIdDesc(RideStatus.REQUESTED).stream()
                .map(this::mapToRideResponse)
                .toList();
    }

    @Override
    public List<RideResponse> getDriverCompletedRides(Long driverId) {
        return rideRepository.findByDriverIdAndStatusOrderByCreatedAtDescIdDesc(driverId, RideStatus.COMPLETED).stream()
                .map(this::mapToRideResponse)
                .toList();
    }

    @Override
    public List<RideEventResponse> getRideEvents(Long rideId, Long requestingUserId, String requestingUserRole) {
        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> new ResourceNotFoundException("Ride not found with id: " + rideId));

        validateRideAccess(ride, requestingUserId, requestingUserRole);

        return rideEventRepository.findByRideIdOrderByCreatedAtAsc(rideId).stream()
                .map(this::mapToRideEventResponse)
                .toList();
    }

    private void validateRideAccess(Ride ride, Long userId, String role) {
        if ("ROLE_ADMIN".equalsIgnoreCase(role) || "ROLE_INTERNAL_SERVICE".equalsIgnoreCase(role)) {
            return;
        }
        if ("ROLE_PASSENGER".equalsIgnoreCase(role) && ride.getPassengerId().equals(userId)) {
            return;
        }
        if ("ROLE_DRIVER".equalsIgnoreCase(role) && ride.getDriverId() != null && ride.getDriverId().equals(userId)) {
            return;
        }

        throw new ForbiddenException("You do not have permission to access this ride");
    }

    private RideResponse mapToRideResponse(Ride ride) {
        return RideResponse.builder()
                .id(ride.getId())
                .rideUuid(ride.getRideUuid())
                .passengerId(ride.getPassengerId())
                .driverId(ride.getDriverId())
                .status(ride.getStatus().name())
                .pickupLocationName(ride.getPickupLocationName())
                .pickupLatitude(ride.getPickupLatitude())
                .pickupLongitude(ride.getPickupLongitude())
                .destinationLocationName(ride.getDestinationLocationName())
                .destinationLatitude(ride.getDestinationLatitude())
                .destinationLongitude(ride.getDestinationLongitude())
                .distanceKm(ride.getDistanceKm())
                .fareQuoteToken(ride.getFareQuoteToken())
                .estimatedFare(ride.getEstimatedFare())
                .finalFare(ride.getFinalFare())
                .baseFare(ride.getBaseFare())
                .distanceFare(ride.getDistanceFare())
                .bookingFee(ride.getBookingFee())
                .currency(ride.getCurrency())
                .vehicleType(ride.getVehicleType())
                .cancellationReason(ride.getCancellationReason())
                .cancelledBy(ride.getCancelledBy())
                .acceptedAt(ride.getAcceptedAt())
                .arrivedAt(ride.getArrivedAt())
                .startedAt(ride.getStartedAt())
                .completedAt(ride.getCompletedAt())
                .cancelledAt(ride.getCancelledAt())
                .version(ride.getVersion())
                .createdAt(ride.getCreatedAt())
                .updatedAt(ride.getUpdatedAt())
                .build();
    }

    private RideEventResponse mapToRideEventResponse(RideEvent event) {
        return RideEventResponse.builder()
                .id(event.getId())
                .rideId(event.getRide().getId())
                .fromStatus(event.getFromStatus())
                .toStatus(event.getToStatus())
                .actorId(event.getActorId())
                .actorRole(event.getActorRole())
                .remarks(event.getRemarks())
                .createdAt(event.getCreatedAt())
                .build();
    }

    private boolean isActiveRideConstraintViolation(DataIntegrityViolationException ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof ConstraintViolationException cve) {
                String constraintName = cve.getConstraintName();
                if (constraintName != null && constraintName.toLowerCase().contains("uq_rides_active_passenger")) {
                    return true;
                }
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("uq_rides_active_passenger")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
