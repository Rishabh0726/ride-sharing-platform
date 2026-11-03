package com.rideshare.user_service.dto.response;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DriverAvailabilityResponse {

    private Long driverId;
    private String status;
    private Boolean online;
    private LocalDateTime updatedAt;
    private LocalDateTime locationUpdatedAt;
}
