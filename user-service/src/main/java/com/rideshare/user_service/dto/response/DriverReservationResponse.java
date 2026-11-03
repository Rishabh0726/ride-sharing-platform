package com.rideshare.user_service.dto.response;

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
    private int leaseDurationSeconds;
}
