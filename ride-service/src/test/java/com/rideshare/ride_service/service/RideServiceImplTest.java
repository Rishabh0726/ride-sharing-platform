package com.rideshare.ride_service.service;

import com.rideshare.ride_service.client.PricingClient;
import com.rideshare.ride_service.client.UserClient;
import com.rideshare.ride_service.client.dto.FareQuoteResponse;
import com.rideshare.ride_service.dto.request.CreateRideRequest;
import com.rideshare.ride_service.dto.response.RideResponse;
import com.rideshare.ride_service.entity.Ride;
import com.rideshare.ride_service.entity.RideStatus;
import com.rideshare.ride_service.exception.BadRequestException;
import com.rideshare.ride_service.exception.ConflictException;
import com.rideshare.ride_service.repository.RideEventRepository;
import com.rideshare.ride_service.repository.RideRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Service-level automated tests for Issue 1: Concurrency and DataIntegrityViolationException handling in RideServiceImpl.
 */
@ExtendWith(MockitoExtension.class)
class RideServiceImplTest {

    @Mock
    private RideRepository rideRepository;

    @Mock
    private RideEventRepository rideEventRepository;

    @Mock
    private RideStateTransitionService transitionService;

    @Mock
    private RideReconciliationService reconciliationService;

    @Mock
    private RideNotificationService notificationService;

    @Mock
    private PricingClient pricingClient;

    @Mock
    private UserClient userClient;

    @InjectMocks
    private RideServiceImpl rideService;

    private CreateRideRequest standardRequest;
    private FareQuoteResponse validQuote;

    @BeforeEach
    void setUp() {
        standardRequest = CreateRideRequest.builder()
                .fareQuoteToken("quote-token-xyz")
                .pickupLocationName("Koramangala")
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLocationName("Indiranagar")
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .build();

        validQuote = FareQuoteResponse.builder()
                .quoteToken("quote-token-xyz")
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .distanceKm(5.18)
                .estimatedFare(BigDecimal.valueOf(142.77))
                .baseFare(BigDecimal.valueOf(50.00))
                .distanceFare(BigDecimal.valueOf(77.77))
                .bookingFee(BigDecimal.valueOf(15.00))
                .currency("INR")
                .vehicleType("SEDAN")
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
    }

