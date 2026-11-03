package com.rideshare.user_service.service;

import com.rideshare.user_service.dto.request.DriverActionRequest;
import com.rideshare.user_service.dto.request.DriverAvailabilityRequest;
import com.rideshare.user_service.dto.request.DriverReleaseRequest;
import com.rideshare.user_service.dto.request.DriverReservationRequest;
import com.rideshare.user_service.dto.response.*;

import java.math.BigDecimal;
import java.util.List;

public interface DriverService {

    DriverAvailabilityResponse toggleAvailability(Long authenticatedUserId, DriverAvailabilityRequest request);

    List<AvailableDriverResponse> findAvailableDrivers(BigDecimal lat, BigDecimal lon, Double radiusKm, Integer limit);

    DriverReservationResponse reserveDriver(Long driverId, DriverReservationRequest request);

    DriverConfirmRideResponse confirmRide(Long driverId, DriverActionRequest request);

    DriverReleaseResponse releaseReservation(Long driverId, DriverReleaseRequest request);

    DriverCompleteRideResponse completeRide(Long driverId, DriverActionRequest request);
}
