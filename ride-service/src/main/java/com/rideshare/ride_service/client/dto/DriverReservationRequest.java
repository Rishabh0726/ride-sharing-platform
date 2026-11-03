package com.rideshare.ride_service.client.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DriverReservationRequest {
    private Long rideId;
}
