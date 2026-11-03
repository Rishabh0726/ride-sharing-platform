package com.rideshare.user_service.repository;

import com.rideshare.user_service.entity.DriverProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DriverProfileRepository extends JpaRepository<DriverProfile, Long> {

    Optional<DriverProfile> findByUserId(Long userId);

    boolean existsByLicensePlate(String licensePlate);

    // 1. Availability toggle to OFFLINE (Atomic conditional update)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE DriverProfile d SET " +
           "d.status = com.rideshare.user_service.entity.DriverStatus.OFFLINE, " +
           "d.version = d.version + 1, " +
           "d.updatedAt = :now " +
           "WHERE d.id = :driverId AND d.status = com.rideshare.user_service.entity.DriverStatus.AVAILABLE")
    int setDriverOffline(
            @Param("driverId") Long driverId,
            @Param("now") LocalDateTime now
    );

    // 2. Availability toggle to AVAILABLE & GPS update (Atomic conditional update)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE DriverProfile d SET " +
           "d.status = com.rideshare.user_service.entity.DriverStatus.AVAILABLE, " +
           "d.currentLatitude = :latitude, " +
           "d.currentLongitude = :longitude, " +
           "d.locationUpdatedAt = :now, " +
           "d.version = d.version + 1, " +
           "d.updatedAt = :now " +
           "WHERE d.id = :driverId AND d.status = com.rideshare.user_service.entity.DriverStatus.OFFLINE")
    int setDriverAvailable(
            @Param("driverId") Long driverId,
            @Param("latitude") BigDecimal latitude,
            @Param("longitude") BigDecimal longitude,
            @Param("now") LocalDateTime now
    );

    // 3. Location refresh while already AVAILABLE
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE DriverProfile d SET " +
           "d.currentLatitude = :latitude, " +
           "d.currentLongitude = :longitude, " +
           "d.locationUpdatedAt = :now, " +
           "d.version = d.version + 1, " +
           "d.updatedAt = :now " +
           "WHERE d.id = :driverId AND d.status = com.rideshare.user_service.entity.DriverStatus.AVAILABLE")
    int updateDriverLocation(
            @Param("driverId") Long driverId,
            @Param("latitude") BigDecimal latitude,
            @Param("longitude") BigDecimal longitude,
            @Param("now") LocalDateTime now
    );

    // 4. Candidate Discovery (Standard Bounding Box)
    // Strictly requires d.locationUpdatedAt >= :cutoffTime to enforce GPS freshness
    @Query("SELECT d FROM DriverProfile d WHERE " +
           "(d.status = com.rideshare.user_service.entity.DriverStatus.AVAILABLE OR " +
           "(d.status = com.rideshare.user_service.entity.DriverStatus.RESERVED AND d.reservationExpiresAt <= :now)) " +
           "AND d.currentLatitude IS NOT NULL AND d.currentLongitude IS NOT NULL " +
           "AND d.locationUpdatedAt IS NOT NULL AND d.locationUpdatedAt >= :cutoffTime " +
           "AND d.currentLatitude BETWEEN :minLat AND :maxLat " +
           "AND d.currentLongitude BETWEEN :minLon AND :maxLon")
    List<DriverProfile> findCandidatesInStandardBoundingBox(
            @Param("now") LocalDateTime now,
            @Param("cutoffTime") LocalDateTime cutoffTime,
            @Param("minLat") BigDecimal minLat,
            @Param("maxLat") BigDecimal maxLat,
            @Param("minLon") BigDecimal minLon,
            @Param("maxLon") BigDecimal maxLon
    );

    // 5. Candidate Discovery (Antimeridian Crossing Bounding Box)
    @Query("SELECT d FROM DriverProfile d WHERE " +
           "(d.status = com.rideshare.user_service.entity.DriverStatus.AVAILABLE OR " +
           "(d.status = com.rideshare.user_service.entity.DriverStatus.RESERVED AND d.reservationExpiresAt <= :now)) " +
           "AND d.currentLatitude IS NOT NULL AND d.currentLongitude IS NOT NULL " +
           "AND d.locationUpdatedAt IS NOT NULL AND d.locationUpdatedAt >= :cutoffTime " +
           "AND d.currentLatitude BETWEEN :minLat AND :maxLat " +
           "AND (d.currentLongitude >= :minLon OR d.currentLongitude <= :maxLon)")
    List<DriverProfile> findCandidatesInAntimeridianBoundingBox(
            @Param("now") LocalDateTime now,
            @Param("cutoffTime") LocalDateTime cutoffTime,
            @Param("minLat") BigDecimal minLat,
            @Param("maxLat") BigDecimal maxLat,
            @Param("minLon") BigDecimal minLon,
            @Param("maxLon") BigDecimal maxLon
    );

    // 6. Atomic Reservation Lease Acquisition
    // Strict boundary: reclaimable if status=AVAILABLE OR (status=RESERVED AND reservationExpiresAt <= :now)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE DriverProfile d SET " +
           "d.status = com.rideshare.user_service.entity.DriverStatus.RESERVED, " +
           "d.reservedByRideId = :rideId, " +
           "d.reservationExpiresAt = :expiresAt, " +
           "d.version = d.version + 1, " +
           "d.updatedAt = :now " +
           "WHERE d.id = :driverId AND " +
           "(d.status = com.rideshare.user_service.entity.DriverStatus.AVAILABLE OR " +
           "(d.status = com.rideshare.user_service.entity.DriverStatus.RESERVED AND d.reservationExpiresAt <= :now))")
    int acquireReservationLease(
            @Param("driverId") Long driverId,
            @Param("rideId") Long rideId,
            @Param("expiresAt") LocalDateTime expiresAt,
            @Param("now") LocalDateTime now
    );

    // 7. Atomic Lease Confirmation
    // Strict boundary: confirmation requires reservationExpiresAt > :now (strictly before expiry)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE DriverProfile d SET " +
           "d.status = com.rideshare.user_service.entity.DriverStatus.ON_RIDE, " +
           "d.activeRideId = :rideId, " +
           "d.reservedByRideId = null, " +
           "d.reservationExpiresAt = null, " +
           "d.version = d.version + 1, " +
           "d.updatedAt = :now " +
           "WHERE d.id = :driverId AND d.status = com.rideshare.user_service.entity.DriverStatus.RESERVED " +
           "AND d.reservedByRideId = :rideId AND d.reservationExpiresAt > :now")
    int confirmRideAssignment(
            @Param("driverId") Long driverId,
            @Param("rideId") Long rideId,
            @Param("now") LocalDateTime now
    );

    // 8. Atomic Conditional Release
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE DriverProfile d SET " +
           "d.status = com.rideshare.user_service.entity.DriverStatus.AVAILABLE, " +
           "d.reservedByRideId = null, " +
           "d.reservationExpiresAt = null, " +
           "d.lastReleasedByRideId = :rideId, " +
           "d.version = d.version + 1, " +
           "d.updatedAt = :now " +
           "WHERE d.id = :driverId AND d.status = com.rideshare.user_service.entity.DriverStatus.RESERVED " +
           "AND d.reservedByRideId = :rideId")
    int releaseReservation(
            @Param("driverId") Long driverId,
            @Param("rideId") Long rideId,
            @Param("now") LocalDateTime now
    );

    // 9. Atomic Ride Completion
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE DriverProfile d SET " +
           "d.status = com.rideshare.user_service.entity.DriverStatus.AVAILABLE, " +
           "d.activeRideId = null, " +
           "d.version = d.version + 1, " +
           "d.updatedAt = :now " +
           "WHERE d.id = :driverId AND d.status = com.rideshare.user_service.entity.DriverStatus.ON_RIDE " +
           "AND d.activeRideId = :rideId")
    int completeRideAssignment(
            @Param("driverId") Long driverId,
            @Param("rideId") Long rideId,
            @Param("now") LocalDateTime now
    );
}
