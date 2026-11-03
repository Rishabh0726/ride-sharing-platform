package com.rideshare.user_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rideshare.user_service.config.JacksonConfig;
import com.rideshare.user_service.dto.request.DriverActionRequest;
import com.rideshare.user_service.dto.request.DriverAvailabilityRequest;
import com.rideshare.user_service.dto.request.DriverReleaseRequest;
import com.rideshare.user_service.dto.request.DriverReservationRequest;
import com.rideshare.user_service.dto.response.*;
import com.rideshare.user_service.exception.GlobalExceptionHandler;
import com.rideshare.user_service.security.AuthEntryPointJwt;
import com.rideshare.user_service.security.JwtAuthenticationFilter;
import com.rideshare.user_service.security.JwtUtils;
import com.rideshare.user_service.security.SecurityConfig;
import com.rideshare.user_service.security.UserPrincipal;
import com.rideshare.user_service.service.DriverService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DriverController.class)
@Import({SecurityConfig.class, AuthEntryPointJwt.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class, JacksonConfig.class})
@ActiveProfiles("test")
class DriverControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DriverService driverService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private UserDetailsService userDetailsService;

    private UserPrincipal passengerPrincipal;
    private UserPrincipal driverPrincipal;
    private UserPrincipal internalServicePrincipal;

    @BeforeEach
    void setUp() {
        passengerPrincipal = new UserPrincipal(
                1L,
                "passenger@example.com",
                "hashedPass",
                List.of(new SimpleGrantedAuthority("ROLE_PASSENGER")),
                true
        );

        driverPrincipal = new UserPrincipal(
                2L,
                "driver@example.com",
                "hashedPass",
                List.of(new SimpleGrantedAuthority("ROLE_DRIVER")),
                true
        );

        internalServicePrincipal = new UserPrincipal(
                999L,
                "ride-service@internal",
                "hashedPass",
                List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE")),
                true
        );
    }

    @Test
    @DisplayName("Unauthenticated requests to all operational and availability endpoints return 401 Unauthorized")
    void shouldReturnUnauthorizedWhenUnauthenticated() throws Exception {
        mockMvc.perform(patch("/api/v1/drivers/availability")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(get("/api/v1/drivers/available?latitude=12.97&longitude=77.59"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(post("/api/v1/drivers/1/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rideId\": 501}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(post("/api/v1/drivers/1/confirm-ride")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rideId\": 501}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(post("/api/v1/drivers/1/release-reservation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rideId\": 501}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(post("/api/v1/drivers/1/complete-ride")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rideId\": 501}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Passenger calling /availability returns 403 Forbidden")
    void shouldForbidPassengerFromAvailabilityEndpoint() throws Exception {
        DriverAvailabilityRequest request = DriverAvailabilityRequest.builder()
                .online(true)
                .latitude(BigDecimal.valueOf(12.9716))
                .longitude(BigDecimal.valueOf(77.5946))
                .build();

        mockMvc.perform(patch("/api/v1/drivers/availability")
                        .with(user(passengerPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Passenger calling operational endpoints returns 403 Forbidden")
    void shouldForbidPassengerFromOperationalEndpoints() throws Exception {
        mockMvc.perform(post("/api/v1/drivers/1/reserve")
                        .with(user(passengerPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rideId\": 501}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(post("/api/v1/drivers/1/confirm-ride")
                        .with(user(passengerPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rideId\": 501}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(post("/api/v1/drivers/1/release-reservation")
                        .with(user(passengerPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rideId\": 501}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(post("/api/v1/drivers/1/complete-ride")
                        .with(user(passengerPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rideId\": 501}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Driver calling operational endpoints returns 403 Forbidden")
    void shouldForbidDriverFromOperationalEndpoints() throws Exception {
        mockMvc.perform(post("/api/v1/drivers/1/reserve")
                        .with(user(driverPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rideId\": 501}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(post("/api/v1/drivers/1/confirm-ride")
                        .with(user(driverPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rideId\": 501}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(post("/api/v1/drivers/1/release-reservation")
                        .with(user(driverPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rideId\": 501}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(post("/api/v1/drivers/1/complete-ride")
                        .with(user(driverPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rideId\": 501}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Driver calling /available discovery returns 403 Forbidden (restricted to passenger / internal service)")
    void shouldForbidDriverFromDiscoveryEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/drivers/available?latitude=12.9716&longitude=77.5946")
                        .with(user(driverPrincipal)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Passenger calling /available discovery returns 200 OK")
    void shouldAllowPassengerDiscoveryEndpoint() throws Exception {
        when(driverService.findAvailableDrivers(any(), any(), any(), any()))
                .thenReturn(List.of(
                        AvailableDriverResponse.builder()
                                .driverId(1L)
                                .vehicleModel("Prius")
                                .licensePlate("DL-01")
                                .distanceKm(1.2)
                                .ratingAvg(BigDecimal.valueOf(4.9))
                                .build()
                ));

        mockMvc.perform(get("/api/v1/drivers/available?latitude=12.9716&longitude=77.5946")
                        .with(user(passengerPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].driverId").value(1))
                .andExpect(jsonPath("$[0].vehicleModel").value("Prius"));
    }

    @Test
    @DisplayName("Driver calling /availability returns 200 OK")
    void shouldAllowDriverAvailabilityEndpoint() throws Exception {
        DriverAvailabilityRequest request = DriverAvailabilityRequest.builder()
                .online(true)
                .latitude(BigDecimal.valueOf(12.9716))
                .longitude(BigDecimal.valueOf(77.5946))
                .build();

        DriverAvailabilityResponse response = DriverAvailabilityResponse.builder()
                .driverId(1L)
                .status("AVAILABLE")
                .online(true)
                .updatedAt(LocalDateTime.now())
                .locationUpdatedAt(LocalDateTime.now())
                .build();

        when(driverService.toggleAvailability(eq(2L), any())).thenReturn(response);

        mockMvc.perform(patch("/api/v1/drivers/availability")
                        .with(user(driverPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverId").value(1))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.online").value(true));
    }

    @Test
    @DisplayName("Internal service calling operational endpoints returns 200 OK")
    void shouldAllowInternalServiceOnOperationalEndpoints() throws Exception {
        DriverReservationResponse resResp = DriverReservationResponse.builder().driverId(1L).status("RESERVED").reservedByRideId(501L).leaseDurationSeconds(30).build();
        DriverConfirmRideResponse confResp = DriverConfirmRideResponse.builder().driverId(1L).status("ON_RIDE").activeRideId(501L).build();
        DriverReleaseResponse relResp = DriverReleaseResponse.builder().driverId(1L).status("RELEASED").driverStatus("AVAILABLE").build();
        DriverCompleteRideResponse compResp = DriverCompleteRideResponse.builder().driverId(1L).status("COMPLETED").driverStatus("AVAILABLE").build();

        when(driverService.reserveDriver(eq(1L), any())).thenReturn(resResp);
        when(driverService.confirmRide(eq(1L), any())).thenReturn(confResp);
        when(driverService.releaseReservation(eq(1L), any())).thenReturn(relResp);
        when(driverService.completeRide(eq(1L), any())).thenReturn(compResp);

        mockMvc.perform(post("/api/v1/drivers/1/reserve")
                        .with(user(internalServicePrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rideId\": 501}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"));

        mockMvc.perform(post("/api/v1/drivers/1/confirm-ride")
                        .with(user(internalServicePrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rideId\": 501}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ON_RIDE"));

        mockMvc.perform(post("/api/v1/drivers/1/release-reservation")
                        .with(user(internalServicePrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rideId\": 501}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));

        mockMvc.perform(post("/api/v1/drivers/1/complete-ride")
                        .with(user(internalServicePrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rideId\": 501}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }
}
