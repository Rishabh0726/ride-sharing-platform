package com.rideshare.pricing_service.service;

import com.rideshare.pricing_service.dto.request.FareEstimateRequest;
import com.rideshare.pricing_service.dto.response.FareEstimateResponse;
import com.rideshare.pricing_service.dto.response.FareQuoteResponse;
import com.rideshare.pricing_service.entity.FareQuote;
import com.rideshare.pricing_service.entity.PricingConfiguration;
import com.rideshare.pricing_service.exception.BadRequestException;
import com.rideshare.pricing_service.exception.QuoteExpiredException;
import com.rideshare.pricing_service.exception.ResourceNotFoundException;
import com.rideshare.pricing_service.repository.FareQuoteRepository;
import com.rideshare.pricing_service.repository.PricingConfigurationRepository;
import com.rideshare.pricing_service.util.HaversineDistanceCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PricingServiceTest {

    @Mock
    private PricingConfigurationRepository pricingConfigurationRepository;

    @Mock
    private FareQuoteRepository fareQuoteRepository;

    private HaversineDistanceCalculator distanceCalculator;
    private PricingService pricingService;
    private Clock fixedClock;

    private final Instant baseInstant = Instant.parse("2026-10-21T10:00:00Z");
    private final ZoneId utcZone = ZoneId.of("UTC");

    private PricingConfiguration sedanConfig;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(baseInstant, utcZone);
        distanceCalculator = new HaversineDistanceCalculator();
        pricingService = new PricingServiceImpl(
                pricingConfigurationRepository,
                fareQuoteRepository,
                distanceCalculator,
                fixedClock
        );

        sedanConfig = PricingConfiguration.builder()
                .id(1L)
                .vehicleType("SEDAN")
                .baseFare(new BigDecimal("50.00"))
                .ratePerKm(new BigDecimal("15.00"))
                .bookingFee(new BigDecimal("15.00"))
                .currency("INR")
                .active(true)
                .build();
    }

    @Test
    @DisplayName("Estimate fare generates quote with exact SEDAN rates and 10-minute expiry")
    void shouldGenerateFareEstimateWithApprovedRatesAnd10MinExpiry() {
        FareEstimateRequest request = FareEstimateRequest.builder()
                .pickupLatitude(BigDecimal.valueOf(12.9716))
                .pickupLongitude(BigDecimal.valueOf(77.5946))
                .destinationLatitude(BigDecimal.valueOf(12.9352))
                .destinationLongitude(BigDecimal.valueOf(77.6245))
                .vehicleType("SEDAN")
                .build();

        when(pricingConfigurationRepository.findByVehicleTypeIgnoreCaseAndActiveTrue("SEDAN"))
                .thenReturn(Optional.of(sedanConfig));

        FareEstimateResponse response = pricingService.estimateFare(request);

        assertThat(response).isNotNull();
        assertThat(response.getQuoteToken()).startsWith("quote-");
        assertThat(response.getDistanceKm()).isEqualTo(5.18);
        assertThat(response.getBaseFare()).isEqualByComparingTo("50.00");
        assertThat(response.getDistanceFare()).isEqualByComparingTo("77.77"); // full-precision 5.184659... * 15.00 = 77.7698... -> 77.77
        assertThat(response.getBookingFee()).isEqualByComparingTo("15.00");
        assertThat(response.getEstimatedFare()).isEqualByComparingTo("142.77"); // 50.00 + 77.77 + 15.00 = 142.77
        assertThat(response.getCurrency()).isEqualTo("INR");
        assertThat(response.getExpiresAt()).isEqualTo(LocalDateTime.of(2026, 10, 21, 10, 10, 0));

        ArgumentCaptor<FareQuote> quoteCaptor = ArgumentCaptor.forClass(FareQuote.class);
        verify(fareQuoteRepository).save(quoteCaptor.capture());
        FareQuote saved = quoteCaptor.getValue();
        assertThat(saved.getQuoteToken()).isEqualTo(response.getQuoteToken());
        assertThat(saved.getPickupLatitude()).isEqualByComparingTo(BigDecimal.valueOf(12.9716));
        assertThat(saved.getDestinationLatitude()).isEqualByComparingTo(BigDecimal.valueOf(12.9352));
        assertThat(saved.getDistanceKm()).isEqualByComparingTo("5.18");
        assertThat(saved.getDistanceFare()).isEqualByComparingTo("77.77");
        assertThat(saved.getEstimatedFare()).isEqualByComparingTo("142.77");
        assertThat(saved.getVehicleType()).isEqualTo("SEDAN");
    }

    @Test
    @DisplayName("Regression proof: Full-precision distance determines fare (₹77.77 / ₹142.77), rejecting premature rounding (₹77.70 / ₹142.70)")
    void shouldProveFullPrecisionDistanceDeterminesFare() {
        FareEstimateRequest request = FareEstimateRequest.builder()
                .pickupLatitude(BigDecimal.valueOf(12.9716))
                .pickupLongitude(BigDecimal.valueOf(77.5946))
                .destinationLatitude(BigDecimal.valueOf(12.9352))
                .destinationLongitude(BigDecimal.valueOf(77.6245))
                .vehicleType("SEDAN")
                .build();

        when(pricingConfigurationRepository.findByVehicleTypeIgnoreCaseAndActiveTrue("SEDAN"))
                .thenReturn(Optional.of(sedanConfig));

        FareEstimateResponse response = pricingService.estimateFare(request);

        // Display distance remains rounded to 2 decimals
        assertThat(response.getDistanceKm()).isEqualTo(5.18);

        // Fare MUST be computed using unrounded distance (5.184659... * 15.00 = 77.7698... -> 77.77), NOT premature (5.18 * 15.00 = 77.70)
        assertThat(response.getDistanceFare()).isEqualByComparingTo("77.77");
        assertThat(response.getDistanceFare()).isNotEqualByComparingTo("77.70");

        assertThat(response.getEstimatedFare()).isEqualByComparingTo("142.77");
        assertThat(response.getEstimatedFare()).isNotEqualByComparingTo("142.70");
    }

    @Test
    @DisplayName("Reject identical pickup and destination coordinates with 400 Bad Request")
    void shouldRejectIdenticalCoordinates() {
        FareEstimateRequest request = FareEstimateRequest.builder()
                .pickupLatitude(BigDecimal.valueOf(12.9716))
                .pickupLongitude(BigDecimal.valueOf(77.5946))
                .destinationLatitude(BigDecimal.valueOf(12.9716))
                .destinationLongitude(BigDecimal.valueOf(77.5946))
                .vehicleType("SEDAN")
                .build();

        assertThatThrownBy(() -> pricingService.estimateFare(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Pickup and destination coordinates cannot be identical");

        verifyNoInteractions(pricingConfigurationRepository);
        verifyNoInteractions(fareQuoteRepository);
    }

    @Test
    @DisplayName("Reject unsupported or inactive vehicle type with 400 Bad Request")
    void shouldRejectUnsupportedVehicleType() {
        FareEstimateRequest request = FareEstimateRequest.builder()
                .pickupLatitude(BigDecimal.valueOf(12.9716))
                .pickupLongitude(BigDecimal.valueOf(77.5946))
                .destinationLatitude(BigDecimal.valueOf(12.9352))
                .destinationLongitude(BigDecimal.valueOf(77.6245))
                .vehicleType("HELICOPTER")
                .build();

        when(pricingConfigurationRepository.findByVehicleTypeIgnoreCaseAndActiveTrue("HELICOPTER"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> pricingService.estimateFare(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unsupported or inactive vehicle type");

        verify(fareQuoteRepository, never()).save(any());
    }

    @Test
    @DisplayName("Verify quote before expiry returns full original snapshot including coordinates")
    void shouldVerifyValidQuoteBeforeExpiry() {
        LocalDateTime expiresAt = LocalDateTime.of(2026, 10, 21, 10, 10, 0);
        FareQuote quote = FareQuote.builder()
                .id(1L)
                .quoteToken("quote-test-123")
                .pickupLatitude(BigDecimal.valueOf(12.9716))
                .pickupLongitude(BigDecimal.valueOf(77.5946))
                .destinationLatitude(BigDecimal.valueOf(12.9352))
                .destinationLongitude(BigDecimal.valueOf(77.6245))
                .distanceKm(new BigDecimal("5.18"))
                .vehicleType("SEDAN")
                .baseFare(new BigDecimal("50.00"))
                .distanceFare(new BigDecimal("77.77"))
                .bookingFee(new BigDecimal("15.00"))
                .estimatedFare(new BigDecimal("142.77"))
                .currency("INR")
                .expiresAt(expiresAt)
                .createdAt(LocalDateTime.of(2026, 10, 21, 10, 0, 0))
                .build();

        when(fareQuoteRepository.findByQuoteToken("quote-test-123")).thenReturn(Optional.of(quote));

        FareQuoteResponse response = pricingService.verifyQuote("quote-test-123");

        assertThat(response).isNotNull();
        assertThat(response.getQuoteToken()).isEqualTo("quote-test-123");
        assertThat(response.getPickupLatitude()).isEqualByComparingTo(BigDecimal.valueOf(12.9716));
        assertThat(response.getPickupLongitude()).isEqualByComparingTo(BigDecimal.valueOf(77.5946));
        assertThat(response.getDestinationLatitude()).isEqualByComparingTo(BigDecimal.valueOf(12.9352));
        assertThat(response.getDestinationLongitude()).isEqualByComparingTo(BigDecimal.valueOf(77.6245));
        assertThat(response.getEstimatedFare()).isEqualByComparingTo("142.77");
        assertThat(response.getExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("Quote verification is reusable (multiple calls succeed without mutating state)")
    void shouldAllowMultipleQuoteVerificationsWithoutMutation() {
        LocalDateTime expiresAt = LocalDateTime.of(2026, 10, 21, 10, 10, 0);
        FareQuote quote = FareQuote.builder()
                .quoteToken("quote-reusable-123")
                .pickupLatitude(BigDecimal.valueOf(12.9716))
                .pickupLongitude(BigDecimal.valueOf(77.5946))
                .destinationLatitude(BigDecimal.valueOf(12.9352))
                .destinationLongitude(BigDecimal.valueOf(77.6245))
                .distanceKm(new BigDecimal("5.18"))
                .vehicleType("SEDAN")
                .baseFare(new BigDecimal("50.00"))
                .distanceFare(new BigDecimal("77.77"))
                .bookingFee(new BigDecimal("15.00"))
                .estimatedFare(new BigDecimal("142.77"))
                .currency("INR")
                .expiresAt(expiresAt)
                .build();

        when(fareQuoteRepository.findByQuoteToken("quote-reusable-123")).thenReturn(Optional.of(quote));

        FareQuoteResponse response1 = pricingService.verifyQuote("quote-reusable-123");
        FareQuoteResponse response2 = pricingService.verifyQuote("quote-reusable-123");

        assertThat(response1.getQuoteToken()).isEqualTo(response2.getQuoteToken());
        assertThat(response1.getEstimatedFare()).isEqualByComparingTo(response2.getEstimatedFare());
        assertThat(response1.getEstimatedFare()).isEqualByComparingTo("142.77");
        verify(fareQuoteRepository, never()).save(any());
        assertThat(response1.getEstimatedFare()).isEqualByComparingTo(response2.getEstimatedFare());
        verify(fareQuoteRepository, never()).save(any());
    }

    @Test
    @DisplayName("Unknown quote token throws 404 ResourceNotFoundException")
    void shouldThrow404ForUnknownQuoteToken() {
        when(fareQuoteRepository.findByQuoteToken("quote-unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pricingService.verifyQuote("quote-unknown"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Fare quote not found for token: quote-unknown");
    }

    @Test
    @DisplayName("Quote at exact expiration boundary (t == expiresAt) throws 410 QuoteExpiredException")
    void shouldThrow410AtExactExpirationBoundary() {
        // baseInstant is 10:00:00. Set expiresAt to 10:00:00 (now == expiresAt)
        LocalDateTime exactExpiry = LocalDateTime.of(2026, 10, 21, 10, 0, 0);
        FareQuote quote = FareQuote.builder()
                .quoteToken("quote-expired")
                .expiresAt(exactExpiry)
                .build();

        when(fareQuoteRepository.findByQuoteToken("quote-expired")).thenReturn(Optional.of(quote));

        assertThatThrownBy(() -> pricingService.verifyQuote("quote-expired"))
                .isInstanceOf(QuoteExpiredException.class)
                .hasMessageContaining("has expired at");
    }

    @Test
    @DisplayName("Quote after expiration boundary (t > expiresAt) throws 410 QuoteExpiredException")
    void shouldThrow410AfterExpirationBoundary() {
        // baseInstant is 10:00:00. Set expiresAt to 09:59:59 (now > expiresAt)
        LocalDateTime pastExpiry = LocalDateTime.of(2026, 10, 21, 9, 59, 59);
        FareQuote quote = FareQuote.builder()
                .quoteToken("quote-expired")
                .expiresAt(pastExpiry)
                .build();

        when(fareQuoteRepository.findByQuoteToken("quote-expired")).thenReturn(Optional.of(quote));

        assertThatThrownBy(() -> pricingService.verifyQuote("quote-expired"))
                .isInstanceOf(QuoteExpiredException.class)
                .hasMessageContaining("has expired at");
    }
}
