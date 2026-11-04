package com.rideshare.user_service.service;

import com.rideshare.user_service.dto.request.DriverActionRequest;
import com.rideshare.user_service.dto.request.DriverAvailabilityRequest;
import com.rideshare.user_service.dto.request.DriverReleaseRequest;
import com.rideshare.user_service.dto.request.DriverReservationRequest;
import com.rideshare.user_service.dto.response.*;
import com.rideshare.user_service.entity.DriverProfile;
import com.rideshare.user_service.entity.DriverStatus;
import com.rideshare.user_service.entity.User;
import com.rideshare.user_service.exception.BadRequestException;
import com.rideshare.user_service.exception.ConflictException;
import com.rideshare.user_service.exception.ResourceNotFoundException;
import com.rideshare.user_service.repository.DriverProfileRepository;
import com.rideshare.user_service.util.HaversineDistanceCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DriverServiceTest {

    @Mock
    private DriverProfileRepository driverProfileRepository;

    @Mock
    private HaversineDistanceCalculator distanceCalculator;

    private Clock fixedClock;
    private DriverServiceImpl driverService;

    private final Instant fixedInstant = Instant.parse("2026-10-21T02:00:00Z");
    private final LocalDateTime fixedNow = LocalDateTime.ofInstant(fixedInstant, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);
        driverService = new DriverServiceImpl(driverProfileRepository, distanceCalculator, fixedClock);
    }

    @Test
    @DisplayName("Toggle availability to ONLINE with coordinates transitions OFFLINE driver to AVAILABLE")
    void shouldToggleOnlineSuccessfully() {
        DriverProfile profile = DriverProfile.builder()
                .id(5L)
                .status(DriverStatus.OFFLINE)
                .build();

        DriverProfile updatedProfile = DriverProfile.builder()
                .id(5L)
                .status(DriverStatus.AVAILABLE)
                .currentLatitude(BigDecimal.valueOf(12.9716))
                .currentLongitude(BigDecimal.valueOf(77.5946))
                .locationUpdatedAt(fixedNow)
                .updatedAt(fixedNow)
                .build();

        when(driverProfileRepository.findByUserId(42L)).thenReturn(Optional.of(profile));
        when(driverProfileRepository.setDriverAvailable(eq(5L), any(), any(), eq(fixedNow))).thenReturn(1);
        when(driverProfileRepository.findById(5L)).thenReturn(Optional.of(updatedProfile));

        DriverAvailabilityRequest request = DriverAvailabilityRequest.builder()
                .online(true)
                .latitude(BigDecimal.valueOf(12.9716))
                .longitude(BigDecimal.valueOf(77.5946))
                .build();

        DriverAvailabilityResponse response = driverService.toggleAvailability(42L, request);

        assertThat(response.getDriverId()).isEqualTo(42L);
        assertThat(response.getStatus()).isEqualTo("AVAILABLE");
        assertThat(response.getOnline()).isTrue();
        assertThat(response.getLocationUpdatedAt()).isEqualTo(fixedNow);
    }

    @Test
    @DisplayName("Toggle availability to ONLINE without coordinates throws BadRequestException")
    void shouldRejectOnlineWithoutCoordinates() {
        DriverProfile profile = DriverProfile.builder()
                .id(1L)
                .status(DriverStatus.OFFLINE)
                .build();

        when(driverProfileRepository.findByUserId(10L)).thenReturn(Optional.of(profile));

        DriverAvailabilityRequest request = DriverAvailabilityRequest.builder()
                .online(true)
                .latitude(null)
                .longitude(null)
                .build();

        assertThatThrownBy(() -> driverService.toggleAvailability(10L, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Latitude and longitude are required");
    }

    @Test
    @DisplayName("Toggle availability to OFFLINE while in RESERVED status throws ConflictException")
    void shouldRejectOfflineWhileReserved() {
        DriverProfile profile = DriverProfile.builder()
                .id(1L)
                .status(DriverStatus.RESERVED)
                .reservedByRideId(501L)
                .build();

        when(driverProfileRepository.findByUserId(10L)).thenReturn(Optional.of(profile));

        DriverAvailabilityRequest request = DriverAvailabilityRequest.builder()
                .online(false)
                .build();

        assertThatThrownBy(() -> driverService.toggleAvailability(10L, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Cannot go offline while reserved or on an active ride");
    }

    @Test
    @DisplayName("Toggle availability to OFFLINE while AVAILABLE transitions cleanly to OFFLINE")
    void shouldToggleOfflineSuccessfully() {
        DriverProfile profile = DriverProfile.builder()
                .id(1L)
                .status(DriverStatus.AVAILABLE)
                .build();

        DriverProfile updatedProfile = DriverProfile.builder()
                .id(1L)
                .status(DriverStatus.OFFLINE)
                .updatedAt(fixedNow)
                .build();

        when(driverProfileRepository.findByUserId(10L)).thenReturn(Optional.of(profile));
        when(driverProfileRepository.setDriverOffline(eq(1L), eq(fixedNow))).thenReturn(1);
        when(driverProfileRepository.findById(1L)).thenReturn(Optional.of(updatedProfile));

        DriverAvailabilityRequest request = DriverAvailabilityRequest.builder()
                .online(false)
                .build();

        DriverAvailabilityResponse response = driverService.toggleAvailability(10L, request);

        assertThat(response.getDriverId()).isEqualTo(10L);
        assertThat(response.getStatus()).isEqualTo("OFFLINE");
        assertThat(response.getOnline()).isFalse();
    }

    @Test
    @DisplayName("Find available drivers validates radius strictly (0 < r <= 20 km)")
    void shouldRejectInvalidRadius() {
        BigDecimal lat = BigDecimal.valueOf(12.9716);
        BigDecimal lon = BigDecimal.valueOf(77.5946);

        assertThatThrownBy(() -> driverService.findAvailableDrivers(lat, lon, 0.0, 10))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Radius must be strictly positive");

        assertThatThrownBy(() -> driverService.findAvailableDrivers(lat, lon, 25.0, 10))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no greater than 20.0 km");
    }

    @Test
    @DisplayName("Find available drivers calculates exact distance, filters and sorts by proximity")
    void shouldFindAndSortAvailableDrivers() {
        BigDecimal pickupLat = BigDecimal.valueOf(12.9716);
        BigDecimal pickupLon = BigDecimal.valueOf(77.5946);

        HaversineDistanceCalculator.GeoBoundingBox bbox = new HaversineDistanceCalculator.GeoBoundingBox(
                BigDecimal.valueOf(12.92), BigDecimal.valueOf(13.02),
                BigDecimal.valueOf(77.54), BigDecimal.valueOf(77.64),
                false
        );

        when(distanceCalculator.calculateBoundingBox(pickupLat, pickupLon, 5.0)).thenReturn(bbox);

        User u1 = User.builder().id(101L).build();
        User u2 = User.builder().id(202L).build();
        User u3Far = User.builder().id(303L).build();

        DriverProfile d1 = DriverProfile.builder().id(1L).user(u1).vehicleModel("Prius").licensePlate("DL-01").currentLatitude(BigDecimal.valueOf(12.98)).currentLongitude(BigDecimal.valueOf(77.60)).ratingAvg(BigDecimal.valueOf(4.9)).build();
        DriverProfile d2 = DriverProfile.builder().id(2L).user(u2).vehicleModel("Camry").licensePlate("DL-02").currentLatitude(BigDecimal.valueOf(12.975)).currentLongitude(BigDecimal.valueOf(77.598)).ratingAvg(BigDecimal.valueOf(4.8)).build();
        DriverProfile d3Far = DriverProfile.builder().id(3L).user(u3Far).vehicleModel("Swift").licensePlate("DL-03").currentLatitude(BigDecimal.valueOf(13.01)).currentLongitude(BigDecimal.valueOf(77.63)).ratingAvg(BigDecimal.valueOf(4.7)).build();

        when(driverProfileRepository.findCandidatesInStandardBoundingBox(eq(fixedNow), any(), any(), any(), any(), any()))
                .thenReturn(List.of(d1, d2, d3Far));

        when(distanceCalculator.calculateDistance(pickupLat, pickupLon, d1.getCurrentLatitude(), d1.getCurrentLongitude())).thenReturn(1.50);
        when(distanceCalculator.calculateDistance(pickupLat, pickupLon, d2.getCurrentLatitude(), d2.getCurrentLongitude())).thenReturn(0.50);
        when(distanceCalculator.calculateDistance(pickupLat, pickupLon, d3Far.getCurrentLatitude(), d3Far.getCurrentLongitude())).thenReturn(5.50); // Outside 5.0 km radius

        List<AvailableDriverResponse> results = driverService.findAvailableDrivers(pickupLat, pickupLon, 5.0, 10);

        // d3Far (5.5 km) must be excluded; d2 (0.5 km) must appear before d1 (1.5 km)
        assertThat(results).hasSize(2);
        assertThat(results.get(0).getDriverId()).isEqualTo(202L);
        assertThat(results.get(0).getDistanceKm()).isEqualTo(0.50);
        assertThat(results.get(1).getDriverId()).isEqualTo(101L);
        assertThat(results.get(1).getDistanceKm()).isEqualTo(1.50);
    }

    @Test
    @DisplayName("Reserve driver acquires 30-second lease when available")
    void shouldReserveDriverSuccessfully() {
        DriverProfile profile = DriverProfile.builder().id(1L).status(DriverStatus.AVAILABLE).build();
        when(driverProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));
        when(driverProfileRepository.acquireReservationLease(eq(1L), eq(501L), eq(fixedNow.plusSeconds(30)), eq(fixedNow)))
                .thenReturn(1);

        DriverReservationRequest request = DriverReservationRequest.builder().rideId(501L).build();
        DriverReservationResponse response = driverService.reserveDriver(1L, request);

        assertThat(response.getDriverId()).isEqualTo(1L);
        assertThat(response.getStatus()).isEqualTo("RESERVED");
        assertThat(response.getReservedByRideId()).isEqualTo(501L);
        assertThat(response.getReservationExpiresAt()).isEqualTo(fixedNow.plusSeconds(30));
        assertThat(response.getLeaseDurationSeconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("Reserve driver throws ConflictException when driver is unavailable or already reserved")
    void shouldRejectReserveWhenUnavailable() {
        DriverProfile profile = DriverProfile.builder().id(1L).status(DriverStatus.AVAILABLE).build();
        when(driverProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));
        when(driverProfileRepository.acquireReservationLease(eq(1L), eq(501L), any(), eq(fixedNow)))
                .thenReturn(0);

        DriverReservationRequest request = DriverReservationRequest.builder().rideId(501L).build();

        assertThatThrownBy(() -> driverService.reserveDriver(1L, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Driver is unavailable, offline, or already reserved");
    }

    @Test
    @DisplayName("Confirm ride transitions driver to ON_RIDE status")
    void shouldConfirmRideSuccessfully() {
        DriverProfile profile = DriverProfile.builder().id(1L).status(DriverStatus.RESERVED).build();
        when(driverProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));
        when(driverProfileRepository.confirmRideAssignment(eq(1L), eq(501L), eq(fixedNow)))
                .thenReturn(1);

        DriverActionRequest request = DriverActionRequest.builder().rideId(501L).build();
        DriverConfirmRideResponse response = driverService.confirmRide(1L, request);

        assertThat(response.getStatus()).isEqualTo("ON_RIDE");
        assertThat(response.getActiveRideId()).isEqualTo(501L);
    }

    @Test
    @DisplayName("Confirm ride throws ConflictException when lease expired or reclaimed (0 rows affected)")
    void shouldRejectConfirmWhenLeaseExpired() {
        DriverProfile profile = DriverProfile.builder().id(1L).status(DriverStatus.RESERVED).build();
        when(driverProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));
        when(driverProfileRepository.confirmRideAssignment(eq(1L), eq(501L), eq(fixedNow)))
                .thenReturn(0);

        DriverActionRequest request = DriverActionRequest.builder().rideId(501L).build();

        assertThatThrownBy(() -> driverService.confirmRide(1L, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Reservation lease has expired or was claimed by another ride");
    }

    @Test
    @DisplayName("Release reservation Branch 1: clean release when 1 row updated")
    void shouldReleaseReservationCleanly() {
        DriverProfile profile = DriverProfile.builder().id(1L).status(DriverStatus.RESERVED).reservedByRideId(501L).build();
        when(driverProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));
        when(driverProfileRepository.releaseReservation(eq(1L), eq(501L), eq(fixedNow))).thenReturn(1);

        DriverReleaseRequest request = DriverReleaseRequest.builder().rideId(501L).build();
        DriverReleaseResponse response = driverService.releaseReservation(1L, request);

        assertThat(response.getStatus()).isEqualTo("RELEASED");
        assertThat(response.getDriverStatus()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("Release reservation Branch 2: duplicate retry returns ALREADY_RELEASED")
    void shouldHandleDuplicateReleaseRetry() {
        DriverProfile profile = DriverProfile.builder().id(1L).status(DriverStatus.AVAILABLE).lastReleasedByRideId(501L).build();
        when(driverProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));
        when(driverProfileRepository.findById(1L)).thenReturn(Optional.of(profile));
        when(driverProfileRepository.releaseReservation(eq(1L), eq(501L), eq(fixedNow))).thenReturn(0);

        DriverReleaseRequest request = DriverReleaseRequest.builder().rideId(501L).build();
        DriverReleaseResponse response = driverService.releaseReservation(1L, request);

        assertThat(response.getStatus()).isEqualTo("ALREADY_RELEASED");
    }

    @Test
    @DisplayName("Release reservation Branch 3: mismatched ride reservation throws ConflictException")
    void shouldRejectReleaseOnMismatchedRide() {
        DriverProfile profile = DriverProfile.builder().id(1L).status(DriverStatus.RESERVED).reservedByRideId(502L).build(); // Reserved by 502, not 501
        when(driverProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));
        when(driverProfileRepository.findById(1L)).thenReturn(Optional.of(profile));
        when(driverProfileRepository.releaseReservation(eq(1L), eq(501L), eq(fixedNow))).thenReturn(0);

        DriverReleaseRequest request = DriverReleaseRequest.builder().rideId(501L).build();

        assertThatThrownBy(() -> driverService.releaseReservation(1L, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Driver is reserved by another ride: 502");
    }

    @Test
    @DisplayName("Release reservation Branch 4: driver on active trip throws ConflictException")
    void shouldRejectReleaseWhenOnActiveTrip() {
        DriverProfile profile = DriverProfile.builder().id(1L).status(DriverStatus.ON_RIDE).activeRideId(501L).build();
        when(driverProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));
        when(driverProfileRepository.findById(1L)).thenReturn(Optional.of(profile));
        when(driverProfileRepository.releaseReservation(eq(1L), eq(501L), eq(fixedNow))).thenReturn(0);

        DriverReleaseRequest request = DriverReleaseRequest.builder().rideId(501L).build();

        assertThatThrownBy(() -> driverService.releaseReservation(1L, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Driver is currently on an active trip");
    }

    @Test
    @DisplayName("Release reservation Branch 5: driver offline throws ConflictException")
    void shouldRejectReleaseWhenOffline() {
        DriverProfile profile = DriverProfile.builder().id(1L).status(DriverStatus.OFFLINE).build();
        when(driverProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));
        when(driverProfileRepository.findById(1L)).thenReturn(Optional.of(profile));
        when(driverProfileRepository.releaseReservation(eq(1L), eq(501L), eq(fixedNow))).thenReturn(0);

        DriverReleaseRequest request = DriverReleaseRequest.builder().rideId(501L).build();

        assertThatThrownBy(() -> driverService.releaseReservation(1L, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Driver is currently offline");
    }

    @Test
    @DisplayName("Release reservation Branch 6: stale release after subsequent ride released driver throws ConflictException")
    void shouldRejectStaleReleaseAfterSubsequentRelease() {
        DriverProfile profile = DriverProfile.builder()
                .id(1L)
                .status(DriverStatus.AVAILABLE)
                .lastReleasedByRideId(502L) // Subsequent ride 502 released it
                .build();

        when(driverProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));
        when(driverProfileRepository.findById(1L)).thenReturn(Optional.of(profile));
        when(driverProfileRepository.releaseReservation(eq(1L), eq(501L), eq(fixedNow))).thenReturn(0);

        DriverReleaseRequest request = DriverReleaseRequest.builder().rideId(501L).build();

        assertThatThrownBy(() -> driverService.releaseReservation(1L, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Stale release request: driver lease has evolved and was released by a subsequent ride");
    }

    @Test
    @DisplayName("Complete ride succeeds when driver is active on that ride")
    void shouldCompleteRideSuccessfully() {
        DriverProfile profile = DriverProfile.builder().id(1L).status(DriverStatus.ON_RIDE).activeRideId(501L).build();
        when(driverProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));
        when(driverProfileRepository.completeRideAssignment(eq(1L), eq(501L), eq(fixedNow))).thenReturn(1);

        DriverActionRequest request = DriverActionRequest.builder().rideId(501L).build();
        DriverCompleteRideResponse response = driverService.completeRide(1L, request);

        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        assertThat(response.getDriverStatus()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("Duplicate completion where system cannot establish prior completion returns ConflictException")
    void shouldRejectDuplicateCompletionWhenUnprovable() {
        DriverProfile profile = DriverProfile.builder().id(1L).status(DriverStatus.AVAILABLE).activeRideId(null).build();
        when(driverProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));
        when(driverProfileRepository.findById(1L)).thenReturn(Optional.of(profile));
        when(driverProfileRepository.completeRideAssignment(eq(1L), eq(501L), eq(fixedNow))).thenReturn(0);

        DriverActionRequest request = DriverActionRequest.builder().rideId(501L).build();

        assertThatThrownBy(() -> driverService.completeRide(1L, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Cannot complete ride: driver is not currently active on this ride");
    }

    @Test
    @DisplayName("Complete ride for old ride R1 while driver is active on R2 throws ConflictException")
    void shouldRejectCompleteWhenActiveOnDifferentRide() {
        DriverProfile profile = DriverProfile.builder().id(1L).status(DriverStatus.ON_RIDE).activeRideId(502L).build(); // Active on 502
        when(driverProfileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));
        when(driverProfileRepository.findById(1L)).thenReturn(Optional.of(profile));
        when(driverProfileRepository.completeRideAssignment(eq(1L), eq(501L), eq(fixedNow))).thenReturn(0);

        DriverActionRequest request = DriverActionRequest.builder().rideId(501L).build();

        assertThatThrownBy(() -> driverService.completeRide(1L, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Driver is currently active on another ride: 502");
    }

    @Test
    @DisplayName("Regression: Reserve driver succeeds when authenticated userId differs from driver-profile id")
    void shouldReserveDriverSuccessfullyWhenUserIdDiffersFromProfileId() {
        // Driver User ID = 101, DriverProfile ID = 5
        DriverProfile profile = DriverProfile.builder().id(5L).status(DriverStatus.AVAILABLE).build();
        when(driverProfileRepository.findByUserId(101L)).thenReturn(Optional.of(profile));
        when(driverProfileRepository.acquireReservationLease(eq(5L), eq(501L), eq(fixedNow.plusSeconds(30)), eq(fixedNow)))
                .thenReturn(1);

        DriverReservationRequest request = DriverReservationRequest.builder().rideId(501L).build();
        DriverReservationResponse response = driverService.reserveDriver(101L, request);

        assertThat(response.getDriverId()).isEqualTo(101L);
        assertThat(response.getStatus()).isEqualTo("RESERVED");
        assertThat(response.getReservedByRideId()).isEqualTo(501L);
    }

    @Test
    @DisplayName("Regression ID Collision: Passenger userId matches another driver's profile id; reserve rejects cleanly")
    void shouldPreventIdCollisionWhenPassengerUserIdMatchesAnotherDriverProfileId() {
        // Passenger has User ID 1 (no driver profile). A driver exists elsewhere with DriverProfile ID 1 (User ID 2).
        when(driverProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());

        DriverReservationRequest request = DriverReservationRequest.builder().rideId(501L).build();

        assertThatThrownBy(() -> driverService.reserveDriver(1L, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Driver profile not found for user id: 1");

        // Verify the driver profile was NEVER reserved or modified by caller supplying passenger user ID
        verify(driverProfileRepository, never()).acquireReservationLease(any(), any(), any(), any());
    }
}
