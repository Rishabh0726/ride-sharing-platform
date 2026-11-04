package com.rideshare.ride_service.repository;

import com.rideshare.ride_service.entity.Ride;
import com.rideshare.ride_service.entity.RideStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository-level tests for Issue 1: Database-enforced single active ride per passenger constraint.
 *
 * Note on H2 testing:
 * Production MySQL 8 migration (V2) uses "GENERATED ALWAYS AS (...) STORED AFTER passenger_id".
 * In H2 in-memory testing (where Flyway is disabled and Hibernate ddl-auto=create-drop is used),
 * the schema is adapted in @BeforeEach to emulate the generated column and unique index uq_rides_active_passenger.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class RideActiveConstraintRepositoryTest {

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private TestEntityManager entityManager;

    @BeforeEach
    void setupActiveRideConstraint() {
        entityManager.clear();
        try {
            entityManager.getEntityManager().createNativeQuery("DELETE FROM rides").executeUpdate();
            entityManager.getEntityManager().createNativeQuery(
                "ALTER TABLE rides DROP COLUMN IF EXISTS active_passenger_id"
            ).executeUpdate();
            entityManager.getEntityManager().createNativeQuery(
                "ALTER TABLE rides ADD COLUMN active_passenger_id BIGINT GENERATED ALWAYS AS (" +
                "  CASE WHEN status IN ('REQUESTED', 'ACCEPTED', 'DRIVER_ARRIVING', 'IN_PROGRESS') THEN passenger_id " +
                "  ELSE NULL END" +
                ")"
            ).executeUpdate();
            entityManager.getEntityManager().createNativeQuery(
                "ALTER TABLE rides ADD CONSTRAINT uq_rides_active_passenger UNIQUE (active_passenger_id)"
            ).executeUpdate();
        } catch (Exception ex) {
            // Already configured or altered
        }
    }

    @AfterEach
    void tearDownActiveRideConstraint() {
        entityManager.clear();
        try {
            entityManager.getEntityManager().createNativeQuery("DELETE FROM rides").executeUpdate();
            entityManager.getEntityManager().createNativeQuery(
                "ALTER TABLE rides DROP CONSTRAINT IF EXISTS uq_rides_active_passenger"
            ).executeUpdate();
            entityManager.getEntityManager().createNativeQuery(
                "ALTER TABLE rides DROP COLUMN IF EXISTS active_passenger_id"
            ).executeUpdate();
            entityManager.getEntityManager().createNativeQuery(
                "ALTER TABLE rides ADD COLUMN active_passenger_id BIGINT"
            ).executeUpdate();
        } catch (Exception ignored) {
        }
    }

    private Ride createSampleRide(Long passengerId, String quoteToken, RideStatus status) {
        return Ride.builder()
                .rideUuid(UUID.randomUUID().toString())
                .passengerId(passengerId)
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
    @DisplayName("A.1: Same passenger cannot have two active rides simultaneously")
    void testSamePassengerCannotHaveTwoActiveRides() {
        Long passengerId = 101L;

        // Persist initial active ride (REQUESTED)
        Ride ride1 = createSampleRide(passengerId, "quote-active-1", RideStatus.REQUESTED);
        rideRepository.saveAndFlush(ride1);

        // Attempt to persist second active ride (ACCEPTED) for the same passenger
        Ride ride2 = createSampleRide(passengerId, "quote-active-2", RideStatus.ACCEPTED);
        assertThatThrownBy(() -> rideRepository.saveAndFlush(ride2))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("UQ_RIDES_ACTIVE_PASSENGER");
        entityManager.clear();
    }

    @Test
    @DisplayName("A.2: Same passenger can have multiple COMPLETED rides")
    void testSamePassengerCanHaveMultipleCompletedRides() {
        Long passengerId = 101L;

        Ride completed1 = createSampleRide(passengerId, "quote-completed-1", RideStatus.COMPLETED);
        Ride completed2 = createSampleRide(passengerId, "quote-completed-2", RideStatus.COMPLETED);
        Ride completed3 = createSampleRide(passengerId, "quote-completed-3", RideStatus.COMPLETED);

        Ride saved1 = rideRepository.saveAndFlush(completed1);
        Ride saved2 = rideRepository.saveAndFlush(completed2);
        Ride saved3 = rideRepository.saveAndFlush(completed3);

        assertThat(saved1.getId()).isNotNull();
        assertThat(saved2.getId()).isNotNull();
        assertThat(saved3.getId()).isNotNull();
        assertThat(rideRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("A.3: Same passenger can have multiple CANCELLED rides")
    void testSamePassengerCanHaveMultipleCancelledRides() {
        Long passengerId = 101L;

        Ride cancelled1 = createSampleRide(passengerId, "quote-cancelled-1", RideStatus.CANCELLED);
        Ride cancelled2 = createSampleRide(passengerId, "quote-cancelled-2", RideStatus.CANCELLED);
        Ride cancelled3 = createSampleRide(passengerId, "quote-cancelled-3", RideStatus.CANCELLED);

        Ride saved1 = rideRepository.saveAndFlush(cancelled1);
        Ride saved2 = rideRepository.saveAndFlush(cancelled2);
        Ride saved3 = rideRepository.saveAndFlush(cancelled3);

        assertThat(saved1.getId()).isNotNull();
        assertThat(saved2.getId()).isNotNull();
        assertThat(saved3.getId()).isNotNull();
        assertThat(rideRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("A.4: Different passengers can each have one active ride")
    void testDifferentPassengersCanEachHaveOneActiveRide() {
        Long passenger1 = 101L;
        Long passenger2 = 102L;

        Ride ride1 = createSampleRide(passenger1, "quote-active-p1", RideStatus.REQUESTED);
        Ride ride2 = createSampleRide(passenger2, "quote-active-p2", RideStatus.IN_PROGRESS);

        Ride saved1 = rideRepository.saveAndFlush(ride1);
        Ride saved2 = rideRepository.saveAndFlush(ride2);

        assertThat(saved1.getId()).isNotNull();
        assertThat(saved2.getId()).isNotNull();
        assertThat(rideRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("A.5: Passenger can create new active ride after previous ride becomes COMPLETED or CANCELLED")
    void testPassengerCanCreateActiveRideAfterPreviousRideTerminal() {
        Long passengerId = 101L;

        // 1. Initial ride is active (REQUESTED)
        Ride ride1 = createSampleRide(passengerId, "quote-term-test-1", RideStatus.REQUESTED);
        Ride saved1 = rideRepository.saveAndFlush(ride1);

        // 2. Transition ride1 to COMPLETED
        saved1.setStatus(RideStatus.COMPLETED);
        rideRepository.saveAndFlush(saved1);
        entityManager.clear();

        // 3. Passenger can now create a new active ride
        Ride ride2 = createSampleRide(passengerId, "quote-term-test-2", RideStatus.REQUESTED);
        Ride saved2 = rideRepository.saveAndFlush(ride2);
        assertThat(saved2.getId()).isNotNull();
        assertThat(saved2.getId()).isNotEqualTo(saved1.getId());

        // 4. Transition ride2 to CANCELLED
        saved2.setStatus(RideStatus.CANCELLED);
        rideRepository.saveAndFlush(saved2);
        entityManager.clear();

        // 5. Passenger can again create another active ride
        Ride ride3 = createSampleRide(passengerId, "quote-term-test-3", RideStatus.REQUESTED);
        Ride saved3 = rideRepository.saveAndFlush(ride3);
        assertThat(saved3.getId()).isNotNull();
        assertThat(saved3.getId()).isNotEqualTo(saved2.getId());
    }
}
