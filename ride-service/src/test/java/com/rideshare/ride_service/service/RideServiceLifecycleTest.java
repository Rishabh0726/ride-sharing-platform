package com.rideshare.ride_service.service;

import com.rideshare.ride_service.client.PricingClient;
import com.rideshare.ride_service.client.UserClient;
import com.rideshare.ride_service.client.dto.*;
import com.rideshare.ride_service.dto.request.CancelRideRequest;
import com.rideshare.ride_service.dto.request.CreateRideRequest;
import com.rideshare.ride_service.dto.response.RideEventResponse;
import com.rideshare.ride_service.dto.response.RideResponse;
import com.rideshare.ride_service.entity.RideStatus;
import com.rideshare.ride_service.exception.BadRequestException;
import com.rideshare.ride_service.exception.ConflictException;
import com.rideshare.ride_service.repository.RideEventRepository;
import com.rideshare.ride_service.repository.RideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class RideServiceLifecycleTest {

    @Autowired
    private RideService rideService;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private RideEventRepository rideEventRepository;

    @MockitoBean
    private PricingClient pricingClient;

    @MockitoBean
    private UserClient userClient;

    @BeforeEach
    void setUp() {
        rideEventRepository.deleteAll();
        rideRepository.deleteAll();
    }

    @Test
    @DisplayName("Complete end-to-end happy path lifecycle: create -> accept -> arrive -> start -> complete")
    void testCompleteRideLifecycle() {
        String quoteToken = "quote-lifecycle-test-token-1";
        Long passengerId = 101L;
        Long driverId = 202L;

        FareQuoteResponse quoteResponse = FareQuoteResponse.builder()
                .quoteToken(quoteToken)
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .distanceKm(5.18)
                .vehicleType("SEDAN")
                .baseFare(BigDecimal.valueOf(50.00))
                .distanceFare(BigDecimal.valueOf(77.77))
                .bookingFee(BigDecimal.valueOf(15.00))
                .estimatedFare(BigDecimal.valueOf(142.77))
                .currency("INR")
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();

        when(pricingClient.verifyQuote(quoteToken)).thenReturn(ResponseEntity.ok(quoteResponse));

        // 1. Create Ride Request
        CreateRideRequest createRequest = CreateRideRequest.builder()
                .fareQuoteToken(quoteToken)
                .pickupLocationName("Koramangala 5th Block")
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLocationName("Indiranagar 100ft Rd")
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .build();

        RideResponse created = rideService.createRide(passengerId, createRequest);
        assertThat(created.getId()).isNotNull();
        assertThat(created.getStatus()).isEqualTo(RideStatus.REQUESTED.name());
        assertThat(created.getEstimatedFare()).isEqualByComparingTo("142.77");
        assertThat(created.getFinalFare()).isNull();
        assertThat(created.getDriverId()).isNull();

        // 2. Idempotent Retry: Same passenger receives the exact existing ride
        RideResponse idempotentRetry = rideService.createRide(passengerId, createRequest);
        assertThat(idempotentRetry.getId()).isEqualTo(created.getId());
        assertThat(idempotentRetry.getRideUuid()).isEqualTo(created.getRideUuid());

        // 3. Different Passenger Collision: Attempt to use same quote token rejected with 400
        Long maliciousPassengerId = 999L;
        assertThatThrownBy(() -> rideService.createRide(maliciousPassengerId, createRequest))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid fare quote token");

        // 4. Driver Accept Ride
        when(userClient.reserveDriver(eq(driverId), any(DriverReservationRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverReservationResponse.builder().driverId(driverId).status("RESERVED").build()));
        when(userClient.confirmRide(eq(driverId), any(DriverActionRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverConfirmRideResponse.builder().driverId(driverId).status("ON_RIDE").build()));

        RideResponse accepted = rideService.acceptRide(created.getId(), driverId);
        assertThat(accepted.getStatus()).isEqualTo(RideStatus.ACCEPTED.name());
        assertThat(accepted.getDriverId()).isEqualTo(driverId);
        assertThat(accepted.getAcceptedAt()).isNotNull();

        verify(userClient).reserveDriver(eq(driverId), any(DriverReservationRequest.class));
        verify(userClient).confirmRide(eq(driverId), any(DriverActionRequest.class));

        // 5. Driver Arrives at Pickup
        RideResponse arriving = rideService.arriveAtPickup(created.getId(), driverId);
        assertThat(arriving.getStatus()).isEqualTo(RideStatus.DRIVER_ARRIVING.name());
        assertThat(arriving.getArrivedAt()).isNotNull();

        // 6. Driver Starts Trip
        RideResponse inProgress = rideService.startRide(created.getId(), driverId);
        assertThat(inProgress.getStatus()).isEqualTo(RideStatus.IN_PROGRESS.name());
        assertThat(inProgress.getStartedAt()).isNotNull();

        // 7. Driver Completes Trip
        when(userClient.completeRide(eq(driverId), any(DriverActionRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverCompleteRideResponse.builder().driverId(driverId).status("COMPLETED").build()));

        RideResponse completed = rideService.completeRide(created.getId(), driverId);
        assertThat(completed.getStatus()).isEqualTo(RideStatus.COMPLETED.name());
        assertThat(completed.getCompletedAt()).isNotNull();
        assertThat(completed.getFinalFare()).isEqualByComparingTo("142.77");

        verify(userClient).completeRide(eq(driverId), any(DriverActionRequest.class));

        // 8. Verify Chronological Ride Events
        List<RideEventResponse> events = rideService.getRideEvents(created.getId(), passengerId, "ROLE_PASSENGER");
        assertThat(events).hasSize(5);
        assertThat(events.get(0).getToStatus()).isEqualTo(RideStatus.REQUESTED.name());
        assertThat(events.get(1).getToStatus()).isEqualTo(RideStatus.ACCEPTED.name());
        assertThat(events.get(2).getToStatus()).isEqualTo(RideStatus.DRIVER_ARRIVING.name());
        assertThat(events.get(3).getToStatus()).isEqualTo(RideStatus.IN_PROGRESS.name());
        assertThat(events.get(4).getToStatus()).isEqualTo(RideStatus.COMPLETED.name());
    }

    private CreateRideRequest buildCreateRequest(String quoteToken) {
        return CreateRideRequest.builder()
                .fareQuoteToken(quoteToken)
                .pickupLocationName("Koramangala 5th Block")
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLocationName("Indiranagar 100ft Rd")
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .build();
    }

    private FareQuoteResponse buildMockQuote(String quoteToken) {
        return FareQuoteResponse.builder()
                .quoteToken(quoteToken)
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .distanceKm(5.18)
                .vehicleType("SEDAN")
                .baseFare(BigDecimal.valueOf(50.00))
                .distanceFare(BigDecimal.valueOf(77.77))
                .bookingFee(BigDecimal.valueOf(15.00))
                .estimatedFare(BigDecimal.valueOf(142.77))
                .currency("INR")
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
    }

    @Test
    @DisplayName("Second ride with different quote is rejected when passenger has active ride, but same quote is idempotent")
    void testRejectSecondRideWhenActiveRideExistsAndPreserveIdempotency() {
        Long passengerId = 501L;
        String quote1 = "quote-token-active-1";
        String quote2 = "quote-token-active-2";

        when(pricingClient.verifyQuote(quote1)).thenReturn(ResponseEntity.ok(buildMockQuote(quote1)));
        when(pricingClient.verifyQuote(quote2)).thenReturn(ResponseEntity.ok(buildMockQuote(quote2)));

        // Step 1: Create initial ride
        RideResponse ride1 = rideService.createRide(passengerId, buildCreateRequest(quote1));
        assertThat(ride1.getId()).isNotNull();
        assertThat(ride1.getStatus()).isEqualTo(RideStatus.REQUESTED.name());

        // Step 2: Idempotent re-submission with the same quote token succeeds
        RideResponse idempotentRetry = rideService.createRide(passengerId, buildCreateRequest(quote1));
        assertThat(idempotentRetry.getId()).isEqualTo(ride1.getId());
        assertThat(idempotentRetry.getFareQuoteToken()).isEqualTo(quote1);

        // Step 3: Attempting to create a second ride with a different quote token is rejected with 409 Conflict
        assertThatThrownBy(() -> rideService.createRide(passengerId, buildCreateRequest(quote2)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Passenger already has an active ride in progress");

        // Verify that pricingClient.verifyQuote was never called for quote2
        verify(pricingClient, never()).verifyQuote(quote2);
    }

    @Test
    @DisplayName("New ride can be created after previous ride reaches COMPLETED status")
    void testNewRideAllowedAfterPreviousRideCompleted() {
        Long passengerId = 502L;
        Long driverId = 602L;
        String quoteA = "quote-token-completed-a";
        String quoteB = "quote-token-completed-b";

        when(pricingClient.verifyQuote(quoteA)).thenReturn(ResponseEntity.ok(buildMockQuote(quoteA)));
        when(pricingClient.verifyQuote(quoteB)).thenReturn(ResponseEntity.ok(buildMockQuote(quoteB)));

        when(userClient.reserveDriver(eq(driverId), any(DriverReservationRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverReservationResponse.builder().driverId(driverId).status("RESERVED").build()));
        when(userClient.confirmRide(eq(driverId), any(DriverActionRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverConfirmRideResponse.builder().driverId(driverId).status("ON_RIDE").build()));
        when(userClient.completeRide(eq(driverId), any(DriverActionRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverCompleteRideResponse.builder().driverId(driverId).status("COMPLETED").build()));

        // Create, accept, arrive, start, complete ride A
        RideResponse rideA = rideService.createRide(passengerId, buildCreateRequest(quoteA));
        rideService.acceptRide(rideA.getId(), driverId);
        rideService.arriveAtPickup(rideA.getId(), driverId);
        rideService.startRide(rideA.getId(), driverId);
        RideResponse completedA = rideService.completeRide(rideA.getId(), driverId);
        assertThat(completedA.getStatus()).isEqualTo(RideStatus.COMPLETED.name());

        // Create ride B now that ride A is COMPLETED
        RideResponse rideB = rideService.createRide(passengerId, buildCreateRequest(quoteB));
        assertThat(rideB.getId()).isNotNull();
        assertThat(rideB.getId()).isNotEqualTo(rideA.getId());
        assertThat(rideB.getStatus()).isEqualTo(RideStatus.REQUESTED.name());
    }

    @Test
    @DisplayName("New ride can be created after previous ride is CANCELLED")
    void testNewRideAllowedAfterPreviousRideCancelled() {
        Long passengerId = 503L;
        String quoteA = "quote-token-cancelled-a";
        String quoteB = "quote-token-cancelled-b";

        when(pricingClient.verifyQuote(quoteA)).thenReturn(ResponseEntity.ok(buildMockQuote(quoteA)));
        when(pricingClient.verifyQuote(quoteB)).thenReturn(ResponseEntity.ok(buildMockQuote(quoteB)));

        // Create ride A
        RideResponse rideA = rideService.createRide(passengerId, buildCreateRequest(quoteA));
        assertThat(rideA.getStatus()).isEqualTo(RideStatus.REQUESTED.name());

        // Cancel ride A
        CancelRideRequest cancelReq = CancelRideRequest.builder().reason("Change of plans").build();
        RideResponse cancelledA = rideService.cancelRide(rideA.getId(), passengerId, "ROLE_PASSENGER", cancelReq);
        assertThat(cancelledA.getStatus()).isEqualTo(RideStatus.CANCELLED.name());

        // Create ride B now that ride A is CANCELLED
        RideResponse rideB = rideService.createRide(passengerId, buildCreateRequest(quoteB));
        assertThat(rideB.getId()).isNotNull();
        assertThat(rideB.getId()).isNotEqualTo(rideA.getId());
        assertThat(rideB.getStatus()).isEqualTo(RideStatus.REQUESTED.name());
    }

    @Test
    @DisplayName("Active ride guard rejects new ride across each of the 4 active statuses")
    void testActiveRideGuardEnforcedAcrossAllActiveStatuses() {
        // 1. Status: REQUESTED
        Long p1 = 611L;
        String q1a = "quote-status-req-a";
        String q1b = "quote-status-req-b";
        when(pricingClient.verifyQuote(q1a)).thenReturn(ResponseEntity.ok(buildMockQuote(q1a)));
        rideService.createRide(p1, buildCreateRequest(q1a));
        assertThatThrownBy(() -> rideService.createRide(p1, buildCreateRequest(q1b)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Passenger already has an active ride in progress");

        // 2. Status: ACCEPTED
        Long p2 = 612L;
        Long d2 = 712L;
        String q2a = "quote-status-acc-a";
        String q2b = "quote-status-acc-b";
        when(pricingClient.verifyQuote(q2a)).thenReturn(ResponseEntity.ok(buildMockQuote(q2a)));
        when(userClient.reserveDriver(eq(d2), any(DriverReservationRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverReservationResponse.builder().driverId(d2).status("RESERVED").build()));
        when(userClient.confirmRide(eq(d2), any(DriverActionRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverConfirmRideResponse.builder().driverId(d2).status("ON_RIDE").build()));

        RideResponse ride2 = rideService.createRide(p2, buildCreateRequest(q2a));
        rideService.acceptRide(ride2.getId(), d2);
        assertThatThrownBy(() -> rideService.createRide(p2, buildCreateRequest(q2b)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Passenger already has an active ride in progress");

        // 3. Status: DRIVER_ARRIVING
        Long p3 = 613L;
        Long d3 = 713L;
        String q3a = "quote-status-arr-a";
        String q3b = "quote-status-arr-b";
        when(pricingClient.verifyQuote(q3a)).thenReturn(ResponseEntity.ok(buildMockQuote(q3a)));
        when(userClient.reserveDriver(eq(d3), any(DriverReservationRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverReservationResponse.builder().driverId(d3).status("RESERVED").build()));
        when(userClient.confirmRide(eq(d3), any(DriverActionRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverConfirmRideResponse.builder().driverId(d3).status("ON_RIDE").build()));

        RideResponse ride3 = rideService.createRide(p3, buildCreateRequest(q3a));
        rideService.acceptRide(ride3.getId(), d3);
        rideService.arriveAtPickup(ride3.getId(), d3);
        assertThatThrownBy(() -> rideService.createRide(p3, buildCreateRequest(q3b)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Passenger already has an active ride in progress");

        // 4. Status: IN_PROGRESS
        Long p4 = 614L;
        Long d4 = 714L;
        String q4a = "quote-status-prog-a";
        String q4b = "quote-status-prog-b";
        when(pricingClient.verifyQuote(q4a)).thenReturn(ResponseEntity.ok(buildMockQuote(q4a)));
        when(userClient.reserveDriver(eq(d4), any(DriverReservationRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverReservationResponse.builder().driverId(d4).status("RESERVED").build()));
        when(userClient.confirmRide(eq(d4), any(DriverActionRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverConfirmRideResponse.builder().driverId(d4).status("ON_RIDE").build()));

        RideResponse ride4 = rideService.createRide(p4, buildCreateRequest(q4a));
        rideService.acceptRide(ride4.getId(), d4);
        rideService.arriveAtPickup(ride4.getId(), d4);
        rideService.startRide(ride4.getId(), d4);
        assertThatThrownBy(() -> rideService.createRide(p4, buildCreateRequest(q4b)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Passenger already has an active ride in progress");
    }

    @Test
    @DisplayName("Available rides feed shows requested rides, newest first, and removes them once accepted")
    void testAvailableRidesFeedLifecycle() {
        // 1. Empty feed when no requested rides exist
        assertThat(rideService.getAvailableRides()).isEmpty();

        // 2. Passenger 801 creates Ride 1
        Long p1 = 801L;
        String q1 = "quote-avail-feed-1";
        when(pricingClient.verifyQuote(q1)).thenReturn(ResponseEntity.ok(buildMockQuote(q1)));
        RideResponse ride1 = rideService.createRide(p1, buildCreateRequest(q1));

        List<RideResponse> feedAfter1 = rideService.getAvailableRides();
        assertThat(feedAfter1).hasSize(1);
        assertThat(feedAfter1.get(0).getId()).isEqualTo(ride1.getId());
        assertThat(feedAfter1.get(0).getStatus()).isEqualTo(RideStatus.REQUESTED.name());

        // 3. Passenger 802 creates Ride 2
        Long p2 = 802L;
        String q2 = "quote-avail-feed-2";
        when(pricingClient.verifyQuote(q2)).thenReturn(ResponseEntity.ok(buildMockQuote(q2)));
        RideResponse ride2 = rideService.createRide(p2, buildCreateRequest(q2));

        List<RideResponse> feedAfter2 = rideService.getAvailableRides();
        assertThat(feedAfter2).hasSize(2);
        // Ordering: newest first
        assertThat(feedAfter2.get(0).getId()).isEqualTo(ride2.getId());
        assertThat(feedAfter2.get(1).getId()).isEqualTo(ride1.getId());

        // 4. Driver 901 accepts Ride 1 -> Ride 1 disappears from feed
        Long d1 = 901L;
        when(userClient.reserveDriver(eq(d1), any(DriverReservationRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverReservationResponse.builder().driverId(d1).status("RESERVED").build()));
        when(userClient.confirmRide(eq(d1), any(DriverActionRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverConfirmRideResponse.builder().driverId(d1).status("ON_RIDE").build()));

        rideService.acceptRide(ride1.getId(), d1);

        List<RideResponse> feedAfterAccept1 = rideService.getAvailableRides();
        assertThat(feedAfterAccept1).hasSize(1);
        assertThat(feedAfterAccept1.get(0).getId()).isEqualTo(ride2.getId());

        // 5. Driver 902 accepts Ride 2 -> Feed becomes empty
        Long d2 = 902L;
        when(userClient.reserveDriver(eq(d2), any(DriverReservationRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverReservationResponse.builder().driverId(d2).status("RESERVED").build()));
        when(userClient.confirmRide(eq(d2), any(DriverActionRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverConfirmRideResponse.builder().driverId(d2).status("ON_RIDE").build()));

        rideService.acceptRide(ride2.getId(), d2);

        assertThat(rideService.getAvailableRides()).isEmpty();
    }

    @Test
    @DisplayName("Driver completed history lifecycle: empty initially, populates on completion, and isolates between drivers")
    void testDriverCompletedHistoryLifecycle() {
        Long d1 = 911L;
        Long d2 = 912L;

        // 1. Initially empty for both drivers
        assertThat(rideService.getDriverCompletedRides(d1)).isEmpty();
        assertThat(rideService.getDriverCompletedRides(d2)).isEmpty();

        // 2. Mock driver clients
        when(userClient.reserveDriver(eq(d1), any(DriverReservationRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverReservationResponse.builder().driverId(d1).status("RESERVED").build()));
        when(userClient.confirmRide(eq(d1), any(DriverActionRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverConfirmRideResponse.builder().driverId(d1).status("ON_RIDE").build()));
        when(userClient.completeRide(eq(d1), any(DriverActionRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverCompleteRideResponse.builder().driverId(d1).status("COMPLETED").build()));

        // 3. Complete Ride 1 for Driver 1
        Long p1 = 811L;
        String q1 = "quote-hist-811";
        when(pricingClient.verifyQuote(q1)).thenReturn(ResponseEntity.ok(buildMockQuote(q1)));
        RideResponse ride1 = rideService.createRide(p1, buildCreateRequest(q1));
        rideService.acceptRide(ride1.getId(), d1);
        rideService.arriveAtPickup(ride1.getId(), d1);
        rideService.startRide(ride1.getId(), d1);
        RideResponse comp1 = rideService.completeRide(ride1.getId(), d1);

        // Driver 1 has 1 completed ride
        List<RideResponse> d1History1 = rideService.getDriverCompletedRides(d1);
        assertThat(d1History1).hasSize(1);
        assertThat(d1History1.get(0).getId()).isEqualTo(comp1.getId());
        assertThat(d1History1.get(0).getStatus()).isEqualTo(RideStatus.COMPLETED.name());

        // Driver 2 history is still empty (no leakage)
        assertThat(rideService.getDriverCompletedRides(d2)).isEmpty();

        // 4. Complete Ride 2 for Driver 1
        Long p2 = 812L;
        String q2 = "quote-hist-812";
        when(pricingClient.verifyQuote(q2)).thenReturn(ResponseEntity.ok(buildMockQuote(q2)));
        RideResponse ride2 = rideService.createRide(p2, buildCreateRequest(q2));
        rideService.acceptRide(ride2.getId(), d1);
        rideService.arriveAtPickup(ride2.getId(), d1);
        rideService.startRide(ride2.getId(), d1);
        RideResponse comp2 = rideService.completeRide(ride2.getId(), d1);

        // Driver 1 now has 2 completed rides, newest first
        List<RideResponse> d1History2 = rideService.getDriverCompletedRides(d1);
        assertThat(d1History2).hasSize(2);
        assertThat(d1History2.get(0).getId()).isEqualTo(comp2.getId());
        assertThat(d1History2.get(1).getId()).isEqualTo(comp1.getId());
    }
}
