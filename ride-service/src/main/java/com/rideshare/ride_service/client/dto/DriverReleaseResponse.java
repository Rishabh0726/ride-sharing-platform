package com.rideshare.ride_service.client.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DriverReleaseResponse {
    private Long driverId;
    private String status;
    private String driverStatus;
}
