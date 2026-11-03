package com.rideshare.user_service.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DriverActionRequest {

    @NotNull(message = "Ride ID is required")
    private Long rideId;
}
