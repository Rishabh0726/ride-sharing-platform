package com.rideshare.user_service.dto.response;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AvailableDriverResponse {

    private Long driverId;
    private String vehicleModel;
    private String licensePlate;
    private Double distanceKm;
    private BigDecimal ratingAvg;
}
