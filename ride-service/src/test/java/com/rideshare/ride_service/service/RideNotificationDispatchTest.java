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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
public class RideNotificationDispatchTest {

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

    @Test
    @DisplayName("Should dispatch RIDE_REQUESTED notification to passenger on ride creation")
    void testDispatchOnRideCreation() {
        String token = "quote-notif-create-test";
        when(pricingClient.verifyQuote(token)).thenReturn(ResponseEntity.ok(buildQuoteResponse(token)));

        RideResponse response = rideService.createRide(101L, buildCreateRequest(token));

        ArgumentCaptor<CreateNotificationRequest> captor = ArgumentCaptor.forClass(CreateNotificationRequest.class);
        verify(notificationClient, atLeastOnce()).createNotification(captor.capture());

        CreateNotificationRequest captured = captor.getValue();
        assertThat(captured.getRecipientUserId()).isEqualTo(101L);
        assertThat(captured.getRecipientRole()).isEqualTo("ROLE_PASSENGER");
        assertThat(captured.getEventType()).isEqualTo("RIDE_REQUESTED");
        assertThat(captured.getTitle()).isEqualTo("Ride Requested");
        assertThat(captured.getIdempotencyKey()).isEqualTo("ride-" + response.getId() + "-RIDE_REQUESTED-ROLE_PASSENGER-101");
    }

    @Test
    @DisplayName("Should fan out notifications to both passenger (RIDE_ACCEPTED) and driver (DRIVER_ASSIGNED) on acceptance")
    void testDispatchOnRideAcceptance() {
        String token = "quote-notif-accept-test";
        Long driverId = 911L;
        when(pricingClient.verifyQuote(token)).thenReturn(ResponseEntity.ok(buildQuoteResponse(token)));
        when(userClient.reserveDriver(eq(driverId), any())).thenReturn(ResponseEntity.ok(
                DriverReservationResponse.builder().driverId(driverId).status("RESERVED").build()
        ));
        when(userClient.confirmRide(eq(driverId), any())).thenReturn(ResponseEntity.ok(
                DriverConfirmRideResponse.builder().driverId(driverId).activeRideId(1L).status("ON_RIDE").build()
        ));

        RideResponse ride = rideService.createRide(101L, buildCreateRequest(token));
        reset(notificationClient);

        rideService.acceptRide(ride.getId(), driverId);

        ArgumentCaptor<CreateNotificationRequest> captor = ArgumentCaptor.forClass(CreateNotificationRequest.class);
        verify(notificationClient, times(2)).createNotification(captor.capture());

        List<CreateNotificationRequest> requests = captor.getAllValues();
        CreateNotificationRequest passengerNotif = requests.stream()
                .filter(r -> r.getRecipientRole().equals("ROLE_PASSENGER"))
                .findFirst().orElseThrow();
        CreateNotificationRequest driverNotif = requests.stream()
                .filter(r -> r.getRecipientRole().equals("ROLE_DRIVER"))
                .findFirst().orElseThrow();

        assertThat(passengerNotif.getEventType()).isEqualTo("RIDE_ACCEPTED");
        assertThat(passengerNotif.getTitle()).isEqualTo("Driver Assigned");
        assertThat(passengerNotif.getMessage()).contains("Driver #" + driverId);

        assertThat(driverNotif.getEventType()).isEqualTo("DRIVER_ASSIGNED");
        assertThat(driverNotif.getTitle()).isEqualTo("Ride Confirmed");
        assertThat(driverNotif.getMessage()).contains("Ride #" + ride.getId());
    }

