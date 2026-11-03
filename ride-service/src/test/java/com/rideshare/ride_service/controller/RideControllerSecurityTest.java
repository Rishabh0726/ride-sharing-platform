package com.rideshare.ride_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rideshare.ride_service.config.JacksonConfig;
import com.rideshare.ride_service.dto.request.CancelRideRequest;
import com.rideshare.ride_service.dto.request.CreateRideRequest;
import com.rideshare.ride_service.dto.response.RideResponse;
import com.rideshare.ride_service.entity.RideStatus;
import com.rideshare.ride_service.exception.GlobalExceptionHandler;
import com.rideshare.ride_service.security.AuthEntryPointJwt;
import com.rideshare.ride_service.security.JwtAuthenticationFilter;
import com.rideshare.ride_service.security.JwtUtils;
import com.rideshare.ride_service.security.SecurityConfig;
import com.rideshare.ride_service.security.UserPrincipal;
import com.rideshare.ride_service.service.RideService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RideController.class)
@Import({SecurityConfig.class, AuthEntryPointJwt.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class, JacksonConfig.class})
@ActiveProfiles("test")
class RideControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RideService rideService;

    @MockitoBean
    private JwtUtils jwtUtils;

    private UserPrincipal passengerPrincipal;
    private UserPrincipal driverPrincipal;
    private UserPrincipal adminPrincipal;

    @BeforeEach
    void setUp() {
        passengerPrincipal = new UserPrincipal(
                101L,
                "passenger@example.com",
                "",
                List.of(new SimpleGrantedAuthority("ROLE_PASSENGER")),
                true
        );

        driverPrincipal = new UserPrincipal(
                202L,
                "driver@example.com",
                "",
                List.of(new SimpleGrantedAuthority("ROLE_DRIVER")),
                true
        );

        adminPrincipal = new UserPrincipal(
                303L,
                "admin@example.com",
                "",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")),
                true
        );
    }

    private CreateRideRequest sampleCreateRequest() {
        return CreateRideRequest.builder()
                .fareQuoteToken("quote-sec-test-token")
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .build();
    }

    private RideResponse sampleRideResponse(RideStatus status) {
        return RideResponse.builder()
                .id(1L)
                .rideUuid("uuid-123")
                .passengerId(101L)
                .driverId(status != RideStatus.REQUESTED ? 202L : null)
                .status(status.name())
                .estimatedFare(BigDecimal.valueOf(142.77))
                .currency("INR")
                .build();
    }

    @Test
    @DisplayName("Unauthenticated request to POST /api/v1/rides returns 401 Unauthorized")
    void testCreateRideUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/rides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleCreateRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ROLE_PASSENGER can create a ride request")
    void testPassengerCanCreateRide() throws Exception {
        when(rideService.createRide(eq(101L), any(CreateRideRequest.class)))
                .thenReturn(sampleRideResponse(RideStatus.REQUESTED));

        mockMvc.perform(post("/api/v1/rides")
                        .with(user(passengerPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleCreateRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"));
    }

    @Test
    @DisplayName("ROLE_DRIVER calling POST /api/v1/rides returns 403 Forbidden")
    void testDriverCannotCreateRide() throws Exception {
        mockMvc.perform(post("/api/v1/rides")
                        .with(user(driverPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleCreateRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ROLE_DRIVER can accept a ride")
    void testDriverCanAcceptRide() throws Exception {
        when(rideService.acceptRide(eq(1L), eq(202L)))
                .thenReturn(sampleRideResponse(RideStatus.ACCEPTED));

        mockMvc.perform(post("/api/v1/rides/1/accept")
                        .with(user(driverPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    @DisplayName("ROLE_PASSENGER calling /accept returns 403 Forbidden")
    void testPassengerCannotAcceptRide() throws Exception {
        mockMvc.perform(post("/api/v1/rides/1/accept")
                        .with(user(passengerPrincipal)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ROLE_DRIVER can advance lifecycle states: arrive, start, complete")
    void testDriverLifecycleEndpoints() throws Exception {
        when(rideService.arriveAtPickup(eq(1L), eq(202L)))
                .thenReturn(sampleRideResponse(RideStatus.DRIVER_ARRIVING));
        when(rideService.startRide(eq(1L), eq(202L)))
                .thenReturn(sampleRideResponse(RideStatus.IN_PROGRESS));
        when(rideService.completeRide(eq(1L), eq(202L)))
                .thenReturn(sampleRideResponse(RideStatus.COMPLETED));

        mockMvc.perform(post("/api/v1/rides/1/arrive").with(user(driverPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRIVER_ARRIVING"));

        mockMvc.perform(post("/api/v1/rides/1/start").with(user(driverPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        mockMvc.perform(post("/api/v1/rides/1/complete").with(user(driverPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("Passenger can cancel a ride")
    void testPassengerCanCancelRide() throws Exception {
        when(rideService.cancelRide(eq(1L), eq(101L), eq("ROLE_PASSENGER"), any()))
                .thenReturn(sampleRideResponse(RideStatus.CANCELLED));

        mockMvc.perform(post("/api/v1/rides/1/cancel")
                        .with(user(passengerPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CancelRideRequest.builder().reason("Change of plans").build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("ROLE_DRIVER cannot view passenger ride history")
    void testDriverCannotAccessPassengerRides() throws Exception {
        mockMvc.perform(get("/api/v1/rides/passenger/me")
                        .with(user(driverPrincipal)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ROLE_PASSENGER cannot view driver active ride")
    void testPassengerCannotAccessDriverActiveRide() throws Exception {
        mockMvc.perform(get("/api/v1/rides/driver/active")
                        .with(user(passengerPrincipal)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ROLE_DRIVER can access available requested rides feed")
    void testDriverCanAccessAvailableRides() throws Exception {
        when(rideService.getAvailableRides())
                .thenReturn(List.of(sampleRideResponse(RideStatus.REQUESTED)));

        mockMvc.perform(get("/api/v1/rides/driver/available")
                        .with(user(driverPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("REQUESTED"));
    }

    @Test
    @DisplayName("ROLE_PASSENGER calling /driver/available returns 403 Forbidden")
    void testPassengerCannotAccessAvailableRides() throws Exception {
        mockMvc.perform(get("/api/v1/rides/driver/available")
                        .with(user(passengerPrincipal)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Unauthenticated request to /driver/available returns 401 Unauthorized")
    void testUnauthenticatedCannotAccessAvailableRides() throws Exception {
        mockMvc.perform(get("/api/v1/rides/driver/available"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ROLE_DRIVER can access own completed ride history")
    void testDriverCanAccessCompletedHistory() throws Exception {
        when(rideService.getDriverCompletedRides(eq(202L)))
                .thenReturn(List.of(sampleRideResponse(RideStatus.COMPLETED)));

        mockMvc.perform(get("/api/v1/rides/driver/history")
                        .with(user(driverPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));
    }

    @Test
    @DisplayName("ROLE_PASSENGER calling /driver/history returns 403 Forbidden")
    void testPassengerCannotAccessDriverHistory() throws Exception {
        mockMvc.perform(get("/api/v1/rides/driver/history")
                        .with(user(passengerPrincipal)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Unauthenticated request to /driver/history returns 401 Unauthorized")
    void testUnauthenticatedCannotAccessDriverHistory() throws Exception {
        mockMvc.perform(get("/api/v1/rides/driver/history"))
                .andExpect(status().isUnauthorized());
    }
}
