package com.rideshare.ride_service.service;

import com.rideshare.ride_service.entity.Ride;
import com.rideshare.ride_service.entity.RideEvent;
import com.rideshare.ride_service.entity.RideStatus;
import com.rideshare.ride_service.exception.ConflictException;
import com.rideshare.ride_service.exception.ResourceNotFoundException;
import com.rideshare.ride_service.repository.RideEventRepository;
import com.rideshare.ride_service.repository.RideRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RideStateTransitionService {

    private final RideRepository rideRepository;
    private final RideEventRepository rideEventRepository;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Ride saveNewRide(Ride ride, Long passengerId) {
        Ride saved = rideRepository.save(ride);

        RideEvent event = RideEvent.builder()
                .ride(saved)
                .fromStatus(null)
                .toStatus(RideStatus.REQUESTED.name())
                .actorId(passengerId)
                .actorRole("ROLE_PASSENGER")
                .remarks("Ride requested by passenger")
                .build();
        rideEventRepository.save(event);

        return saved;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean claimAndAcceptRideLocally(Long rideId, Long driverId, Long expectedVersion) {
        LocalDateTime now = LocalDateTime.now(clock);
        int updated = rideRepository.claimAndAcceptRide(rideId, driverId, now, expectedVersion);

        if (updated == 1) {
            Ride ride = rideRepository.findById(rideId).orElseThrow();
            RideEvent event = RideEvent.builder()
                    .ride(ride)
                    .fromStatus(RideStatus.REQUESTED.name())
                    .toStatus(RideStatus.ACCEPTED.name())
                    .actorId(driverId)
                    .actorRole("ROLE_DRIVER")
                    .remarks("Driver claimed and accepted ride")
                    .build();
            rideEventRepository.save(event);
            return true;
        }

        return false;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean rollbackAcceptanceLocally(Long rideId, Long driverId, String reason) {
        LocalDateTime now = LocalDateTime.now(clock);
        int updated = rideRepository.rollbackAcceptance(rideId, driverId, now);

        if (updated == 1) {
            Ride ride = rideRepository.findById(rideId).orElseThrow();
            RideEvent event = RideEvent.builder()
                    .ride(ride)
                    .fromStatus(RideStatus.ACCEPTED.name())
                    .toStatus(RideStatus.REQUESTED.name())
                    .actorId(driverId)
                    .actorRole("ROLE_INTERNAL_SERVICE")
                    .remarks(reason != null ? reason : "Acceptance rolled back due to lease confirmation failure")
                    .build();
            rideEventRepository.save(event);
            return true;
        }

        return false;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Ride transitionToDriverArriving(Long rideId, Long driverId) {
        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> new ResourceNotFoundException("Ride not found with id: " + rideId));

        if (ride.getStatus() != RideStatus.ACCEPTED) {
            throw new ConflictException("Cannot transition to DRIVER_ARRIVING: ride status is " + ride.getStatus());
        }
        if (ride.getDriverId() == null || !ride.getDriverId().equals(driverId)) {
            throw new ConflictException("Driver is not assigned to this ride");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int updated = rideRepository.transitionToDriverArriving(rideId, driverId, now, ride.getVersion());
        if (updated == 0) {
            throw new ConflictException("Concurrent modification: unable to transition ride to DRIVER_ARRIVING");
        }

        Ride updatedRide = rideRepository.findById(rideId).orElseThrow();
        RideEvent event = RideEvent.builder()
                .ride(updatedRide)
                .fromStatus(RideStatus.ACCEPTED.name())
                .toStatus(RideStatus.DRIVER_ARRIVING.name())
                .actorId(driverId)
                .actorRole("ROLE_DRIVER")
                .remarks("Driver arrived at pickup location")
                .build();
        rideEventRepository.save(event);

        return updatedRide;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Ride transitionToInProgress(Long rideId, Long driverId) {
        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> new ResourceNotFoundException("Ride not found with id: " + rideId));

        if (ride.getStatus() != RideStatus.DRIVER_ARRIVING) {
            throw new ConflictException("Cannot start ride: ride status is " + ride.getStatus());
        }
        if (ride.getDriverId() == null || !ride.getDriverId().equals(driverId)) {
            throw new ConflictException("Driver is not assigned to this ride");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int updated = rideRepository.transitionToInProgress(rideId, driverId, now, ride.getVersion());
        if (updated == 0) {
            throw new ConflictException("Concurrent modification: unable to transition ride to IN_PROGRESS");
        }

        Ride updatedRide = rideRepository.findById(rideId).orElseThrow();
        RideEvent event = RideEvent.builder()
                .ride(updatedRide)
                .fromStatus(RideStatus.DRIVER_ARRIVING.name())
                .toStatus(RideStatus.IN_PROGRESS.name())
                .actorId(driverId)
                .actorRole("ROLE_DRIVER")
                .remarks("Ride trip started")
                .build();
        rideEventRepository.save(event);

        return updatedRide;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Ride transitionToCompleted(Long rideId, Long driverId, BigDecimal finalFare) {
        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> new ResourceNotFoundException("Ride not found with id: " + rideId));

        if (ride.getStatus() != RideStatus.IN_PROGRESS) {
            throw new ConflictException("Cannot complete ride: ride status is " + ride.getStatus());
        }
        if (ride.getDriverId() == null || !ride.getDriverId().equals(driverId)) {
            throw new ConflictException("Driver is not assigned to this ride");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int updated = rideRepository.transitionToCompleted(rideId, driverId, finalFare, now, ride.getVersion());
        if (updated == 0) {
            throw new ConflictException("Concurrent modification: unable to transition ride to COMPLETED");
        }

        Ride updatedRide = rideRepository.findById(rideId).orElseThrow();
        RideEvent event = RideEvent.builder()
                .ride(updatedRide)
                .fromStatus(RideStatus.IN_PROGRESS.name())
                .toStatus(RideStatus.COMPLETED.name())
                .actorId(driverId)
                .actorRole("ROLE_DRIVER")
                .remarks("Ride completed successfully with final fare: " + finalFare)
                .build();
        rideEventRepository.save(event);

        return updatedRide;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Ride transitionToCancelled(Long rideId, Collection<RideStatus> allowedStatuses, 
                                      String cancelledBy, String reason, Long actorId, String actorRole) {
        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> new ResourceNotFoundException("Ride not found with id: " + rideId));

        if (!allowedStatuses.contains(ride.getStatus())) {
            throw new ConflictException("Cannot cancel ride: status is already " + ride.getStatus());
        }

        RideStatus fromStatus = ride.getStatus();
        LocalDateTime now = LocalDateTime.now(clock);
        int updated = rideRepository.transitionToCancelled(rideId, allowedStatuses, cancelledBy, reason, now, ride.getVersion());
        if (updated == 0) {
            throw new ConflictException("Concurrent modification: unable to cancel ride");
        }

        Ride updatedRide = rideRepository.findById(rideId).orElseThrow();
        RideEvent event = RideEvent.builder()
                .ride(updatedRide)
                .fromStatus(fromStatus.name())
                .toStatus(RideStatus.CANCELLED.name())
                .actorId(actorId)
                .actorRole(actorRole)
                .remarks("Ride cancelled by " + cancelledBy + (reason != null ? ": " + reason : ""))
                .build();
        rideEventRepository.save(event);

        return updatedRide;
    }
}
