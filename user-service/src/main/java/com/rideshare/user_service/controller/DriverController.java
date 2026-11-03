package com.rideshare.user_service.controller;

import com.rideshare.user_service.dto.request.DriverActionRequest;
import com.rideshare.user_service.dto.request.DriverAvailabilityRequest;
import com.rideshare.user_service.dto.request.DriverReleaseRequest;
import com.rideshare.user_service.dto.request.DriverReservationRequest;
import com.rideshare.user_service.dto.response.*;
import com.rideshare.user_service.security.UserPrincipal;
import com.rideshare.user_service.service.DriverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/drivers")
@RequiredArgsConstructor
@Tag(name = "Driver Management", description = "Endpoints for driver availability, discovery, and distributed lease lifecycle")
@SecurityRequirement(name = "bearerAuth")
public class DriverController {

    private final DriverService driverService;

    @PatchMapping("/availability")
    @PreAuthorize("hasAuthority('ROLE_DRIVER')")
    @Operation(summary = "Toggle driver availability and update GPS coordinates")
    public ResponseEntity<DriverAvailabilityResponse> toggleAvailability(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody DriverAvailabilityRequest request) {
        DriverAvailabilityResponse response = driverService.toggleAvailability(principal.getId(), request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/available")
    @PreAuthorize("hasAnyAuthority('ROLE_PASSENGER', 'ROLE_INTERNAL_SERVICE')")
    @Operation(summary = "Discover available drivers within a geographical radius sorted by proximity")
    public ResponseEntity<List<AvailableDriverResponse>> findAvailableDrivers(
            @RequestParam("latitude") BigDecimal latitude,
            @RequestParam("longitude") BigDecimal longitude,
            @RequestParam(value = "radiusKm", required = false) Double radiusKm,
            @RequestParam(value = "limit", required = false) Integer limit) {
        List<AvailableDriverResponse> response = driverService.findAvailableDrivers(latitude, longitude, radiusKm, limit);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{driverId}/reserve")
    @PreAuthorize("hasAuthority('ROLE_INTERNAL_SERVICE')")
    @Operation(summary = "Acquire a 30-second distributed reservation lease for a driver")
    public ResponseEntity<DriverReservationResponse> reserveDriver(
            @PathVariable("driverId") Long driverId,
            @Valid @RequestBody DriverReservationRequest request) {
        DriverReservationResponse response = driverService.reserveDriver(driverId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{driverId}/confirm-ride")
    @PreAuthorize("hasAuthority('ROLE_INTERNAL_SERVICE')")
    @Operation(summary = "Confirm driver assignment and transition to ON_RIDE status")
    public ResponseEntity<DriverConfirmRideResponse> confirmRide(
            @PathVariable("driverId") Long driverId,
            @Valid @RequestBody DriverActionRequest request) {
        DriverConfirmRideResponse response = driverService.confirmRide(driverId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{driverId}/release-reservation")
    @PreAuthorize("hasAuthority('ROLE_INTERNAL_SERVICE')")
    @Operation(summary = "Release a reservation lease with strict idempotency guarantees")
    public ResponseEntity<DriverReleaseResponse> releaseReservation(
            @PathVariable("driverId") Long driverId,
            @Valid @RequestBody DriverReleaseRequest request) {
        DriverReleaseResponse response = driverService.releaseReservation(driverId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{driverId}/complete-ride")
    @PreAuthorize("hasAuthority('ROLE_INTERNAL_SERVICE')")
    @Operation(summary = "Complete an active ride and restore driver to AVAILABLE status")
    public ResponseEntity<DriverCompleteRideResponse> completeRide(
            @PathVariable("driverId") Long driverId,
            @Valid @RequestBody DriverActionRequest request) {
        DriverCompleteRideResponse response = driverService.completeRide(driverId, request);
        return ResponseEntity.ok(response);
    }
}
