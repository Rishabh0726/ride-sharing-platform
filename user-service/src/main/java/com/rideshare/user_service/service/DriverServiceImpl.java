package com.rideshare.user_service.service;

import com.rideshare.user_service.dto.request.DriverActionRequest;
import com.rideshare.user_service.dto.request.DriverAvailabilityRequest;
import com.rideshare.user_service.dto.request.DriverReleaseRequest;
import com.rideshare.user_service.dto.request.DriverReservationRequest;
import com.rideshare.user_service.dto.response.*;
import com.rideshare.user_service.entity.DriverProfile;
import com.rideshare.user_service.entity.DriverStatus;
import com.rideshare.user_service.exception.BadRequestException;
import com.rideshare.user_service.exception.ConflictException;
import com.rideshare.user_service.exception.ResourceNotFoundException;
import com.rideshare.user_service.repository.DriverProfileRepository;
import com.rideshare.user_service.util.HaversineDistanceCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DriverServiceImpl implements DriverService {

    private final DriverProfileRepository driverProfileRepository;
    private final HaversineDistanceCalculator distanceCalculator;
    private final Clock clock;

    @Value("${application.driver.location.max-age-seconds:600}")
    private long maxLocationAgeSeconds = 600L;

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DriverAvailabilityResponse toggleAvailability(Long authenticatedUserId, DriverAvailabilityRequest request) {
        log.info("Processing availability toggle for userId: {}, online: {}", authenticatedUserId, request.getOnline());

        DriverProfile profile = driverProfileRepository.findByUserId(authenticatedUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found for user id: " + authenticatedUserId));

        LocalDateTime now = LocalDateTime.now(clock);

        if (Boolean.TRUE.equals(request.getOnline())) {
            if (request.getLatitude() == null || request.getLongitude() == null) {
                throw new BadRequestException("Latitude and longitude are required when going online");
            }

            if (profile.getStatus() == DriverStatus.OFFLINE) {
                int updated = driverProfileRepository.setDriverAvailable(profile.getId(), request.getLatitude(), request.getLongitude(), now);
                if (updated == 0) {
                    throw new ConflictException("Could not transition driver to available state");
                }
            } else if (profile.getStatus() == DriverStatus.AVAILABLE) {
                driverProfileRepository.updateDriverLocation(profile.getId(), request.getLatitude(), request.getLongitude(), now);
            } else {
                throw new ConflictException("Cannot change availability while in status: " + profile.getStatus());
            }
        } else {
            if (profile.getStatus() == DriverStatus.RESERVED || profile.getStatus() == DriverStatus.ON_RIDE) {
                throw new ConflictException("Cannot go offline while reserved or on an active ride");
            }

            if (profile.getStatus() == DriverStatus.AVAILABLE) {
                int updated = driverProfileRepository.setDriverOffline(profile.getId(), now);
                if (updated == 0) {
                    DriverProfile fresh = driverProfileRepository.findById(profile.getId()).orElse(profile);
                    if (fresh.getStatus() == DriverStatus.RESERVED || fresh.getStatus() == DriverStatus.ON_RIDE) {
                        throw new ConflictException("Cannot go offline while reserved or on an active ride");
                    }
                }
            }
        }

        DriverProfile refreshed = driverProfileRepository.findById(profile.getId()).orElseThrow();
        return DriverAvailabilityResponse.builder()
                .driverId(authenticatedUserId)
                .status(refreshed.getStatus().name())
                .online(refreshed.getStatus() != DriverStatus.OFFLINE)
                .updatedAt(refreshed.getUpdatedAt())
                .locationUpdatedAt(refreshed.getLocationUpdatedAt())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AvailableDriverResponse> findAvailableDrivers(BigDecimal lat, BigDecimal lon, Double radiusKm, Integer limit) {
        if (lat == null || lon == null) {
            throw new BadRequestException("Pickup coordinates (latitude and longitude) are required");
        }
        if (lat.compareTo(BigDecimal.valueOf(-90.0)) < 0 || lat.compareTo(BigDecimal.valueOf(90.0)) > 0) {
            throw new BadRequestException("Latitude must be between -90.0 and 90.0");
        }
        if (lon.compareTo(BigDecimal.valueOf(-180.0)) < 0 || lon.compareTo(BigDecimal.valueOf(180.0)) > 0) {
            throw new BadRequestException("Longitude must be between -180.0 and 180.0");
        }

        double finalRadius = (radiusKm != null) ? radiusKm : 5.0;
        if (finalRadius <= 0 || finalRadius > 20.0) {
            throw new BadRequestException("Radius must be strictly positive and no greater than 20.0 km");
        }

        int finalLimit = (limit != null) ? limit : 10;
        if (finalLimit < 1 || finalLimit > 50) {
            throw new BadRequestException("Limit must be between 1 and 50");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime cutoffTime = now.minusSeconds(maxLocationAgeSeconds);

        HaversineDistanceCalculator.GeoBoundingBox bbox = distanceCalculator.calculateBoundingBox(lat, lon, finalRadius);

        List<DriverProfile> candidates;
        if (bbox.crossesAntimeridian()) {
            candidates = driverProfileRepository.findCandidatesInAntimeridianBoundingBox(
                    now, cutoffTime, bbox.minLat(), bbox.maxLat(), bbox.minLon(), bbox.maxLon()
            );
        } else {
            candidates = driverProfileRepository.findCandidatesInStandardBoundingBox(
                    now, cutoffTime, bbox.minLat(), bbox.maxLat(), bbox.minLon(), bbox.maxLon()
            );
        }

        return candidates.stream()
                .map(d -> {
                    double dist = distanceCalculator.calculateDistance(lat, lon, d.getCurrentLatitude(), d.getCurrentLongitude());
                    return AvailableDriverResponse.builder()
                            .driverId(d.getUser().getId())
                            .vehicleModel(d.getVehicleModel())
                            .licensePlate(d.getLicensePlate())
                            .distanceKm(dist)
                            .ratingAvg(d.getRatingAvg())
                            .build();
                })
                .filter(resp -> resp.getDistanceKm() <= finalRadius)
                .sorted(Comparator.comparing(AvailableDriverResponse::getDistanceKm))
                .limit(finalLimit)
                .toList();
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DriverReservationResponse reserveDriver(Long driverUserId, DriverReservationRequest request) {
        log.info("Attempting to reserve driverUserId: {} for rideId: {}", driverUserId, request.getRideId());

        DriverProfile profile = driverProfileRepository.findByUserId(driverUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found for user id: " + driverUserId));

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiresAt = now.plusSeconds(30);

        int updated = driverProfileRepository.acquireReservationLease(profile.getId(), request.getRideId(), expiresAt, now);
        if (updated == 1) {
            return DriverReservationResponse.builder()
                    .driverId(driverUserId)
                    .status("RESERVED")
                    .reservedByRideId(request.getRideId())
                    .reservationExpiresAt(expiresAt)
                    .leaseDurationSeconds(30)
                    .build();
        }

        throw new ConflictException("Driver is unavailable, offline, or already reserved");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DriverConfirmRideResponse confirmRide(Long driverUserId, DriverActionRequest request) {
        log.info("Attempting to confirm ride assignment for driverUserId: {}, rideId: {}", driverUserId, request.getRideId());

        DriverProfile profile = driverProfileRepository.findByUserId(driverUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found for user id: " + driverUserId));

        LocalDateTime now = LocalDateTime.now(clock);
        int updated = driverProfileRepository.confirmRideAssignment(profile.getId(), request.getRideId(), now);
        if (updated == 1) {
            return DriverConfirmRideResponse.builder()
                    .driverId(driverUserId)
                    .status("ON_RIDE")
                    .activeRideId(request.getRideId())
                    .updatedAt(now)
                    .build();
        }

        throw new ConflictException("Reservation lease has expired or was claimed by another ride");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DriverReleaseResponse releaseReservation(Long driverUserId, DriverReleaseRequest request) {
        log.info("Attempting to release reservation for driverUserId: {}, rideId: {}", driverUserId, request.getRideId());

        DriverProfile profile = driverProfileRepository.findByUserId(driverUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found for user id: " + driverUserId));

        LocalDateTime now = LocalDateTime.now(clock);
        int updated = driverProfileRepository.releaseReservation(profile.getId(), request.getRideId(), now);

        if (updated == 1) {
            return DriverReleaseResponse.builder()
                    .driverId(driverUserId)
                    .status("RELEASED")
                    .driverStatus("AVAILABLE")
                    .build();
        }

        // rowsAffected == 0: inspect current driver state to execute exact decision matrix
        DriverProfile fresh = driverProfileRepository.findById(profile.getId()).orElse(profile);

        // Branch 2: Idempotent duplicate retry of the most recent release
        if (request.getRideId().equals(fresh.getLastReleasedByRideId())) {
            return DriverReleaseResponse.builder()
                    .driverId(driverUserId)
                    .status("ALREADY_RELEASED")
                    .driverStatus(fresh.getStatus().name())
                    .build();
        }

        // Branch 3: Mismatched ride reservation
        if (fresh.getStatus() == DriverStatus.RESERVED && !request.getRideId().equals(fresh.getReservedByRideId())) {
            throw new ConflictException("Driver is reserved by another ride: " + fresh.getReservedByRideId());
        }

        // Branch 4: Driver on active trip
        if (fresh.getStatus() == DriverStatus.ON_RIDE) {
            throw new ConflictException("Driver is currently on an active trip");
        }

        // Branch 5: Driver offline
        if (fresh.getStatus() == DriverStatus.OFFLINE) {
            throw new ConflictException("Driver is currently offline");
        }

        // Branch 6: Stale duplicate after a subsequent ride has modified/released the driver
        if (fresh.getStatus() == DriverStatus.AVAILABLE) {
            throw new ConflictException("Stale release request: driver lease has evolved and was released by a subsequent ride");
        }

        throw new ConflictException("Cannot release driver reservation in status: " + fresh.getStatus());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DriverCompleteRideResponse completeRide(Long driverUserId, DriverActionRequest request) {
        log.info("Attempting to complete ride for driverUserId: {}, rideId: {}", driverUserId, request.getRideId());

        DriverProfile profile = driverProfileRepository.findByUserId(driverUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver profile not found for user id: " + driverUserId));

        LocalDateTime now = LocalDateTime.now(clock);
        int updated = driverProfileRepository.completeRideAssignment(profile.getId(), request.getRideId(), now);

        if (updated == 1) {
            return DriverCompleteRideResponse.builder()
                    .driverId(driverUserId)
                    .status("COMPLETED")
                    .driverStatus("AVAILABLE")
                    .build();
        }

        // rowsAffected == 0: check if driver is on another ride or if request is ambiguous
        DriverProfile fresh = driverProfileRepository.findById(profile.getId()).orElse(profile);
        if (fresh.getStatus() == DriverStatus.ON_RIDE && !request.getRideId().equals(fresh.getActiveRideId())) {
            throw new ConflictException("Driver is currently active on another ride: " + fresh.getActiveRideId());
        }

        // Strictly return 409 Conflict for ambiguous duplicate completion requests where the system cannot prove prior completion
        throw new ConflictException("Cannot complete ride: driver is not currently active on this ride");
    }
}
