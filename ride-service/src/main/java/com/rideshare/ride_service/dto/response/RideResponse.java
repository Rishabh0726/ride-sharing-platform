package com.rideshare.ride_service.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RideResponse {

    private Long id;
    private String rideUuid;
    private Long passengerId;
    private Long driverId;
    private String status;
    private String pickupLocationName;
    private BigDecimal pickupLatitude;
    private BigDecimal pickupLongitude;
    private String destinationLocationName;
    private BigDecimal destinationLatitude;
    private BigDecimal destinationLongitude;
    private BigDecimal distanceKm;
    private String fareQuoteToken;
    private BigDecimal estimatedFare;
    private BigDecimal finalFare;
    private BigDecimal baseFare;
    private BigDecimal distanceFare;
    private BigDecimal bookingFee;
    private String currency;
    private String vehicleType;
    private String cancellationReason;
    private String cancelledBy;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime acceptedAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime arrivedAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime startedAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime completedAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime cancelledAt;

    private Long version;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime createdAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime updatedAt;
}
