package com.rideshare.ride_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rideshare.ride_service.config.JacksonConfig;
import com.rideshare.ride_service.dto.request.CreateRideRequest;
import com.rideshare.ride_service.exception.ConflictException;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RideController.class)
@Import({SecurityConfig.class, AuthEntryPointJwt.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class, JacksonConfig.class})
@ActiveProfiles("test")
class RideControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RideService rideService;

    @MockitoBean
    private JwtUtils jwtUtils;

    private UserPrincipal passengerPrincipal;

    @BeforeEach
    void setUp() {
        passengerPrincipal = new UserPrincipal(
                101L,
                "passenger@example.com",
                "",
                List.of(new SimpleGrantedAuthority("ROLE_PASSENGER")),
                true
        );
    }

    @Test
    @DisplayName("Blank fare quote token is rejected with 400 Bad Request")
    void testBlankQuoteTokenRejected() throws Exception {
        CreateRideRequest request = CreateRideRequest.builder()
                .fareQuoteToken("")
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .build();

        mockMvc.perform(post("/api/v1/rides")
                        .with(user(passengerPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fareQuoteToken").value("Fare quote token is required"));
    }

    @Test
    @DisplayName("Out-of-range latitude is rejected with 400 Bad Request")
    void testInvalidLatitudeRejected() throws Exception {
        CreateRideRequest request = CreateRideRequest.builder()
                .fareQuoteToken("valid-token")
                .pickupLatitude(BigDecimal.valueOf(95.0)) // Invalid > 90.0
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .build();

        mockMvc.perform(post("/api/v1/rides")
                        .with(user(passengerPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pickupLatitude").value("Latitude must be between -90.0 and 90.0"));
    }

    @Test
    @DisplayName("Out-of-range longitude is rejected with 400 Bad Request")
    void testInvalidLongitudeRejected() throws Exception {
        CreateRideRequest request = CreateRideRequest.builder()
                .fareQuoteToken("valid-token")
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(-190.0)) // Invalid < -180.0
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .build();

        mockMvc.perform(post("/api/v1/rides")
                        .with(user(passengerPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pickupLongitude").value("Longitude must be between -180.0 and 180.0"));
    }

    @Test
    @DisplayName("Active ride conflict results in 409 Conflict with RFC 7807 ProblemDetail format")
    void testActiveRideConflictReturns409ProblemDetail() throws Exception {
        CreateRideRequest request = CreateRideRequest.builder()
                .fareQuoteToken("quote-conflict-token")
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .build();

        when(rideService.createRide(eq(101L), any(CreateRideRequest.class)))
                .thenThrow(new ConflictException("Passenger already has an active ride in progress"));

        mockMvc.perform(post("/api/v1/rides")
                        .with(user(passengerPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("Passenger already has an active ride in progress"))
                .andExpect(jsonPath("$.type").value("https://api.rideshare.com/errors/conflict"))
                .andExpect(jsonPath("$.instance").value("/api/v1/rides"))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
