package com.rideshare.pricing_service.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FareEstimateResponse {

    private String quoteToken;
    private Double distanceKm;
    private BigDecimal baseFare;
    private BigDecimal distanceFare;
    private BigDecimal bookingFee;
    private BigDecimal estimatedFare;
    private String currency;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime expiresAt;
}
