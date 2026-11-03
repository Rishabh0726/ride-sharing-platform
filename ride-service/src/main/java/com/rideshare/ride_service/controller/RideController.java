package com.rideshare.ride_service.controller;

import com.rideshare.ride_service.dto.request.CancelRideRequest;
import com.rideshare.ride_service.dto.request.CreateRideRequest;
import com.rideshare.ride_service.dto.response.RideEventResponse;
import com.rideshare.ride_service.dto.response.RideResponse;
import com.rideshare.ride_service.security.UserPrincipal;
import com.rideshare.ride_service.service.RideService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/rides")
@RequiredArgsConstructor
@Tag(name = "Ride Lifecycle & Management", description = "Endpoints for ride creation, driver lifecycle state machine, and ride cancellation")
@SecurityRequirement(name = "bearerAuth")
public class RideController {

    private final RideService rideService;

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_PASSENGER')")
    @Operation(summary = "Create a new ride request referencing a valid pricing quote token")
    public ResponseEntity<RideResponse> createRide(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateRideRequest request
    ) {
        RideResponse response = rideService.createRide(principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{rideId}")
    @PreAuthorize("hasAnyAuthority('ROLE_PASSENGER', 'ROLE_DRIVER', 'ROLE_ADMIN', 'ROLE_INTERNAL_SERVICE')")
    @Operation(summary = "Get ride details by ID with ownership validation")
    public ResponseEntity<RideResponse> getRideById(
            @PathVariable("rideId") Long rideId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        String role = extractPrimaryRole(principal);
        RideResponse response = rideService.getRideById(rideId, principal.getId(), role);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/passenger/me")
    @PreAuthorize("hasAuthority('ROLE_PASSENGER')")
    @Operation(summary = "Get ride history for authenticated passenger")
    public ResponseEntity<List<RideResponse>> getPassengerRides(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        List<RideResponse> response = rideService.getPassengerRides(principal.getId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/driver/active")
    @PreAuthorize("hasAuthority('ROLE_DRIVER')")
    @Operation(summary = "Get current active ride for authenticated driver")
    public ResponseEntity<RideResponse> getDriverActiveRide(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        RideResponse response = rideService.getDriverActiveRide(principal.getId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/driver/available")
    @PreAuthorize("hasAuthority('ROLE_DRIVER')")
    @Operation(summary = "Get available ride requests awaiting driver acceptance")
    public ResponseEntity<List<RideResponse>> getAvailableRides() {
        List<RideResponse> response = rideService.getAvailableRides();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/driver/history")
    @PreAuthorize("hasAuthority('ROLE_DRIVER')")
    @Operation(summary = "Get completed ride history for authenticated driver")
    public ResponseEntity<List<RideResponse>> getDriverCompletedRides(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        List<RideResponse> response = rideService.getDriverCompletedRides(principal.getId());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{rideId}/accept")
    @PreAuthorize("hasAuthority('ROLE_DRIVER')")
    @Operation(summary = "Accept a requested ride via distributed 4-phase lease protocol")
    public ResponseEntity<RideResponse> acceptRide(
            @PathVariable("rideId") Long rideId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        RideResponse response = rideService.acceptRide(rideId, principal.getId());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{rideId}/arrive")
    @PreAuthorize("hasAuthority('ROLE_DRIVER')")
    @Operation(summary = "Driver indicates arrival at passenger pickup location")
    public ResponseEntity<RideResponse> arriveAtPickup(
            @PathVariable("rideId") Long rideId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        RideResponse response = rideService.arriveAtPickup(rideId, principal.getId());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{rideId}/start")
    @PreAuthorize("hasAuthority('ROLE_DRIVER')")
    @Operation(summary = "Driver starts ride trip with passenger aboard")
    public ResponseEntity<RideResponse> startRide(
            @PathVariable("rideId") Long rideId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        RideResponse response = rideService.startRide(rideId, principal.getId());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{rideId}/complete")
    @PreAuthorize("hasAuthority('ROLE_DRIVER')")
    @Operation(summary = "Driver completes ride at destination and sets final fare")
    public ResponseEntity<RideResponse> completeRide(
            @PathVariable("rideId") Long rideId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        RideResponse response = rideService.completeRide(rideId, principal.getId());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{rideId}/cancel")
    @PreAuthorize("hasAnyAuthority('ROLE_PASSENGER', 'ROLE_DRIVER', 'ROLE_ADMIN')")
    @Operation(summary = "Cancel ride according to state machine rules and release assigned driver")
    public ResponseEntity<RideResponse> cancelRide(
            @PathVariable("rideId") Long rideId,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody(required = false) CancelRideRequest request
    ) {
        String role = extractPrimaryRole(principal);
        RideResponse response = rideService.cancelRide(rideId, principal.getId(), role, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{rideId}/events")
    @PreAuthorize("hasAnyAuthority('ROLE_PASSENGER', 'ROLE_DRIVER', 'ROLE_ADMIN', 'ROLE_INTERNAL_SERVICE')")
    @Operation(summary = "Get chronological lifecycle event history for a ride")
    public ResponseEntity<List<RideEventResponse>> getRideEvents(
            @PathVariable("rideId") Long rideId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        String role = extractPrimaryRole(principal);
        List<RideEventResponse> response = rideService.getRideEvents(rideId, principal.getId(), role);
        return ResponseEntity.ok(response);
    }

    private String extractPrimaryRole(UserPrincipal principal) {
        return principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst()
                .orElse("ROLE_PASSENGER");
    }
}
