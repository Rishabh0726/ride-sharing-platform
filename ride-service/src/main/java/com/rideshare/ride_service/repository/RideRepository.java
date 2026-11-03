package com.rideshare.ride_service.repository;

import com.rideshare.ride_service.entity.Ride;
import com.rideshare.ride_service.entity.RideStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RideRepository extends JpaRepository<Ride, Long> {

    Optional<Ride> findByRideUuid(String rideUuid);

    Optional<Ride> findByFareQuoteToken(String fareQuoteToken);

    List<Ride> findByPassengerIdOrderByCreatedAtDesc(Long passengerId);

    Optional<Ride> findFirstByDriverIdAndStatusInOrderByCreatedAtDesc(Long driverId, Collection<RideStatus> statuses);

    boolean existsByDriverIdAndStatusIn(Long driverId, Collection<RideStatus> statuses);

    boolean existsByPassengerIdAndStatusIn(Long passengerId, Collection<RideStatus> statuses);

    List<Ride> findByStatusOrderByCreatedAtDescIdDesc(RideStatus status);

    List<Ride> findByDriverIdAndStatusOrderByCreatedAtDescIdDesc(Long driverId, RideStatus status);

    // Atomic driver acceptance (REQUESTED -> ACCEPTED)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Ride r SET " +
           "r.driverId = :driverId, " +
           "r.status = com.rideshare.ride_service.entity.RideStatus.ACCEPTED, " +
           "r.acceptedAt = :now, " +
           "r.version = r.version + 1, " +
           "r.updatedAt = :now " +
           "WHERE r.id = :rideId AND r.status = com.rideshare.ride_service.entity.RideStatus.REQUESTED " +
           "AND r.version = :expectedVersion")
    int claimAndAcceptRide(
            @Param("rideId") Long rideId,
            @Param("driverId") Long driverId,
            @Param("now") LocalDateTime now,
            @Param("expectedVersion") Long expectedVersion
    );

    // Atomic acceptance rollback on confirmation failure (ACCEPTED -> REQUESTED)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Ride r SET " +
           "r.driverId = null, " +
           "r.status = com.rideshare.ride_service.entity.RideStatus.REQUESTED, " +
           "r.acceptedAt = null, " +
           "r.version = r.version + 1, " +
           "r.updatedAt = :now " +
           "WHERE r.id = :rideId AND r.status = com.rideshare.ride_service.entity.RideStatus.ACCEPTED " +
           "AND r.driverId = :driverId")
    int rollbackAcceptance(
            @Param("rideId") Long rideId,
            @Param("driverId") Long driverId,
            @Param("now") LocalDateTime now
    );

    // Atomic arrival (ACCEPTED -> DRIVER_ARRIVING)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Ride r SET " +
           "r.status = com.rideshare.ride_service.entity.RideStatus.DRIVER_ARRIVING, " +
           "r.arrivedAt = :now, " +
           "r.version = r.version + 1, " +
           "r.updatedAt = :now " +
           "WHERE r.id = :rideId AND r.status = com.rideshare.ride_service.entity.RideStatus.ACCEPTED " +
           "AND r.driverId = :driverId AND r.version = :expectedVersion")
    int transitionToDriverArriving(
            @Param("rideId") Long rideId,
            @Param("driverId") Long driverId,
            @Param("now") LocalDateTime now,
            @Param("expectedVersion") Long expectedVersion
    );

    // Atomic ride start (DRIVER_ARRIVING -> IN_PROGRESS)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Ride r SET " +
           "r.status = com.rideshare.ride_service.entity.RideStatus.IN_PROGRESS, " +
           "r.startedAt = :now, " +
           "r.version = r.version + 1, " +
           "r.updatedAt = :now " +
           "WHERE r.id = :rideId AND r.status = com.rideshare.ride_service.entity.RideStatus.DRIVER_ARRIVING " +
           "AND r.driverId = :driverId AND r.version = :expectedVersion")
    int transitionToInProgress(
            @Param("rideId") Long rideId,
            @Param("driverId") Long driverId,
            @Param("now") LocalDateTime now,
            @Param("expectedVersion") Long expectedVersion
    );

    // Atomic completion (IN_PROGRESS -> COMPLETED) with finalFare assignment
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Ride r SET " +
           "r.status = com.rideshare.ride_service.entity.RideStatus.COMPLETED, " +
           "r.completedAt = :now, " +
           "r.finalFare = :finalFare, " +
           "r.version = r.version + 1, " +
           "r.updatedAt = :now " +
           "WHERE r.id = :rideId AND r.status = com.rideshare.ride_service.entity.RideStatus.IN_PROGRESS " +
           "AND r.driverId = :driverId AND r.version = :expectedVersion")
    int transitionToCompleted(
            @Param("rideId") Long rideId,
            @Param("driverId") Long driverId,
            @Param("finalFare") BigDecimal finalFare,
            @Param("now") LocalDateTime now,
            @Param("expectedVersion") Long expectedVersion
    );

    // Atomic cancellation (* -> CANCELLED)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Ride r SET " +
           "r.status = com.rideshare.ride_service.entity.RideStatus.CANCELLED, " +
           "r.cancelledAt = :now, " +
           "r.cancelledBy = :cancelledBy, " +
           "r.cancellationReason = :reason, " +
           "r.version = r.version + 1, " +
           "r.updatedAt = :now " +
           "WHERE r.id = :rideId AND r.status IN :allowedStatuses " +
           "AND r.version = :expectedVersion")
    int transitionToCancelled(
            @Param("rideId") Long rideId,
            @Param("allowedStatuses") Collection<RideStatus> allowedStatuses,
            @Param("cancelledBy") String cancelledBy,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now,
            @Param("expectedVersion") Long expectedVersion
    );
}
