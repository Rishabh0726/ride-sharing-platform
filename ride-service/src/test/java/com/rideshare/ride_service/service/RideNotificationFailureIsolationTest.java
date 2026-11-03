package com.rideshare.ride_service.service;

import com.rideshare.ride_service.client.NotificationClient;
import com.rideshare.ride_service.client.PricingClient;
import com.rideshare.ride_service.client.UserClient;
import com.rideshare.ride_service.client.dto.*;
import com.rideshare.ride_service.dto.request.CancelRideRequest;
import com.rideshare.ride_service.dto.request.CreateRideRequest;
import com.rideshare.ride_service.dto.response.RideResponse;
import com.rideshare.ride_service.entity.RideStatus;
import com.rideshare.ride_service.repository.RideEventRepository;
import com.rideshare.ride_service.repository.RideRepository;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
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
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
public class RideNotificationFailureIsolationTest {

    @Autowired
    private RideService rideService;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private RideEventRepository rideEventRepository;

    @MockitoBean
    private NotificationClient notificationClient;

    @MockitoBean
    private PricingClient pricingClient;

    @MockitoBean
    private UserClient userClient;

    @BeforeEach
    void setUp() {
        rideEventRepository.deleteAll();
        rideRepository.deleteAll();
    }

    private CreateRideRequest buildCreateRequest(String quoteToken) {
        return CreateRideRequest.builder()
                .fareQuoteToken(quoteToken)
                .pickupLocationName("MG Road, Bengaluru")
                .pickupLatitude(new BigDecimal("12.97160000"))
                .pickupLongitude(new BigDecimal("77.59460000"))
                .destinationLocationName("Koramangala, Bengaluru")
                .destinationLatitude(new BigDecimal("12.93520000"))
                .destinationLongitude(new BigDecimal("77.62450000"))
                .build();
    }

    private FareQuoteResponse buildQuoteResponse(String quoteToken) {
        return FareQuoteResponse.builder()
                .quoteToken(quoteToken)
                .pickupLatitude(new BigDecimal("12.97160000"))
                .pickupLongitude(new BigDecimal("77.59460000"))
                .destinationLatitude(new BigDecimal("12.93520000"))
                .destinationLongitude(new BigDecimal("77.62450000"))
                .distanceKm(5.42)
                .vehicleType("SEDAN")
                .baseFare(new BigDecimal("50.00"))
                .distanceFare(new BigDecimal("81.30"))
                .bookingFee(new BigDecimal("15.00"))
                .estimatedFare(new BigDecimal("146.30"))
                .currency("INR")
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .createdAt(LocalDateTime.now())
                .build();
    }

    private FeignException createFeign503() {
        Request request = Request.create(Request.HttpMethod.POST, "/api/v1/notifications",
                Collections.emptyMap(), null, new RequestTemplate());
        return new FeignException.ServiceUnavailable("Notification service down", request, null, null);
    }

    private FeignException createFeign500() {
        Request request = Request.create(Request.HttpMethod.POST, "/api/v1/notifications",
                Collections.emptyMap(), null, new RequestTemplate());
        return new FeignException.InternalServerError("Internal server error in notification service", request, null, null);
    }

    @Test
    @DisplayName("Should successfully create ride even when notification-service throws HTTP 503 Service Unavailable")
    void testCreateRideSucceedsWhenNotificationFails503() {
        String token = "quote-isolation-create-503";
        when(pricingClient.verifyQuote(token)).thenReturn(ResponseEntity.ok(buildQuoteResponse(token)));
        when(notificationClient.createNotification(any())).thenThrow(createFeign503());

        RideResponse response = rideService.createRide(101L, buildCreateRequest(token));

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(RideStatus.REQUESTED.name());
        assertThat(response.getPassengerId()).isEqualTo(101L);
    }

    @Test
    @DisplayName("Should successfully complete ride even when notification-service throws HTTP 500 Internal Error")
    void testCompleteRideSucceedsWhenNotificationFails500() {
        String token = "quote-isolation-complete-500";
        Long driverId = 921L;
        when(pricingClient.verifyQuote(token)).thenReturn(ResponseEntity.ok(buildQuoteResponse(token)));
        when(userClient.reserveDriver(eq(driverId), any())).thenReturn(ResponseEntity.ok(DriverReservationResponse.builder().status("RESERVED").build()));
        when(userClient.confirmRide(eq(driverId), any())).thenReturn(ResponseEntity.ok(DriverConfirmRideResponse.builder().status("ON_RIDE").build()));
        when(userClient.completeRide(eq(driverId), any())).thenReturn(ResponseEntity.ok(DriverCompleteRideResponse.builder().status("AVAILABLE").build()));

        RideResponse ride = rideService.createRide(101L, buildCreateRequest(token));
        rideService.acceptRide(ride.getId(), driverId);
        rideService.arriveAtPickup(ride.getId(), driverId);
        rideService.startRide(ride.getId(), driverId);

        // Configure notification failure on completion
        when(notificationClient.createNotification(any())).thenThrow(createFeign500());

        RideResponse completed = rideService.completeRide(ride.getId(), driverId);

        assertThat(completed).isNotNull();
        assertThat(completed.getStatus()).isEqualTo(RideStatus.COMPLETED.name());
        assertThat(completed.getFinalFare()).isEqualByComparingTo(new BigDecimal("146.30"));
    }

    @Test
    @DisplayName("Should successfully cancel ride even when notification-service throws 503")
    void testCancelRideSucceedsWhenNotificationFails503() {
        String token = "quote-isolation-cancel-503";
        when(pricingClient.verifyQuote(token)).thenReturn(ResponseEntity.ok(buildQuoteResponse(token)));
        when(notificationClient.createNotification(any())).thenThrow(createFeign503());

        RideResponse ride = rideService.createRide(101L, buildCreateRequest(token));

        RideResponse cancelled = rideService.cancelRide(ride.getId(), 101L, "ROLE_PASSENGER", CancelRideRequest.builder().reason("Change plans").build());

        assertThat(cancelled).isNotNull();
        assertThat(cancelled.getStatus()).isEqualTo(RideStatus.CANCELLED.name());
    }
}
