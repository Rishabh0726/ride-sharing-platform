package com.rideshare.ride_service.client.dto;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DriverReservationResponse {
    private Long driverId;
    private String status;
    private Long reservedByRideId;
    private LocalDateTime reservationExpiresAt;
    private Integer leaseDurationSeconds;
}