    @Test
    @DisplayName("Should dispatch RIDE_COMPLETED with structured amount and formatted fare to both passenger and driver")
    void testDispatchOnRideCompletion() {
        String token = "quote-notif-complete-test";
        Long driverId = 912L;
        when(pricingClient.verifyQuote(token)).thenReturn(ResponseEntity.ok(buildQuoteResponse(token)));
        when(userClient.reserveDriver(eq(driverId), any())).thenReturn(ResponseEntity.ok(DriverReservationResponse.builder().status("RESERVED").build()));
        when(userClient.confirmRide(eq(driverId), any())).thenReturn(ResponseEntity.ok(DriverConfirmRideResponse.builder().status("ON_RIDE").build()));
        when(userClient.completeRide(eq(driverId), any())).thenReturn(ResponseEntity.ok(DriverCompleteRideResponse.builder().status("AVAILABLE").build()));

        RideResponse ride = rideService.createRide(101L, buildCreateRequest(token));
        rideService.acceptRide(ride.getId(), driverId);
        rideService.arriveAtPickup(ride.getId(), driverId);
        rideService.startRide(ride.getId(), driverId);

        reset(notificationClient);

        rideService.completeRide(ride.getId(), driverId);

        ArgumentCaptor<CreateNotificationRequest> captor = ArgumentCaptor.forClass(CreateNotificationRequest.class);
        verify(notificationClient, times(2)).createNotification(captor.capture());

        List<CreateNotificationRequest> requests = captor.getAllValues();
        CreateNotificationRequest passengerNotif = requests.stream()
                .filter(r -> r.getRecipientRole().equals("ROLE_PASSENGER"))
                .findFirst().orElseThrow();
        CreateNotificationRequest driverNotif = requests.stream()
                .filter(r -> r.getRecipientRole().equals("ROLE_DRIVER"))
                .findFirst().orElseThrow();

        assertThat(passengerNotif.getEventType()).isEqualTo("RIDE_COMPLETED");
        assertThat(passengerNotif.getAmount()).isEqualByComparingTo(new BigDecimal("146.30"));
        assertThat(passengerNotif.getMessage()).contains("Final fare: ₹146.30");

        assertThat(driverNotif.getEventType()).isEqualTo("RIDE_COMPLETED");
        assertThat(driverNotif.getAmount()).isEqualByComparingTo(new BigDecimal("146.30"));
        assertThat(driverNotif.getMessage()).contains("Fare recorded: ₹146.30");
    }

    @Test
    @DisplayName("Should dispatch cancellation notification ONLY to passenger when ride is unassigned (REQUESTED)")
    void testDispatchOnUnassignedCancellation() {
        String token = "quote-notif-cancel-unassigned";
        when(pricingClient.verifyQuote(token)).thenReturn(ResponseEntity.ok(buildQuoteResponse(token)));

        RideResponse ride = rideService.createRide(101L, buildCreateRequest(token));
        reset(notificationClient);

        rideService.cancelRide(ride.getId(), 101L, "ROLE_PASSENGER", CancelRideRequest.builder().reason("Change of plans").build());

        ArgumentCaptor<CreateNotificationRequest> captor = ArgumentCaptor.forClass(CreateNotificationRequest.class);
        verify(notificationClient, times(1)).createNotification(captor.capture());

        CreateNotificationRequest notif = captor.getValue();
        assertThat(notif.getRecipientUserId()).isEqualTo(101L);
        assertThat(notif.getRecipientRole()).isEqualTo("ROLE_PASSENGER");
        assertThat(notif.getEventType()).isEqualTo("RIDE_CANCELLED");
        assertThat(notif.getMessage()).contains("Change of plans");
    }

    @Test
    @DisplayName("Should dispatch cancellation notification to BOTH passenger and driver when driver cancels assigned ride")
    void testDispatchOnDriverCancellation() {
        String token = "quote-notif-cancel-assigned";
        Long driverId = 913L;
        when(pricingClient.verifyQuote(token)).thenReturn(ResponseEntity.ok(buildQuoteResponse(token)));
        when(userClient.reserveDriver(eq(driverId), any())).thenReturn(ResponseEntity.ok(DriverReservationResponse.builder().status("RESERVED").build()));
        when(userClient.confirmRide(eq(driverId), any())).thenReturn(ResponseEntity.ok(DriverConfirmRideResponse.builder().status("ON_RIDE").build()));

        RideResponse ride = rideService.createRide(101L, buildCreateRequest(token));
        rideService.acceptRide(ride.getId(), driverId);

        reset(notificationClient);

        rideService.cancelRide(ride.getId(), driverId, "ROLE_DRIVER", CancelRideRequest.builder().reason("Flat tire").build());

        ArgumentCaptor<CreateNotificationRequest> captor = ArgumentCaptor.forClass(CreateNotificationRequest.class);
        verify(notificationClient, times(2)).createNotification(captor.capture());

        List<CreateNotificationRequest> requests = captor.getAllValues();
        CreateNotificationRequest passengerNotif = requests.stream()
                .filter(r -> r.getRecipientRole().equals("ROLE_PASSENGER"))
                .findFirst().orElseThrow();
        CreateNotificationRequest driverNotif = requests.stream()
                .filter(r -> r.getRecipientRole().equals("ROLE_DRIVER"))
                .findFirst().orElseThrow();

        assertThat(passengerNotif.getMessage()).contains("cancelled by the driver: Flat tire");
        assertThat(driverNotif.getMessage()).contains("You cancelled Ride #" + ride.getId() + ". Reason: Flat tire.");
    }
}