    @Test
    @DisplayName("B.1a: uq_rides_active_passenger violation in ConstraintViolationException maps to ConflictException (409)")
    void testActiveRideConstraintViolationViaConstraintNameMapsToConflictException() {
        Long passengerId = 101L;

        when(rideRepository.findByFareQuoteToken("quote-token-xyz")).thenReturn(Optional.empty());
        when(rideRepository.existsByPassengerIdAndStatusIn(eq(passengerId), any())).thenReturn(false);
        when(pricingClient.verifyQuote("quote-token-xyz")).thenReturn(ResponseEntity.ok(validQuote));

        ConstraintViolationException cve = new ConstraintViolationException(
                "Duplicate entry '101' for key 'rides.uq_rides_active_passenger'",
                null,
                "uq_rides_active_passenger"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Constraint violation", cve);
        when(transitionService.saveNewRide(any(), eq(passengerId))).thenThrow(dive);

        assertThatThrownBy(() -> rideService.createRide(passengerId, standardRequest))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Passenger already has an active ride in progress");

        verify(transitionService).saveNewRide(any(), eq(passengerId));
    }

    @Test
    @DisplayName("B.1b: uq_rides_active_passenger violation in message maps to ConflictException (409)")
    void testActiveRideConstraintViolationViaMessageMapsToConflictException() {
        Long passengerId = 101L;

        when(rideRepository.findByFareQuoteToken("quote-token-xyz")).thenReturn(Optional.empty());
        when(rideRepository.existsByPassengerIdAndStatusIn(eq(passengerId), any())).thenReturn(false);
        when(pricingClient.verifyQuote("quote-token-xyz")).thenReturn(ResponseEntity.ok(validQuote));

        DataIntegrityViolationException dive = new DataIntegrityViolationException(
                "Duplicate entry '101' for key 'rides.uq_rides_active_passenger'"
        );
        when(transitionService.saveNewRide(any(), eq(passengerId))).thenThrow(dive);

        assertThatThrownBy(() -> rideService.createRide(passengerId, standardRequest))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Passenger already has an active ride in progress");
    }

    @Test
    @DisplayName("B.2: Duplicate fare quote token for the same passenger preserves idempotent replay")
    void testDuplicateFareQuoteTokenSamePassengerPreservesIdempotentReplay() {
        Long passengerId = 101L;

        Ride existingRide = Ride.builder()
                .id(55L)
                .rideUuid(UUID.randomUUID().toString())
                .passengerId(passengerId)
                .status(RideStatus.REQUESTED)
                .fareQuoteToken("quote-token-xyz")
                .estimatedFare(BigDecimal.valueOf(142.77))
                .baseFare(BigDecimal.valueOf(50.00))
                .distanceFare(BigDecimal.valueOf(77.77))
                .bookingFee(BigDecimal.valueOf(15.00))
                .currency("INR")
                .vehicleType("SEDAN")
                .distanceKm(BigDecimal.valueOf(5.18))
                .build();

        // 1. Initial check returns empty (simulating race condition)
        when(rideRepository.findByFareQuoteToken("quote-token-xyz"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingRide)); // second check inside catch block

        when(rideRepository.existsByPassengerIdAndStatusIn(eq(passengerId), any())).thenReturn(false);
        when(pricingClient.verifyQuote("quote-token-xyz")).thenReturn(ResponseEntity.ok(validQuote));

        // DB rejects with duplicate key on fare_quote_token
        DataIntegrityViolationException dive = new DataIntegrityViolationException(
                "Duplicate entry 'quote-token-xyz' for key 'rides.uq_rides_fare_quote_token'"
        );
        when(transitionService.saveNewRide(any(), eq(passengerId))).thenThrow(dive);

        // Execute createRide
        RideResponse response = rideService.createRide(passengerId, standardRequest);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(55L);
        assertThat(response.getFareQuoteToken()).isEqualTo("quote-token-xyz");
        assertThat(response.getStatus()).isEqualTo(RideStatus.REQUESTED.name());
    }

    @Test
    @DisplayName("B.3: Consumed quote token belonging to another passenger preserves HTTP 400 behavior")
    void testConsumedQuoteTokenDifferentPassengerPreservesBadRequest() {
        Long authenticatedPassengerId = 101L;
        Long originalPassengerOwnerId = 999L;

        Ride otherPassengerRide = Ride.builder()
                .id(88L)
                .rideUuid(UUID.randomUUID().toString())
                .passengerId(originalPassengerOwnerId)
                .status(RideStatus.REQUESTED)
                .fareQuoteToken("quote-token-xyz")
                .build();

        // 1. Pre-check simulation: Token already exists for passenger 999
        when(rideRepository.findByFareQuoteToken("quote-token-xyz"))
                .thenReturn(Optional.of(otherPassengerRide));

        assertThatThrownBy(() -> rideService.createRide(authenticatedPassengerId, standardRequest))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Invalid fare quote token: quote has already been consumed");

        verify(transitionService, never()).saveNewRide(any(), any());

        // 2. Race condition catch block simulation: Token collision discovered during insert
        when(rideRepository.findByFareQuoteToken("quote-token-xyz"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(otherPassengerRide));
        when(rideRepository.existsByPassengerIdAndStatusIn(eq(authenticatedPassengerId), any())).thenReturn(false);
        when(pricingClient.verifyQuote("quote-token-xyz")).thenReturn(ResponseEntity.ok(validQuote));

        DataIntegrityViolationException dive = new DataIntegrityViolationException(
                "Duplicate entry 'quote-token-xyz' for key 'rides.uq_rides_fare_quote_token'"
        );
        when(transitionService.saveNewRide(any(), eq(authenticatedPassengerId))).thenThrow(dive);

        assertThatThrownBy(() -> rideService.createRide(authenticatedPassengerId, standardRequest))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Invalid fare quote token: quote has already been consumed");
    }

    @Test
    @DisplayName("B.4: Unrelated DataIntegrityViolationException is rethrown")
    void testUnrelatedDataIntegrityViolationExceptionIsRethrown() {
        Long passengerId = 101L;

        when(rideRepository.findByFareQuoteToken("quote-token-xyz")).thenReturn(Optional.empty());
        when(rideRepository.existsByPassengerIdAndStatusIn(eq(passengerId), any())).thenReturn(false);
        when(pricingClient.verifyQuote("quote-token-xyz")).thenReturn(ResponseEntity.ok(validQuote));

        DataIntegrityViolationException unrelatedEx = new DataIntegrityViolationException(
                "Cannot add or update a child row: a foreign key constraint fails (`ride_db`.`rides`, CONSTRAINT `fk_rides_driver` FOREIGN KEY (`driver_id`) REFERENCES `drivers` (`id`))"
        );
        when(transitionService.saveNewRide(any(), eq(passengerId))).thenThrow(unrelatedEx);

        assertThatThrownBy(() -> rideService.createRide(passengerId, standardRequest))
                .isSameAs(unrelatedEx);
    }

    @Test
    @DisplayName("B.5: Error mentioning active_passenger_id without constraint name must not be classified as active ride conflict")
    void testErrorMentioningActivePassengerIdWithoutConstraintNameIsRethrown() {
        Long passengerId = 101L;

        when(rideRepository.findByFareQuoteToken("quote-token-xyz")).thenReturn(Optional.empty());
        when(rideRepository.existsByPassengerIdAndStatusIn(eq(passengerId), any())).thenReturn(false);
        when(pricingClient.verifyQuote("quote-token-xyz")).thenReturn(ResponseEntity.ok(validQuote));

        // An error mentioning active_passenger_id alone (e.g. column syntax or not-null error)
        DataIntegrityViolationException nonConstraintError = new DataIntegrityViolationException(
                "Column 'active_passenger_id' cannot be null"
        );
        when(transitionService.saveNewRide(any(), eq(passengerId))).thenThrow(nonConstraintError);

        // Must NOT be converted to ConflictException; must be rethrown
        assertThatThrownBy(() -> rideService.createRide(passengerId, standardRequest))
                .isSameAs(nonConstraintError);
    }
}
