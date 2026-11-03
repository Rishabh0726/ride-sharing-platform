package com.rideshare.ride_service.client.dto;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DriverConfirmRideResponse {
    private Long driverId;
    private String status;
    private Long activeRideId;
    private LocalDateTime updatedAt;
}
