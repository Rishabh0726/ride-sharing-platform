package com.rideshare.ride_service.repository;

import com.rideshare.ride_service.entity.RideEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RideEventRepository extends JpaRepository<RideEvent, Long> {

    List<RideEvent> findByRideIdOrderByCreatedAtAsc(Long rideId);
}
