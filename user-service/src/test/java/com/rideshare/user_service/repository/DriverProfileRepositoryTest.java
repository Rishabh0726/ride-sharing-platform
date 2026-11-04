package com.rideshare.user_service.repository;

import com.rideshare.user_service.entity.DriverProfile;
import com.rideshare.user_service.entity.DriverStatus;
import com.rideshare.user_service.entity.Role;
import com.rideshare.user_service.entity.User;
import com.rideshare.user_service.entity.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class DriverProfileRepositoryTest {

    @Autowired
    private DriverProfileRepository driverProfileRepository;

    @Autowired
    private UserRepository userRepository;

    private DriverProfile savedProfile;

    @BeforeEach
    void setUp() {
        User user = User.builder()
                .email("driver.repo@example.com")
                .passwordHash("hashedPass")
                .firstName("Test")
                .lastName("Driver")
                .phoneNumber("+919876543299")
                .role(Role.ROLE_DRIVER)
                .status(UserStatus.ACTIVE)
                .build();
        userRepository.save(user);

        DriverProfile profile = DriverProfile.builder()
                .user(user)
                .vehicleModel("Swift Dzire")
                .licensePlate("KA-01-MJ-9999")
                .status(DriverStatus.AVAILABLE)
                .currentLatitude(BigDecimal.valueOf(12.9716))
                .currentLongitude(BigDecimal.valueOf(77.5946))
                .locationUpdatedAt(LocalDateTime.now())
                .ratingAvg(BigDecimal.valueOf(4.90))
                .build();
        savedProfile = driverProfileRepository.save(profile);
    }

    @Test
    @DisplayName("Expiry equality boundary: at equality (t == expiresAt), confirm fails (0 rows) and reclaim succeeds (1 row)")
    void shouldEnforceStrictExpiryEqualityBoundary() {
        LocalDateTime expiryTime = LocalDateTime.of(2026, 10, 21, 2, 0, 30);
        LocalDateTime nowAtEquality = expiryTime;

        // Set driver to RESERVED with reservationExpiresAt = expiryTime
        savedProfile.setStatus(DriverStatus.RESERVED);
        savedProfile.setReservedByRideId(501L);
        savedProfile.setReservationExpiresAt(expiryTime);
        driverProfileRepository.saveAndFlush(savedProfile);

        // 1. Confirm at t == expiresAt must FAIL because predicate requires reservationExpiresAt > :now
        int confirmRows = driverProfileRepository.confirmRideAssignment(savedProfile.getId(), 501L, nowAtEquality);
        assertThat(confirmRows).isEqualTo(0);

        // 2. Reclaim at t == expiresAt must SUCCEED because predicate allows reservationExpiresAt <= :now
        LocalDateTime newExpiry = nowAtEquality.plusSeconds(30);
        int reclaimRows = driverProfileRepository.acquireReservationLease(savedProfile.getId(), 502L, newExpiry, nowAtEquality);
        assertThat(reclaimRows).isEqualTo(1);

        DriverProfile reloaded = driverProfileRepository.findById(savedProfile.getId()).orElseThrow();
        assertThat(reloaded.getReservedByRideId()).isEqualTo(502L);
        assertThat(reloaded.getReservationExpiresAt()).isEqualTo(newExpiry);
    }

    @Test
    @DisplayName("Confirm succeeds when now < expiresAt (strictly before expiration)")
    void shouldConfirmBeforeExpiry() {
        LocalDateTime expiryTime = LocalDateTime.of(2026, 10, 21, 2, 0, 30);
        LocalDateTime nowBeforeExpiry = expiryTime.minusSeconds(1);

        savedProfile.setStatus(DriverStatus.RESERVED);
        savedProfile.setReservedByRideId(501L);
        savedProfile.setReservationExpiresAt(expiryTime);
        driverProfileRepository.saveAndFlush(savedProfile);

        int confirmRows = driverProfileRepository.confirmRideAssignment(savedProfile.getId(), 501L, nowBeforeExpiry);
        assertThat(confirmRows).isEqualTo(1);

        DriverProfile reloaded = driverProfileRepository.findById(savedProfile.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DriverStatus.ON_RIDE);
        assertThat(reloaded.getActiveRideId()).isEqualTo(501L);
        assertThat(reloaded.getReservedByRideId()).isNull();
    }

    @Test
    @DisplayName("Persistence context clearing: @Modifying updates version and reloads fresh row without stale entity cache")
    void shouldClearPersistenceContextAndIncrementVersionOnModifyingUpdate() {
        Long initialVersion = savedProfile.getVersion();
        LocalDateTime now = LocalDateTime.now();

        int rows = driverProfileRepository.setDriverOffline(savedProfile.getId(), now);
        assertThat(rows).isEqualTo(1);

        DriverProfile fresh = driverProfileRepository.findById(savedProfile.getId()).orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo(DriverStatus.OFFLINE);
        assertThat(fresh.getVersion()).isEqualTo(initialVersion + 1);
    }

    @Test
    @DisplayName("Offline vs Reserve race: when driver is OFFLINE, acquireReservationLease returns 0 rows")
    void shouldPreventReservingOfflineDriver() {
        savedProfile.setStatus(DriverStatus.OFFLINE);
        driverProfileRepository.saveAndFlush(savedProfile);

        LocalDateTime now = LocalDateTime.now();
        int rows = driverProfileRepository.acquireReservationLease(savedProfile.getId(), 501L, now.plusSeconds(30), now);

        assertThat(rows).isEqualTo(0);
    }

    @Test
    @DisplayName("Candidate discovery excludes drivers with stale GPS coordinates (locationUpdatedAt < cutoffTime)")
    void shouldExcludeStaleGpsCoordinatesFromDiscovery() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 21, 2, 0, 0);
        LocalDateTime cutoff = now.minusSeconds(600); // 10 minutes max age

        // Set locationUpdatedAt to 15 minutes ago (stale)
        savedProfile.setLocationUpdatedAt(now.minusSeconds(900));
        savedProfile.setStatus(DriverStatus.AVAILABLE);
        driverProfileRepository.saveAndFlush(savedProfile);

        List<DriverProfile> candidates = driverProfileRepository.findCandidatesInStandardBoundingBox(
                now, cutoff,
                BigDecimal.valueOf(12.0), BigDecimal.valueOf(14.0),
                BigDecimal.valueOf(76.0), BigDecimal.valueOf(78.0)
        );

        assertThat(candidates).isEmpty();

        // Update locationUpdatedAt to fresh (1 minute ago)
        savedProfile.setLocationUpdatedAt(now.minusSeconds(60));
        driverProfileRepository.saveAndFlush(savedProfile);

        List<DriverProfile> freshCandidates = driverProfileRepository.findCandidatesInStandardBoundingBox(
                now, cutoff,
                BigDecimal.valueOf(12.0), BigDecimal.valueOf(14.0),
                BigDecimal.valueOf(76.0), BigDecimal.valueOf(78.0)
        );

        assertThat(freshCandidates).hasSize(1);
        assertThat(freshCandidates.get(0).getUser()).isNotNull();
        assertThat(freshCandidates.get(0).getUser().getId()).isEqualTo(savedProfile.getUser().getId());
    }

    @Test
    @DisplayName("Antimeridian candidate discovery fetches associated user without lazy-loading")
    void shouldFindCandidatesInAntimeridianBoundingBoxAndFetchUser() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 21, 2, 0, 0);
        LocalDateTime cutoff = now.minusSeconds(600);

        savedProfile.setLocationUpdatedAt(now.minusSeconds(60));
        savedProfile.setStatus(DriverStatus.AVAILABLE);
        savedProfile.setCurrentLatitude(BigDecimal.valueOf(12.9716));
        savedProfile.setCurrentLongitude(BigDecimal.valueOf(179.5));
        driverProfileRepository.saveAndFlush(savedProfile);

        List<DriverProfile> candidates = driverProfileRepository.findCandidatesInAntimeridianBoundingBox(
                now, cutoff,
                BigDecimal.valueOf(12.0), BigDecimal.valueOf(14.0),
                BigDecimal.valueOf(170.0), BigDecimal.valueOf(-170.0)
        );

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getUser()).isNotNull();
        assertThat(candidates.get(0).getUser().getId()).isEqualTo(savedProfile.getUser().getId());
    }
}
