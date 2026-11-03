package com.rideshare.ride_service.service;

import com.rideshare.ride_service.client.PricingClient;
import com.rideshare.ride_service.client.UserClient;
import com.rideshare.ride_service.client.dto.*;
import com.rideshare.ride_service.dto.request.CancelRideRequest;
import com.rideshare.ride_service.dto.request.CreateRideRequest;
import com.rideshare.ride_service.dto.response.RideResponse;
import com.rideshare.ride_service.entity.RideStatus;
import com.rideshare.ride_service.exception.BadRequestException;
import com.rideshare.ride_service.exception.ConflictException;
import com.rideshare.ride_service.exception.GatewayTimeoutException;
import com.rideshare.ride_service.repository.RideEventRepository;
import com.rideshare.ride_service.repository.RideRepository;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.RetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class RideServiceRemoteCompensationTest {

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

    private Request dummyRequest() {
        return Request.create(Request.HttpMethod.GET, "/url", Collections.emptyMap(), null, StandardCharsets.UTF_8, new RequestTemplate());
    }

    @Test
    @DisplayName("Pricing service 404 Not Found maps to 400 Bad Request (quote not found)")
    void testQuoteNotFoundMapping() {
        String token = "quote-not-found-token";
        when(pricingClient.verifyQuote(token))
                .thenThrow(new FeignException.NotFound("Not found", dummyRequest(), null, null));

        CreateRideRequest request = CreateRideRequest.builder()
                .fareQuoteToken(token)
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .build();

        assertThatThrownBy(() -> rideService.createRide(101L, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid fare quote token: quote not found");
    }

    @Test
    @DisplayName("Pricing service 410 Gone maps to 400 Bad Request (quote expired)")
    void testQuoteExpiredMapping() {
        String token = "quote-expired-token";
        when(pricingClient.verifyQuote(token))
                .thenThrow(new FeignException.Gone("Quote expired", dummyRequest(), null, null));

        CreateRideRequest request = CreateRideRequest.builder()
                .fareQuoteToken(token)
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .build();

        assertThatThrownBy(() -> rideService.createRide(101L, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Fare quote has expired");
    }

    @Test
    @DisplayName("Pricing service timeout maps to 504 Gateway Timeout")
    void testPricingTimeoutMapping() {
        String token = "quote-timeout-token";
        when(pricingClient.verifyQuote(token))
                .thenThrow(new RetryableException(504, "Timeout", Request.HttpMethod.GET, new Date(), dummyRequest()));

        CreateRideRequest request = CreateRideRequest.builder()
                .fareQuoteToken(token)
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .build();

        assertThatThrownBy(() -> rideService.createRide(101L, request))
                .isInstanceOf(GatewayTimeoutException.class)
                .hasMessageContaining("Pricing service request timed out");
    }

    @Test
    @DisplayName("Coordinate mismatch between request and quote snapshot is rejected with 400")
    void testCoordinateMismatch() {
        String token = "quote-coord-mismatch";
        FareQuoteResponse quote = FareQuoteResponse.builder()
                .quoteToken(token)
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .distanceKm(5.18)
                .baseFare(BigDecimal.valueOf(50.00))
                .distanceFare(BigDecimal.valueOf(77.77))
                .bookingFee(BigDecimal.valueOf(15.00))
                .estimatedFare(BigDecimal.valueOf(142.77))
                .build();

        when(pricingClient.verifyQuote(token)).thenReturn(ResponseEntity.ok(quote));

        // Different destination latitude (12.9999 instead of 12.9784)
        CreateRideRequest request = CreateRideRequest.builder()
                .fareQuoteToken(token)
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9999))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .build();

        assertThatThrownBy(() -> rideService.createRide(101L, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Pickup and destination coordinates do not match");
    }

    @Test
    @DisplayName("Driver accept rolls back to REQUESTED when confirmRide returns 409 Conflict")
    void testAcceptanceRollbackOnConfirmConflict() {
        String token = "quote-rollback-confirm-test";
        FareQuoteResponse quote = FareQuoteResponse.builder()
                .quoteToken(token)
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .distanceKm(5.18)
                .baseFare(BigDecimal.valueOf(50.00))
                .distanceFare(BigDecimal.valueOf(77.77))
                .bookingFee(BigDecimal.valueOf(15.00))
                .estimatedFare(BigDecimal.valueOf(142.77))
                .build();

        when(pricingClient.verifyQuote(token)).thenReturn(ResponseEntity.ok(quote));

        CreateRideRequest createRequest = CreateRideRequest.builder()
                .fareQuoteToken(token)
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .build();

        RideResponse created = rideService.createRide(101L, createRequest);

        Long driverId = 303L;
        // Step 2 reserve succeeds
        when(userClient.reserveDriver(eq(driverId), any(DriverReservationRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverReservationResponse.builder().driverId(driverId).status("RESERVED").build()));

        // Step 4 confirm fails with 409 Conflict (lease expired)
        when(userClient.confirmRide(eq(driverId), any(DriverActionRequest.class)))
                .thenThrow(new FeignException.Conflict("Lease expired", dummyRequest(), null, null));

        assertThatThrownBy(() -> rideService.acceptRide(created.getId(), driverId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Reservation lease expired before confirmation");

        // Verify ride was rolled back locally to REQUESTED
        RideResponse refreshed = rideService.getRideById(created.getId(), 101L, "ROLE_PASSENGER");
        assertThat(refreshed.getStatus()).isEqualTo(RideStatus.REQUESTED.name());
        assertThat(refreshed.getDriverId()).isNull();
    }

    @Test
    @DisplayName("Passenger cancelling ACCEPTED ride calls completeRide on user-service to free driver")
    void testCancelAcceptedRideFreesDriver() {
        String token = "quote-cancel-free-driver";
        FareQuoteResponse quote = FareQuoteResponse.builder()
                .quoteToken(token)
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .distanceKm(5.18)
                .baseFare(BigDecimal.valueOf(50.00))
                .distanceFare(BigDecimal.valueOf(77.77))
                .bookingFee(BigDecimal.valueOf(15.00))
                .estimatedFare(BigDecimal.valueOf(142.77))
                .build();

        when(pricingClient.verifyQuote(token)).thenReturn(ResponseEntity.ok(quote));

        CreateRideRequest createRequest = CreateRideRequest.builder()
                .fareQuoteToken(token)
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .build();

        RideResponse created = rideService.createRide(101L, createRequest);

        Long driverId = 404L;
        when(userClient.reserveDriver(eq(driverId), any(DriverReservationRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverReservationResponse.builder().driverId(driverId).status("RESERVED").build()));
        when(userClient.confirmRide(eq(driverId), any(DriverActionRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverConfirmRideResponse.builder().driverId(driverId).status("ON_RIDE").build()));

        rideService.acceptRide(created.getId(), driverId);

        // Now Passenger cancels the accepted ride
        when(userClient.completeRide(eq(driverId), any(DriverActionRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverCompleteRideResponse.builder().driverId(driverId).status("COMPLETED").build()));

        CancelRideRequest cancelRequest = CancelRideRequest.builder().reason("Emergency").build();
        RideResponse cancelled = rideService.cancelRide(created.getId(), 101L, "ROLE_PASSENGER", cancelRequest);

        assertThat(cancelled.getStatus()).isEqualTo(RideStatus.CANCELLED.name());
        assertThat(cancelled.getCancelledBy()).isEqualTo("PASSENGER");

        verify(userClient).completeRide(eq(driverId), any(DriverActionRequest.class));
    }

    @Test
    @DisplayName("Cancellation of an IN_PROGRESS ride is strictly rejected with 409 Conflict")
    void testCannotCancelInProgressRide() {
        String token = "quote-cannot-cancel-in-progress";
        FareQuoteResponse quote = FareQuoteResponse.builder()
                .quoteToken(token)
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .distanceKm(5.18)
                .baseFare(BigDecimal.valueOf(50.00))
                .distanceFare(BigDecimal.valueOf(77.77))
                .bookingFee(BigDecimal.valueOf(15.00))
                .estimatedFare(BigDecimal.valueOf(142.77))
                .build();

        when(pricingClient.verifyQuote(token)).thenReturn(ResponseEntity.ok(quote));

        CreateRideRequest createRequest = CreateRideRequest.builder()
                .fareQuoteToken(token)
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .build();

        RideResponse created = rideService.createRide(101L, createRequest);

        Long driverId = 505L;
        when(userClient.reserveDriver(eq(driverId), any(DriverReservationRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverReservationResponse.builder().driverId(driverId).status("RESERVED").build()));
        when(userClient.confirmRide(eq(driverId), any(DriverActionRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverConfirmRideResponse.builder().driverId(driverId).status("ON_RIDE").build()));

        rideService.acceptRide(created.getId(), driverId);
        rideService.arriveAtPickup(created.getId(), driverId);
        rideService.startRide(created.getId(), driverId);

        assertThatThrownBy(() -> rideService.cancelRide(created.getId(), 101L, "ROLE_PASSENGER", null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Ride is already in progress and cannot be cancelled");
    }

    @Test
    @DisplayName("confirmRide timeout followed by probe returning 409 'active trip' treats acceptance as successful")
    void testConfirmRideTimeoutProbeConfirmsOnRideSuccess() {
        String token = "quote-confirm-timeout-active-trip";
        FareQuoteResponse quote = FareQuoteResponse.builder()
                .quoteToken(token)
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .distanceKm(5.18)
                .baseFare(BigDecimal.valueOf(50.00))
                .distanceFare(BigDecimal.valueOf(77.77))
                .bookingFee(BigDecimal.valueOf(15.00))
                .estimatedFare(BigDecimal.valueOf(142.77))
                .build();

        when(pricingClient.verifyQuote(token)).thenReturn(ResponseEntity.ok(quote));

        CreateRideRequest createRequest = CreateRideRequest.builder()
                .fareQuoteToken(token)
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .build();

        RideResponse created = rideService.createRide(101L, createRequest);

        Long driverId = 606L;
        when(userClient.reserveDriver(eq(driverId), any(DriverReservationRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverReservationResponse.builder().driverId(driverId).status("RESERVED").build()));

        // confirmRide times out
        when(userClient.confirmRide(eq(driverId), any(DriverActionRequest.class)))
                .thenThrow(new RetryableException(504, "Read timed out", Request.HttpMethod.POST, new Date(), dummyRequest()));

        // Disambiguation probe returns 409 Conflict with "Driver is currently on an active trip"
        when(userClient.releaseReservation(eq(driverId), any(DriverReleaseRequest.class)))
                .thenThrow(new FeignException.Conflict("Driver is currently on an active trip", dummyRequest(), null, null));

        RideResponse accepted = rideService.acceptRide(created.getId(), driverId);
        assertThat(accepted.getStatus()).isEqualTo(RideStatus.ACCEPTED.name());
        assertThat(accepted.getDriverId()).isEqualTo(driverId);

        // Verify local ride remains ACCEPTED in DB
        RideResponse refreshed = rideService.getRideById(created.getId(), 101L, "ROLE_PASSENGER");
        assertThat(refreshed.getStatus()).isEqualTo(RideStatus.ACCEPTED.name());
        assertThat(refreshed.getDriverId()).isEqualTo(driverId);
    }

    @Test
    @DisplayName("confirmRide timeout followed by probe returning 200 OK rolls back to REQUESTED and returns 504")
    void testConfirmRideTimeoutProbeConfirmsReleasedRollsBackToRequested() {
        String token = "quote-confirm-timeout-released";
        FareQuoteResponse quote = FareQuoteResponse.builder()
                .quoteToken(token)
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .distanceKm(5.18)
                .baseFare(BigDecimal.valueOf(50.00))
                .distanceFare(BigDecimal.valueOf(77.77))
                .bookingFee(BigDecimal.valueOf(15.00))
                .estimatedFare(BigDecimal.valueOf(142.77))
                .build();

        when(pricingClient.verifyQuote(token)).thenReturn(ResponseEntity.ok(quote));

        CreateRideRequest createRequest = CreateRideRequest.builder()
                .fareQuoteToken(token)
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .build();

        RideResponse created = rideService.createRide(101L, createRequest);

        Long driverId = 707L;
        when(userClient.reserveDriver(eq(driverId), any(DriverReservationRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverReservationResponse.builder().driverId(driverId).status("RESERVED").build()));

        // confirmRide times out
        when(userClient.confirmRide(eq(driverId), any(DriverActionRequest.class)))
                .thenThrow(new RetryableException(504, "Read timed out", Request.HttpMethod.POST, new Date(), dummyRequest()));

        // Disambiguation probe succeeds (driver was RESERVED, now RELEASED)
        when(userClient.releaseReservation(eq(driverId), any(DriverReleaseRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverReleaseResponse.builder().driverId(driverId).status("RELEASED").build()));

        assertThatThrownBy(() -> rideService.acceptRide(created.getId(), driverId))
                .isInstanceOf(GatewayTimeoutException.class)
                .hasMessageContaining("Confirmation timed out; driver assignment rolled back");

        // Verify local ride was rolled back to REQUESTED
        RideResponse refreshed = rideService.getRideById(created.getId(), 101L, "ROLE_PASSENGER");
        assertThat(refreshed.getStatus()).isEqualTo(RideStatus.REQUESTED.name());
        assertThat(refreshed.getDriverId()).isNull();
    }

    @Test
    @DisplayName("confirmRide timeout followed by ambiguous probe 409 queues reconciliation without false rollback")
    void testConfirmRideTimeoutProbeAmbiguousConflictQueuesReconciliation() {
        String token = "quote-confirm-timeout-ambiguous";
        FareQuoteResponse quote = FareQuoteResponse.builder()
                .quoteToken(token)
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .distanceKm(5.18)
                .baseFare(BigDecimal.valueOf(50.00))
                .distanceFare(BigDecimal.valueOf(77.77))
                .bookingFee(BigDecimal.valueOf(15.00))
                .estimatedFare(BigDecimal.valueOf(142.77))
                .build();

        when(pricingClient.verifyQuote(token)).thenReturn(ResponseEntity.ok(quote));

        CreateRideRequest createRequest = CreateRideRequest.builder()
                .fareQuoteToken(token)
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .build();

        RideResponse created = rideService.createRide(101L, createRequest);

        Long driverId = 808L;
        when(userClient.reserveDriver(eq(driverId), any(DriverReservationRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverReservationResponse.builder().driverId(driverId).status("RESERVED").build()));

        // confirmRide times out
        when(userClient.confirmRide(eq(driverId), any(DriverActionRequest.class)))
                .thenThrow(new RetryableException(504, "Read timed out", Request.HttpMethod.POST, new Date(), dummyRequest()));

        // Disambiguation probe returns an ambiguous 409 (e.g. driver offline or unexpected conflict)
        when(userClient.releaseReservation(eq(driverId), any(DriverReleaseRequest.class)))
                .thenThrow(new FeignException.Conflict("Driver is currently offline", dummyRequest(), null, null));

        assertThatThrownBy(() -> rideService.acceptRide(created.getId(), driverId))
                .isInstanceOf(GatewayTimeoutException.class)
                .hasMessageContaining("Driver confirmation timed out and is pending reconciliation");

        // Verify local ride remains ACCEPTED pending reconciliation (no false rollback)
        RideResponse refreshed = rideService.getRideById(created.getId(), 101L, "ROLE_PASSENGER");
        assertThat(refreshed.getStatus()).isEqualTo(RideStatus.ACCEPTED.name());
        assertThat(refreshed.getDriverId()).isEqualTo(driverId);
    }
}
