package com.rideshare.ride_service.service;

import com.rideshare.ride_service.dto.request.CancelRideRequest;
import com.rideshare.ride_service.dto.request.CreateRideRequest;
import com.rideshare.ride_service.dto.response.RideEventResponse;
import com.rideshare.ride_service.dto.response.RideResponse;

import java.util.List;

public interface RideService {

    RideResponse createRide(Long passengerId, CreateRideRequest request);

    RideResponse getRideById(Long rideId, Long requestingUserId, String requestingUserRole);

    List<RideResponse> getPassengerRides(Long passengerId);

    RideResponse getDriverActiveRide(Long driverId);

    List<RideResponse> getAvailableRides();

    List<RideResponse> getDriverCompletedRides(Long driverId);

    RideResponse acceptRide(Long rideId, Long driverId);

    RideResponse arriveAtPickup(Long rideId, Long driverId);

    RideResponse startRide(Long rideId, Long driverId);

    RideResponse completeRide(Long rideId, Long driverId);

    RideResponse cancelRide(Long rideId, Long actorId, String actorRole, CancelRideRequest request);

    List<RideEventResponse> getRideEvents(Long rideId, Long requestingUserId, String requestingUserRole);
}
