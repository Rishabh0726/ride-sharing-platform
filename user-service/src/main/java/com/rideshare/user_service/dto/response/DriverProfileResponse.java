package com.rideshare.user_service.dto.response;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DriverProfileResponse {

    private Long driverId;
    private String vehicleModel;
    private String licensePlate;
    private String status;
    private BigDecimal ratingAvg;
}
