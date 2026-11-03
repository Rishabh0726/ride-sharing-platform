package com.rideshare.pricing_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rideshare.pricing_service.config.JacksonConfig;
import com.rideshare.pricing_service.dto.request.FareEstimateRequest;
import com.rideshare.pricing_service.dto.response.FareEstimateResponse;
import com.rideshare.pricing_service.dto.response.FareQuoteResponse;
import com.rideshare.pricing_service.exception.BadRequestException;
import com.rideshare.pricing_service.exception.GlobalExceptionHandler;
import com.rideshare.pricing_service.exception.QuoteExpiredException;
import com.rideshare.pricing_service.exception.ResourceNotFoundException;
import com.rideshare.pricing_service.security.AuthEntryPointJwt;
import com.rideshare.pricing_service.security.JwtAuthenticationFilter;
import com.rideshare.pricing_service.security.JwtUtils;
import com.rideshare.pricing_service.security.SecurityConfig;
import com.rideshare.pricing_service.security.UserPrincipal;
import com.rideshare.pricing_service.service.PricingService;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PricingController.class)
@Import({SecurityConfig.class, AuthEntryPointJwt.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class, JacksonConfig.class})
@ActiveProfiles("test")
class PricingControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PricingService pricingService;

    @MockitoBean
    private JwtUtils jwtUtils;

    private UserPrincipal passengerPrincipal;
    private UserPrincipal driverPrincipal;
    private UserPrincipal internalServicePrincipal;

    @BeforeEach
    void setUp() {
        passengerPrincipal = new UserPrincipal(
                101L,
                "passenger@example.com",
                "hashedPass",
                List.of(new SimpleGrantedAuthority("ROLE_PASSENGER")),
                true
        );

        driverPrincipal = new UserPrincipal(
                102L,
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
    @DisplayName("Unauthenticated requests to estimate and verify endpoints return 401 Unauthorized")
    void shouldReturn401WhenUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/pricing/estimate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(get("/api/v1/pricing/quotes/quote-test-123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Authenticated Passenger calling /estimate returns 200 OK")
    void shouldAllowPassengerToEstimateFare() throws Exception {
        FareEstimateRequest request = FareEstimateRequest.builder()
                .pickupLatitude(BigDecimal.valueOf(12.9716))
                .pickupLongitude(BigDecimal.valueOf(77.5946))
                .destinationLatitude(BigDecimal.valueOf(12.9352))
                .destinationLongitude(BigDecimal.valueOf(77.6245))
                .vehicleType("SEDAN")
                .build();

        FareEstimateResponse response = FareEstimateResponse.builder()
                .quoteToken("quote-test-123")
                .distanceKm(5.18)
                .baseFare(new BigDecimal("50.00"))
                .distanceFare(new BigDecimal("77.77"))
                .bookingFee(new BigDecimal("15.00"))
                .estimatedFare(new BigDecimal("142.77"))
                .currency("INR")
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();

        when(pricingService.estimateFare(any(FareEstimateRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/pricing/estimate")
                        .with(user(passengerPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quoteToken").value("quote-test-123"))
                .andExpect(jsonPath("$.estimatedFare").value(142.77))
                .andExpect(jsonPath("$.currency").value("INR"));
    }

    @Test
    @DisplayName("Authenticated Driver calling /estimate returns 200 OK")
    void shouldAllowDriverToEstimateFare() throws Exception {
        FareEstimateRequest request = FareEstimateRequest.builder()
                .pickupLatitude(BigDecimal.valueOf(12.9716))
                .pickupLongitude(BigDecimal.valueOf(77.5946))
                .destinationLatitude(BigDecimal.valueOf(12.9352))
                .destinationLongitude(BigDecimal.valueOf(77.6245))
                .vehicleType("SEDAN")
                .build();

        FareEstimateResponse response = FareEstimateResponse.builder()
                .quoteToken("quote-test-123")
                .distanceKm(5.18)
                .baseFare(new BigDecimal("50.00"))
                .distanceFare(new BigDecimal("77.77"))
                .bookingFee(new BigDecimal("15.00"))
                .estimatedFare(new BigDecimal("142.77"))
                .currency("INR")
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();

        when(pricingService.estimateFare(any(FareEstimateRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/pricing/estimate")
                        .with(user(driverPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quoteToken").value("quote-test-123"));
    }

    @Test
    @DisplayName("Authenticated Internal Service calling /estimate returns 200 OK")
    void shouldAllowInternalServiceToEstimateFare() throws Exception {
        FareEstimateRequest request = FareEstimateRequest.builder()
                .pickupLatitude(BigDecimal.valueOf(12.9716))
                .pickupLongitude(BigDecimal.valueOf(77.5946))
                .destinationLatitude(BigDecimal.valueOf(12.9352))
                .destinationLongitude(BigDecimal.valueOf(77.6245))
                .vehicleType("SEDAN")
                .build();

        FareEstimateResponse response = FareEstimateResponse.builder()
                .quoteToken("quote-test-123")
                .distanceKm(5.18)
                .baseFare(new BigDecimal("50.00"))
                .distanceFare(new BigDecimal("77.77"))
                .bookingFee(new BigDecimal("15.00"))
                .estimatedFare(new BigDecimal("142.77"))
                .currency("INR")
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();

        when(pricingService.estimateFare(any(FareEstimateRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/pricing/estimate")
                        .with(user(internalServicePrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quoteToken").value("quote-test-123"));
    }

    @Test
    @DisplayName("Authenticated request to /quotes/{quoteToken} returns 200 OK with full snapshot")
    void shouldVerifyValidQuote() throws Exception {
        FareQuoteResponse response = FareQuoteResponse.builder()
                .quoteToken("quote-test-123")
                .pickupLatitude(BigDecimal.valueOf(12.9716))
                .pickupLongitude(BigDecimal.valueOf(77.5946))
                .destinationLatitude(BigDecimal.valueOf(12.9352))
                .destinationLongitude(BigDecimal.valueOf(77.6245))
                .distanceKm(5.18)
                .vehicleType("SEDAN")
                .baseFare(new BigDecimal("50.00"))
                .distanceFare(new BigDecimal("77.77"))
                .bookingFee(new BigDecimal("15.00"))
                .estimatedFare(new BigDecimal("142.77"))
                .currency("INR")
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .createdAt(LocalDateTime.now())
                .build();

        when(pricingService.verifyQuote(eq("quote-test-123"))).thenReturn(response);

        mockMvc.perform(get("/api/v1/pricing/quotes/quote-test-123")
                        .with(user(internalServicePrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quoteToken").value("quote-test-123"))
                .andExpect(jsonPath("$.pickupLatitude").value(12.9716))
                .andExpect(jsonPath("$.destinationLatitude").value(12.9352))
                .andExpect(jsonPath("$.estimatedFare").value(142.77));
    }

    @Test
    @DisplayName("Passenger calling /quotes/{quoteToken} returns 403 Forbidden (restricted to ROLE_INTERNAL_SERVICE)")
    void shouldForbidPassengerFromVerifyQuoteEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/pricing/quotes/quote-test-123")
                        .with(user(passengerPrincipal)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Driver calling /quotes/{quoteToken} returns 403 Forbidden (restricted to ROLE_INTERNAL_SERVICE)")
    void shouldForbidDriverFromVerifyQuoteEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/pricing/quotes/quote-test-123")
                        .with(user(driverPrincipal)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @DisplayName("Unknown quote token for internal service returns 404 Not Found")
    void shouldReturn404ForUnknownQuote() throws Exception {
        when(pricingService.verifyQuote(eq("quote-unknown")))
                .thenThrow(new ResourceNotFoundException("Fare quote not found for token: quote-unknown"));

        mockMvc.perform(get("/api/v1/pricing/quotes/quote-unknown")
                        .with(user(internalServicePrincipal)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Fare quote not found for token: quote-unknown"));
    }

    @Test
    @DisplayName("Expired quote token for internal service returns 410 Gone")
    void shouldReturn410ForExpiredQuote() throws Exception {
        when(pricingService.verifyQuote(eq("quote-expired")))
                .thenThrow(new QuoteExpiredException("Fare quote with token 'quote-expired' has expired"));

        mockMvc.perform(get("/api/v1/pricing/quotes/quote-expired")
                        .with(user(internalServicePrincipal)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value(410))
                .andExpect(jsonPath("$.title").value("Quote Expired"));
    }

    @Test
    @DisplayName("Invalid request payload (missing fields or out-of-range coordinates) returns 400 Bad Request")
    void shouldReturn400ForValidationFailures() throws Exception {
        FareEstimateRequest invalidRequest = FareEstimateRequest.builder()
                .pickupLatitude(BigDecimal.valueOf(95.00)) // invalid > 90
                .pickupLongitude(BigDecimal.valueOf(77.5946))
                .destinationLatitude(null) // missing
                .destinationLongitude(BigDecimal.valueOf(77.6245))
                .vehicleType("") // blank
                .build();

        mockMvc.perform(post("/api/v1/pricing/estimate")
                        .with(user(passengerPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.errors.pickupLatitude").exists())
                .andExpect(jsonPath("$.errors.destinationLatitude").exists())
                .andExpect(jsonPath("$.errors.vehicleType").exists());
    }

    @Test
    @DisplayName("Identical coordinates return 400 Bad Request from service")
    void shouldReturn400WhenServiceRejectsIdenticalCoordinates() throws Exception {
        FareEstimateRequest sameCoords = FareEstimateRequest.builder()
                .pickupLatitude(BigDecimal.valueOf(12.9716))
                .pickupLongitude(BigDecimal.valueOf(77.5946))
                .destinationLatitude(BigDecimal.valueOf(12.9716))
                .destinationLongitude(BigDecimal.valueOf(77.5946))
                .vehicleType("SEDAN")
                .build();

        when(pricingService.estimateFare(any(FareEstimateRequest.class)))
                .thenThrow(new BadRequestException("Pickup and destination coordinates cannot be identical"));

        mockMvc.perform(post("/api/v1/pricing/estimate")
                        .with(user(passengerPrincipal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sameCoords)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Pickup and destination coordinates cannot be identical"));
    }
}
