package com.rideshare.pricing_service.repository;

import com.rideshare.pricing_service.entity.FareQuote;
import com.rideshare.pricing_service.entity.PricingConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class PricingRepositoryTest {

    @Autowired
    private PricingConfigurationRepository pricingConfigurationRepository;

    @Autowired
    private FareQuoteRepository fareQuoteRepository;

    @Test
    @DisplayName("Save and find active PricingConfiguration by vehicle type (case-insensitive)")
    void shouldSaveAndFindActivePricingConfiguration() {
        PricingConfiguration config = PricingConfiguration.builder()
                .vehicleType("SEDAN")
                .baseFare(new BigDecimal("50.00"))
                .ratePerKm(new BigDecimal("15.00"))
                .bookingFee(new BigDecimal("15.00"))
                .currency("INR")
                .active(true)
                .build();

        pricingConfigurationRepository.save(config);

        Optional<PricingConfiguration> foundLower = pricingConfigurationRepository.findByVehicleTypeIgnoreCaseAndActiveTrue("sedan");
        assertThat(foundLower).isPresent();
        assertThat(foundLower.get().getBaseFare()).isEqualByComparingTo("50.00");

        Optional<PricingConfiguration> foundUpper = pricingConfigurationRepository.findByVehicleTypeIgnoreCaseAndActiveTrue("SEDAN");
        assertThat(foundUpper).isPresent();
    }

    @Test
    @DisplayName("Unique constraint on vehicleType in PricingConfiguration")
    void shouldEnforceUniqueConstraintOnVehicleType() {
        PricingConfiguration config1 = PricingConfiguration.builder()
                .vehicleType("SEDAN")
                .baseFare(new BigDecimal("50.00"))
                .ratePerKm(new BigDecimal("15.00"))
                .bookingFee(new BigDecimal("15.00"))
                .currency("INR")
                .active(true)
                .build();
        pricingConfigurationRepository.saveAndFlush(config1);

        PricingConfiguration config2 = PricingConfiguration.builder()
                .vehicleType("SEDAN")
                .baseFare(new BigDecimal("60.00"))
                .ratePerKm(new BigDecimal("18.00"))
                .bookingFee(new BigDecimal("20.00"))
                .currency("INR")
                .active(true)
                .build();

        assertThatThrownBy(() -> pricingConfigurationRepository.saveAndFlush(config2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Save and find FareQuote by quoteToken")
    void shouldSaveAndFindFareQuoteByToken() {
        FareQuote quote = FareQuote.builder()
                .quoteToken("quote-token-abc-123")
                .pickupLatitude(BigDecimal.valueOf(12.9716))
                .pickupLongitude(BigDecimal.valueOf(77.5946))
                .destinationLatitude(BigDecimal.valueOf(12.9352))
                .destinationLongitude(BigDecimal.valueOf(77.6245))
                .distanceKm(new BigDecimal("5.18"))
                .estimatedFare(new BigDecimal("142.70"))
                .vehicleType("SEDAN")
                .baseFare(new BigDecimal("50.00"))
                .distanceFare(new BigDecimal("77.70"))
                .bookingFee(new BigDecimal("15.00"))
                .currency("INR")
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();

        FareQuote saved = fareQuoteRepository.saveAndFlush(quote);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();

        Optional<FareQuote> retrieved = fareQuoteRepository.findByQuoteToken("quote-token-abc-123");
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getEstimatedFare()).isEqualByComparingTo("142.70");
    }

    @Test
    @DisplayName("Unique constraint on quoteToken in FareQuote")
    void shouldEnforceUniqueConstraintOnQuoteToken() {
        FareQuote quote1 = FareQuote.builder()
                .quoteToken("duplicate-token")
                .pickupLatitude(BigDecimal.valueOf(12.9716))
                .pickupLongitude(BigDecimal.valueOf(77.5946))
                .destinationLatitude(BigDecimal.valueOf(12.9352))
                .destinationLongitude(BigDecimal.valueOf(77.6245))
                .distanceKm(new BigDecimal("5.18"))
                .estimatedFare(new BigDecimal("142.70"))
                .vehicleType("SEDAN")
                .baseFare(new BigDecimal("50.00"))
                .distanceFare(new BigDecimal("77.70"))
                .bookingFee(new BigDecimal("15.00"))
                .currency("INR")
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
        fareQuoteRepository.saveAndFlush(quote1);

        FareQuote quote2 = FareQuote.builder()
                .quoteToken("duplicate-token")
                .pickupLatitude(BigDecimal.valueOf(12.9000))
                .pickupLongitude(BigDecimal.valueOf(77.5000))
                .destinationLatitude(BigDecimal.valueOf(12.9100))
                .destinationLongitude(BigDecimal.valueOf(77.5100))
                .distanceKm(new BigDecimal("2.00"))
                .estimatedFare(new BigDecimal("95.00"))
                .vehicleType("SEDAN")
                .baseFare(new BigDecimal("50.00"))
                .distanceFare(new BigDecimal("30.00"))
                .bookingFee(new BigDecimal("15.00"))
                .currency("INR")
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();

        assertThatThrownBy(() -> fareQuoteRepository.saveAndFlush(quote2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
