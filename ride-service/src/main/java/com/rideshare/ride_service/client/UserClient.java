package com.rideshare.ride_service.client;

import com.rideshare.ride_service.client.dto.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "user-service", url = "${user-service.url:http://localhost:8081}")
public interface UserClient {

    @PostMapping("/api/v1/drivers/{driverId}/reserve")
    ResponseEntity<DriverReservationResponse> reserveDriver(
            @PathVariable("driverId") Long driverId,
            @RequestBody DriverReservationRequest request
    );

    @PostMapping("/api/v1/drivers/{driverId}/confirm-ride")
    ResponseEntity<DriverConfirmRideResponse> confirmRide(
            @PathVariable("driverId") Long driverId,
            @RequestBody DriverActionRequest request
    );

    @PostMapping("/api/v1/drivers/{driverId}/release-reservation")
    ResponseEntity<DriverReleaseResponse> releaseReservation(
            @PathVariable("driverId") Long driverId,
            @RequestBody DriverReleaseRequest request
    );

    @PostMapping("/api/v1/drivers/{driverId}/complete-ride")
    ResponseEntity<DriverCompleteRideResponse> completeRide(
            @PathVariable("driverId") Long driverId,
            @RequestBody DriverActionRequest request
    );
}
