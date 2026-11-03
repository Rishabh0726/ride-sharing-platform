package com.rideshare.ride_service.repository;

import com.rideshare.ride_service.entity.Ride;
import com.rideshare.ride_service.entity.RideStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class RideRepositoryTest {

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Ride createSampleRide(String quoteToken, RideStatus status) {
        return Ride.builder()
                .rideUuid(UUID.randomUUID().toString())
                .passengerId(101L)
                .status(status)
                .pickupLocationName("Koramangala")
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLocationName("Indiranagar")
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .distanceKm(BigDecimal.valueOf(5.18))
                .fareQuoteToken(quoteToken)
                .estimatedFare(BigDecimal.valueOf(142.77))
                .baseFare(BigDecimal.valueOf(50.00))
                .distanceFare(BigDecimal.valueOf(77.77))
                .bookingFee(BigDecimal.valueOf(15.00))
                .currency("INR")
                .vehicleType("SEDAN")
                .build();
    }

    @Test
    @DisplayName("Unique constraint on fare_quote_token rejects duplicate ride creation")
    void testUniqueFareQuoteTokenConstraint() {
        Ride ride1 = createSampleRide("quote-unique-token-123", RideStatus.REQUESTED);
        rideRepository.saveAndFlush(ride1);

        Ride ride2 = createSampleRide("quote-unique-token-123", RideStatus.REQUESTED);
        assertThatThrownBy(() -> rideRepository.saveAndFlush(ride2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Unique constraint on ride_uuid enforces uniqueness across rides")
    void testUniqueRideUuidConstraint() {
        String sharedUuid = UUID.randomUUID().toString();
        Ride ride1 = createSampleRide("quote-token-a", RideStatus.REQUESTED);
        ride1.setRideUuid(sharedUuid);
        rideRepository.saveAndFlush(ride1);

        Ride ride2 = createSampleRide("quote-token-b", RideStatus.REQUESTED);
        ride2.setRideUuid(sharedUuid);
        assertThatThrownBy(() -> rideRepository.saveAndFlush(ride2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("claimAndAcceptRide atomically updates status to ACCEPTED and increments version")
    void testClaimAndAcceptRideSuccess() {
        Ride ride = createSampleRide("quote-claim-test", RideStatus.REQUESTED);
        Ride saved = rideRepository.saveAndFlush(ride);
        Long expectedVersion = saved.getVersion();

        LocalDateTime now = LocalDateTime.now();
        int updated = rideRepository.claimAndAcceptRide(saved.getId(), 202L, now, expectedVersion);
        assertThat(updated).isEqualTo(1);

        entityManager.clear();
        Ride updatedRide = rideRepository.findById(saved.getId()).orElseThrow();
        assertThat(updatedRide.getStatus()).isEqualTo(RideStatus.ACCEPTED);
        assertThat(updatedRide.getDriverId()).isEqualTo(202L);
        assertThat(updatedRide.getVersion()).isEqualTo(expectedVersion + 1);
    }

    @Test
    @DisplayName("claimAndAcceptRide fails (0 rows) if ride is not in REQUESTED status or version mismatches")
    void testClaimAndAcceptRideConflict() {
        Ride ride = createSampleRide("quote-claim-conflict", RideStatus.ACCEPTED);
        ride.setDriverId(999L);
        Ride saved = rideRepository.saveAndFlush(ride);

        int updated = rideRepository.claimAndAcceptRide(saved.getId(), 202L, LocalDateTime.now(), saved.getVersion());
        assertThat(updated).isEqualTo(0);
    }

    @Test
    @DisplayName("rollbackAcceptance restores REQUESTED status and clears driverId")
    void testRollbackAcceptance() {
        Ride ride = createSampleRide("quote-rollback-test", RideStatus.ACCEPTED);
        ride.setDriverId(202L);
        Ride saved = rideRepository.saveAndFlush(ride);

        LocalDateTime now = LocalDateTime.now();
        int updated = rideRepository.rollbackAcceptance(saved.getId(), 202L, now);
        assertThat(updated).isEqualTo(1);

        entityManager.clear();
        Ride rolledBack = rideRepository.findById(saved.getId()).orElseThrow();
        assertThat(rolledBack.getStatus()).isEqualTo(RideStatus.REQUESTED);
        assertThat(rolledBack.getDriverId()).isNull();
    }

    @Test
    @DisplayName("Lifecycle state transitions: arrive, start, complete with finalFare assignment")
    void testFullLifecycleStateTransitions() {
        Ride ride = createSampleRide("quote-lifecycle-test", RideStatus.ACCEPTED);
        ride.setDriverId(202L);
        Ride saved = rideRepository.saveAndFlush(ride);

        LocalDateTime now = LocalDateTime.now();

        // 1. Arrive
        int arriveUpdated = rideRepository.transitionToDriverArriving(saved.getId(), 202L, now, saved.getVersion());
        assertThat(arriveUpdated).isEqualTo(1);

        entityManager.clear();
        Ride arrivingRide = rideRepository.findById(saved.getId()).orElseThrow();
        assertThat(arrivingRide.getStatus()).isEqualTo(RideStatus.DRIVER_ARRIVING);

        // 2. Start
        int startUpdated = rideRepository.transitionToInProgress(saved.getId(), 202L, now, arrivingRide.getVersion());
        assertThat(startUpdated).isEqualTo(1);

        entityManager.clear();
        Ride inProgressRide = rideRepository.findById(saved.getId()).orElseThrow();
        assertThat(inProgressRide.getStatus()).isEqualTo(RideStatus.IN_PROGRESS);

        // 3. Complete with final fare assignment
        BigDecimal finalFare = BigDecimal.valueOf(142.77);
        int completeUpdated = rideRepository.transitionToCompleted(saved.getId(), 202L, finalFare, now, inProgressRide.getVersion());
        assertThat(completeUpdated).isEqualTo(1);

        entityManager.clear();
        Ride completedRide = rideRepository.findById(saved.getId()).orElseThrow();
        assertThat(completedRide.getStatus()).isEqualTo(RideStatus.COMPLETED);
        assertThat(completedRide.getFinalFare()).isEqualTo(finalFare);
    }

    @Test
    @DisplayName("transitionToCancelled succeeds for allowed statuses and fails for COMPLETED")
    void testTransitionToCancelled() {
        Ride ride = createSampleRide("quote-cancel-test", RideStatus.ACCEPTED);
        ride.setDriverId(202L);
        Ride saved = rideRepository.saveAndFlush(ride);

        int updated = rideRepository.transitionToCancelled(
                saved.getId(),
                List.of(RideStatus.REQUESTED, RideStatus.ACCEPTED, RideStatus.DRIVER_ARRIVING),
                "PASSENGER",
                "Change of plans",
                LocalDateTime.now(),
                saved.getVersion()
        );
        assertThat(updated).isEqualTo(1);

        entityManager.clear();
        Ride cancelledRide = rideRepository.findById(saved.getId()).orElseThrow();
        assertThat(cancelledRide.getStatus()).isEqualTo(RideStatus.CANCELLED);
        assertThat(cancelledRide.getCancelledBy()).isEqualTo("PASSENGER");
        assertThat(cancelledRide.getCancellationReason()).isEqualTo("Change of plans");

        // Attempting to cancel again fails
        int secondCancel = rideRepository.transitionToCancelled(
                saved.getId(),
                List.of(RideStatus.REQUESTED, RideStatus.ACCEPTED),
                "PASSENGER",
                "Retry",
                LocalDateTime.now(),
                cancelledRide.getVersion()
        );
        assertThat(secondCancel).isEqualTo(0);
    }

    @Test
    @DisplayName("existsByPassengerIdAndStatusIn accurately detects active statuses, ignores terminal statuses and other passengers")
    void testExistsByPassengerIdAndStatusIn() {
        List<RideStatus> activeStatuses = List.of(
                RideStatus.REQUESTED,
                RideStatus.ACCEPTED,
                RideStatus.DRIVER_ARRIVING,
                RideStatus.IN_PROGRESS
        );

        Long passengerId = 101L;
        Long otherPassengerId = 999L;

        // 1. No rides exist
        assertThat(rideRepository.existsByPassengerIdAndStatusIn(passengerId, activeStatuses)).isFalse();

        // 2. Terminal status: COMPLETED does not trigger active check
        Ride completedRide = createSampleRide("quote-completed", RideStatus.COMPLETED);
        rideRepository.saveAndFlush(completedRide);
        assertThat(rideRepository.existsByPassengerIdAndStatusIn(passengerId, activeStatuses)).isFalse();

        // 3. Terminal status: CANCELLED does not trigger active check
        Ride cancelledRide = createSampleRide("quote-cancelled", RideStatus.CANCELLED);
        rideRepository.saveAndFlush(cancelledRide);
        assertThat(rideRepository.existsByPassengerIdAndStatusIn(passengerId, activeStatuses)).isFalse();

        // 4. Test each of the 4 active statuses triggers exists == true
        for (RideStatus activeStatus : activeStatuses) {
            Ride activeRide = createSampleRide("quote-active-" + activeStatus.name(), activeStatus);
            Ride saved = rideRepository.saveAndFlush(activeRide);

            assertThat(rideRepository.existsByPassengerIdAndStatusIn(passengerId, activeStatuses)).isTrue();
            // Other passenger does not match
            assertThat(rideRepository.existsByPassengerIdAndStatusIn(otherPassengerId, activeStatuses)).isFalse();

            // Clean up to isolate next status check
            rideRepository.delete(saved);
            rideRepository.flush();
        }

        // 5. After removing active rides, returns false again
        assertThat(rideRepository.existsByPassengerIdAndStatusIn(passengerId, activeStatuses)).isFalse();
    }

    @Test
    @DisplayName("findByStatusOrderByCreatedAtDescIdDesc filters strictly by status and orders newest first")
    void testFindByStatusOrderByCreatedAtDescIdDesc() {
        // 1. Initially empty
        assertThat(rideRepository.findByStatusOrderByCreatedAtDescIdDesc(RideStatus.REQUESTED)).isEmpty();

        // 2. Persist requested rides
        Ride req1 = createSampleRide("quote-feed-1", RideStatus.REQUESTED);
        Ride savedReq1 = rideRepository.saveAndFlush(req1);

        Ride req2 = createSampleRide("quote-feed-2", RideStatus.REQUESTED);
        Ride savedReq2 = rideRepository.saveAndFlush(req2);

        Ride req3 = createSampleRide("quote-feed-3", RideStatus.REQUESTED);
        Ride savedReq3 = rideRepository.saveAndFlush(req3);

        // 3. Persist other statuses that must be excluded
        Ride accepted = createSampleRide("quote-feed-accepted", RideStatus.ACCEPTED);
        accepted.setDriverId(202L);
        rideRepository.saveAndFlush(accepted);

        Ride completed = createSampleRide("quote-feed-completed", RideStatus.COMPLETED);
        completed.setDriverId(202L);
        rideRepository.saveAndFlush(completed);

        Ride cancelled = createSampleRide("quote-feed-cancelled", RideStatus.CANCELLED);
        rideRepository.saveAndFlush(cancelled);

        // 4. Query available requested feed
        List<Ride> available = rideRepository.findByStatusOrderByCreatedAtDescIdDesc(RideStatus.REQUESTED);

        assertThat(available).hasSize(3);
        assertThat(available).extracting(Ride::getStatus).containsOnly(RideStatus.REQUESTED);
        assertThat(available).extracting(Ride::getFareQuoteToken)
                .containsExactly(savedReq3.getFareQuoteToken(), savedReq2.getFareQuoteToken(), savedReq1.getFareQuoteToken());
    }

    @Test
    @DisplayName("findByDriverIdAndStatusOrderByCreatedAtDescIdDesc returns only completed rides for the specific driver")
    void testFindByDriverIdAndStatusOrderByCreatedAtDescIdDesc() {
        Long driverA = 202L;
        Long driverB = 303L;

        // 1. Initially empty
        assertThat(rideRepository.findByDriverIdAndStatusOrderByCreatedAtDescIdDesc(driverA, RideStatus.COMPLETED)).isEmpty();

        // 2. Driver A completed rides
        Ride compA1 = createSampleRide("quote-driverA-comp-1", RideStatus.COMPLETED);
        compA1.setDriverId(driverA);
        Ride savedCompA1 = rideRepository.saveAndFlush(compA1);

        Ride compA2 = createSampleRide("quote-driverA-comp-2", RideStatus.COMPLETED);
        compA2.setDriverId(driverA);
        Ride savedCompA2 = rideRepository.saveAndFlush(compA2);

        // 3. Driver A non-completed rides (must be excluded)
        Ride progA = createSampleRide("quote-driverA-prog", RideStatus.IN_PROGRESS);
        progA.setDriverId(driverA);
        rideRepository.saveAndFlush(progA);

        Ride cancA = createSampleRide("quote-driverA-canc", RideStatus.CANCELLED);
        cancA.setDriverId(driverA);
        rideRepository.saveAndFlush(cancA);

        // 4. Driver B completed ride (must be excluded from Driver A)
        Ride compB = createSampleRide("quote-driverB-comp", RideStatus.COMPLETED);
        compB.setDriverId(driverB);
        rideRepository.saveAndFlush(compB);

        // 5. Query Driver A history
        List<Ride> driverAHistory = rideRepository.findByDriverIdAndStatusOrderByCreatedAtDescIdDesc(driverA, RideStatus.COMPLETED);
        assertThat(driverAHistory).hasSize(2);
        assertThat(driverAHistory).extracting(Ride::getDriverId).containsOnly(driverA);
        assertThat(driverAHistory).extracting(Ride::getStatus).containsOnly(RideStatus.COMPLETED);
        assertThat(driverAHistory).extracting(Ride::getFareQuoteToken)
                .containsExactly(savedCompA2.getFareQuoteToken(), savedCompA1.getFareQuoteToken());

        // 6. Query Driver B history
        List<Ride> driverBHistory = rideRepository.findByDriverIdAndStatusOrderByCreatedAtDescIdDesc(driverB, RideStatus.COMPLETED);
        assertThat(driverBHistory).hasSize(1);
        assertThat(driverBHistory.get(0).getFareQuoteToken()).isEqualTo("quote-driverB-comp");

        // 7. Unknown driver returns empty list
        assertThat(rideRepository.findByDriverIdAndStatusOrderByCreatedAtDescIdDesc(999L, RideStatus.COMPLETED)).isEmpty();
    }
}
