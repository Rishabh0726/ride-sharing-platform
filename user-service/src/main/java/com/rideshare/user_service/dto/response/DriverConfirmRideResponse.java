package com.rideshare.user_service.dto.response;

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
