package com.rideshare.user_service.dto.response;

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
