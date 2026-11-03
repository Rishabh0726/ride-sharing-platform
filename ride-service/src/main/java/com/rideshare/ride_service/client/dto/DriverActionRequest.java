package com.rideshare.ride_service.client.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DriverActionRequest {
    private Long rideId;
}
