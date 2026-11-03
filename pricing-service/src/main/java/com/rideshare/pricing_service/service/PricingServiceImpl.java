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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PricingServiceImpl implements PricingService {

    private final PricingConfigurationRepository pricingConfigurationRepository;
    private final FareQuoteRepository fareQuoteRepository;
    private final HaversineDistanceCalculator distanceCalculator;
    private final Clock clock;

    @Override
    @Transactional
    public FareEstimateResponse estimateFare(FareEstimateRequest request) {
        log.info("Generating fare estimate for vehicleType: {}", request.getVehicleType());

        // Reject identical pickup and destination coordinates
        if (request.getPickupLatitude().compareTo(request.getDestinationLatitude()) == 0 &&
            request.getPickupLongitude().compareTo(request.getDestinationLongitude()) == 0) {
            throw new BadRequestException("Pickup and destination coordinates cannot be identical");
        }

        double distance = distanceCalculator.calculateDistance(
                request.getPickupLatitude(),
                request.getPickupLongitude(),
                request.getDestinationLatitude(),
                request.getDestinationLongitude()
        );

        if (distance == 0.0) {
            throw new BadRequestException("Pickup and destination coordinates cannot be identical");
        }

        PricingConfiguration config = pricingConfigurationRepository
                .findByVehicleTypeIgnoreCaseAndActiveTrue(request.getVehicleType().trim())
                .orElseThrow(() -> new BadRequestException(
                        "Unsupported or inactive vehicle type: " + request.getVehicleType()));

        BigDecimal fullPrecisionDistance = BigDecimal.valueOf(distance);
        BigDecimal displayDistanceKm = fullPrecisionDistance.setScale(2, RoundingMode.HALF_UP);
        BigDecimal distanceFare = fullPrecisionDistance.multiply(config.getRatePerKm()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal estimatedFare = config.getBaseFare().add(distanceFare).add(config.getBookingFee()).setScale(2, RoundingMode.HALF_UP);

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiresAt = now.plusMinutes(10);
        String quoteToken = "quote-" + UUID.randomUUID();

        FareQuote quote = FareQuote.builder()
                .quoteToken(quoteToken)
                .pickupLatitude(request.getPickupLatitude())
                .pickupLongitude(request.getPickupLongitude())
                .destinationLatitude(request.getDestinationLatitude())
                .destinationLongitude(request.getDestinationLongitude())
                .distanceKm(displayDistanceKm)
                .estimatedFare(estimatedFare)
                .vehicleType(config.getVehicleType())
                .baseFare(config.getBaseFare())
                .distanceFare(distanceFare)
                .bookingFee(config.getBookingFee())
                .currency(config.getCurrency())
                .expiresAt(expiresAt)
                .build();

        fareQuoteRepository.save(quote);

        return FareEstimateResponse.builder()
                .quoteToken(quoteToken)
                .distanceKm(displayDistanceKm.doubleValue())
                .baseFare(config.getBaseFare())
                .distanceFare(distanceFare)
                .bookingFee(config.getBookingFee())
                .estimatedFare(estimatedFare)
                .currency(config.getCurrency())
                .expiresAt(expiresAt)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public FareQuoteResponse verifyQuote(String quoteToken) {
        log.info("Verifying fare quote token: {}", quoteToken);

        FareQuote quote = fareQuoteRepository.findByQuoteToken(quoteToken)
                .orElseThrow(() -> new ResourceNotFoundException("Fare quote not found for token: " + quoteToken));

        LocalDateTime now = LocalDateTime.now(clock);
        if (now.isAfter(quote.getExpiresAt()) || now.isEqual(quote.getExpiresAt())) {
            throw new QuoteExpiredException("Fare quote with token '" + quoteToken + "' has expired at " + quote.getExpiresAt());
        }

        return FareQuoteResponse.builder()
                .quoteToken(quote.getQuoteToken())
                .pickupLatitude(quote.getPickupLatitude())
                .pickupLongitude(quote.getPickupLongitude())
                .destinationLatitude(quote.getDestinationLatitude())
                .destinationLongitude(quote.getDestinationLongitude())
                .distanceKm(quote.getDistanceKm().doubleValue())
                .vehicleType(quote.getVehicleType())
                .baseFare(quote.getBaseFare())
                .distanceFare(quote.getDistanceFare())
                .bookingFee(quote.getBookingFee())
                .estimatedFare(quote.getEstimatedFare())
                .currency(quote.getCurrency())
                .expiresAt(quote.getExpiresAt())
                .createdAt(quote.getCreatedAt())
                .build();
    }
}
