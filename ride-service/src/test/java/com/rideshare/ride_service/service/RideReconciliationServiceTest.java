package com.rideshare.ride_service.service;

import com.rideshare.ride_service.client.UserClient;
import com.rideshare.ride_service.client.dto.DriverActionRequest;
import com.rideshare.ride_service.client.dto.DriverReleaseRequest;
import com.rideshare.ride_service.client.dto.DriverReleaseResponse;
import com.rideshare.ride_service.entity.AuditStatus;
import com.rideshare.ride_service.entity.Ride;
import com.rideshare.ride_service.entity.RideReconciliationAudit;
import com.rideshare.ride_service.entity.RideStatus;
import com.rideshare.ride_service.repository.RideReconciliationAuditRepository;
import com.rideshare.ride_service.repository.RideRepository;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class RideReconciliationServiceTest {

    @Autowired
    private RideReconciliationService reconciliationService;

    @Autowired
    private RideReconciliationAuditRepository auditRepository;

    @Autowired
    private RideRepository rideRepository;

    @MockitoBean
    private UserClient userClient;

    private Request dummyRequest() {
        return Request.create(Request.HttpMethod.POST, "/url", Collections.emptyMap(), null, StandardCharsets.UTF_8, new RequestTemplate());
    }

    private Ride createTestRide(RideStatus status, Long driverId) {
        Ride ride = Ride.builder()
                .rideUuid(UUID.randomUUID().toString())
                .passengerId(101L)
                .driverId(driverId)
                .status(status)
                .pickupLocationName("Koramangala")
                .pickupLatitude(BigDecimal.valueOf(12.9352))
                .pickupLongitude(BigDecimal.valueOf(77.6245))
                .destinationLocationName("Indiranagar")
                .destinationLatitude(BigDecimal.valueOf(12.9784))
                .destinationLongitude(BigDecimal.valueOf(77.6408))
                .distanceKm(BigDecimal.valueOf(5.18))
                .fareQuoteToken("quote-reconcile-" + UUID.randomUUID())
                .estimatedFare(BigDecimal.valueOf(142.77))
                .baseFare(BigDecimal.valueOf(50.00))
                .distanceFare(BigDecimal.valueOf(77.77))
                .bookingFee(BigDecimal.valueOf(15.00))
                .currency("INR")
                .vehicleType("SEDAN")
                .build();
        return rideRepository.save(ride);
    }

    @Test
    @DisplayName("reconcilePendingTasks resolves task when remote completeRide succeeds")
    void testReconcileCompleteRideSuccess() {
        RideReconciliationAudit task = RideReconciliationAudit.builder()
                .rideId(991L)
                .driverId(881L)
                .operation("COMPLETE_RIDE")
                .retryCount(0)
                .status(AuditStatus.PENDING)
                .errorMessage("Read timeout")
                .build();
        task = auditRepository.save(task);

        reconciliationService.reconcilePendingTasks();

        RideReconciliationAudit refreshed = auditRepository.findById(task.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(AuditStatus.RESOLVED);
        assertThat(refreshed.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("reconcilePendingTasks resolves task when remote user-service returns 409 Conflict")
    void testReconcileHandles409AsResolved() {
        RideReconciliationAudit task = RideReconciliationAudit.builder()
                .rideId(992L)
                .driverId(882L)
                .operation("COMPLETE_RIDE")
                .retryCount(0)
                .status(AuditStatus.PENDING)
                .build();
        task = auditRepository.save(task);

        doThrow(new FeignException.Conflict("Driver not active on this ride", dummyRequest(), null, null))
                .when(userClient).completeRide(eq(882L), any(DriverActionRequest.class));

        reconciliationService.reconcilePendingTasks();

        RideReconciliationAudit refreshed = auditRepository.findById(task.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(AuditStatus.RESOLVED);
        assertThat(refreshed.getErrorMessage()).contains("Resolved via remote conflict confirmation");
    }

    @Test
    @DisplayName("Exceeding MAX_RETRIES transitions task to MANUAL_ATTENTION")
    void testMaxRetriesTransitionsToManualAttention() {
        RideReconciliationAudit task = RideReconciliationAudit.builder()
                .rideId(993L)
                .driverId(883L)
                .operation("COMPLETE_RIDE")
                .retryCount(RideReconciliationService.MAX_RETRIES - 1)
                .status(AuditStatus.PENDING)
                .build();
        task = auditRepository.save(task);

        doThrow(new RuntimeException("Connection refused"))
                .when(userClient).completeRide(eq(883L), any(DriverActionRequest.class));

        reconciliationService.executeReconciliationTask(task);

        RideReconciliationAudit refreshed = auditRepository.findById(task.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(AuditStatus.MANUAL_ATTENTION);
        assertThat(refreshed.getRetryCount()).isEqualTo(RideReconciliationService.MAX_RETRIES);
    }

    @Test
    @DisplayName("CONFIRM_RIDE_PROBE resolves when probe confirms driver is ON_RIDE (active trip)")
    void testReconcileConfirmRideProbeResolvesWhenDriverOnRide() {
        Long driverId = 884L;
        Ride ride = createTestRide(RideStatus.ACCEPTED, driverId);

        RideReconciliationAudit task = RideReconciliationAudit.builder()
                .rideId(ride.getId())
                .driverId(driverId)
                .operation("CONFIRM_RIDE_PROBE")
                .retryCount(0)
                .status(AuditStatus.PENDING)
                .build();
        task = auditRepository.save(task);

        // Probe releaseReservation throws 409 Conflict with "active trip"
        when(userClient.releaseReservation(eq(driverId), any(DriverReleaseRequest.class)))
                .thenThrow(new FeignException.Conflict("Driver is currently on an active trip", dummyRequest(), null, null));

        reconciliationService.executeReconciliationTask(task);

        RideReconciliationAudit refreshedTask = auditRepository.findById(task.getId()).orElseThrow();
        assertThat(refreshedTask.getStatus()).isEqualTo(AuditStatus.RESOLVED);
        assertThat(refreshedTask.getErrorMessage()).contains("Remote confirm verified via active trip status");

        // Local ride remains in ACCEPTED status with driver
        Ride refreshedRide = rideRepository.findById(ride.getId()).orElseThrow();
        assertThat(refreshedRide.getStatus()).isEqualTo(RideStatus.ACCEPTED);
        assertThat(refreshedRide.getDriverId()).isEqualTo(driverId);
    }

    @Test
    @DisplayName("CONFIRM_RIDE_PROBE rolls back local ride to REQUESTED when probe confirms driver was released")
    void testReconcileConfirmRideProbeRollsBackWhenDriverReleased() {
        Long driverId = 885L;
        Ride ride = createTestRide(RideStatus.ACCEPTED, driverId);

        RideReconciliationAudit task = RideReconciliationAudit.builder()
                .rideId(ride.getId())
                .driverId(driverId)
                .operation("CONFIRM_RIDE_PROBE")
                .retryCount(0)
                .status(AuditStatus.PENDING)
                .build();
        task = auditRepository.save(task);

        // Probe releaseReservation succeeds with 200 OK
        when(userClient.releaseReservation(eq(driverId), any(DriverReleaseRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverReleaseResponse.builder().driverId(driverId).status("RELEASED").build()));

        reconciliationService.executeReconciliationTask(task);

        RideReconciliationAudit refreshedTask = auditRepository.findById(task.getId()).orElseThrow();
        assertThat(refreshedTask.getStatus()).isEqualTo(AuditStatus.RESOLVED);
        assertThat(refreshedTask.getErrorMessage()).contains("Rollback executed");

        // Local ride is rolled back to REQUESTED with null driverId
        Ride refreshedRide = rideRepository.findById(ride.getId()).orElseThrow();
        assertThat(refreshedRide.getStatus()).isEqualTo(RideStatus.REQUESTED);
        assertThat(refreshedRide.getDriverId()).isNull();
    }

    @Test
    @DisplayName("CONFIRM_RIDE_PROBE with ambiguous 409 conflict is not marked RESOLVED and escalates on max retries")
    void testReconcileConfirmRideProbeAmbiguousConflictNotResolved() {
        Long driverId = 886L;
        Ride ride = createTestRide(RideStatus.ACCEPTED, driverId);

        RideReconciliationAudit task = RideReconciliationAudit.builder()
                .rideId(ride.getId())
                .driverId(driverId)
                .operation("CONFIRM_RIDE_PROBE")
                .retryCount(0)
                .status(AuditStatus.PENDING)
                .build();
        task = auditRepository.save(task);

        // Probe returns ambiguous conflict (neither active trip nor lease expired)
        when(userClient.releaseReservation(eq(driverId), any(DriverReleaseRequest.class)))
                .thenThrow(new FeignException.Conflict("Cannot release driver reservation in status: OFFLINE", dummyRequest(), null, null));

        reconciliationService.executeReconciliationTask(task);

        RideReconciliationAudit refreshedTask = auditRepository.findById(task.getId()).orElseThrow();
        assertThat(refreshedTask.getStatus()).isEqualTo(AuditStatus.PENDING);
        assertThat(refreshedTask.getErrorMessage()).contains("Ambiguous remote probe conflict");

        // Local ride remains intact
        Ride refreshedRide = rideRepository.findById(ride.getId()).orElseThrow();
        assertThat(refreshedRide.getStatus()).isEqualTo(RideStatus.ACCEPTED);

        // When retried to MAX_RETRIES, transitions to MANUAL_ATTENTION
        refreshedTask.setRetryCount(RideReconciliationService.MAX_RETRIES - 1);
        auditRepository.save(refreshedTask);

        reconciliationService.executeReconciliationTask(refreshedTask);

        RideReconciliationAudit finalTask = auditRepository.findById(task.getId()).orElseThrow();
        assertThat(finalTask.getStatus()).isEqualTo(AuditStatus.MANUAL_ATTENTION);
    }

    @Test
    @DisplayName("CONFIRM_RIDE_PROBE skips rollback if local ride has already advanced concurrently")
    void testReconcileConfirmRideProbeSkipsRollbackIfRideAdvancedConcurrently() {
        Long driverId = 887L;
        // Ride is already CANCELLED
        Ride ride = createTestRide(RideStatus.CANCELLED, driverId);

        RideReconciliationAudit task = RideReconciliationAudit.builder()
                .rideId(ride.getId())
                .driverId(driverId)
                .operation("CONFIRM_RIDE_PROBE")
                .retryCount(0)
                .status(AuditStatus.PENDING)
                .build();
        task = auditRepository.save(task);

        reconciliationService.executeReconciliationTask(task);

        RideReconciliationAudit refreshedTask = auditRepository.findById(task.getId()).orElseThrow();
        assertThat(refreshedTask.getStatus()).isEqualTo(AuditStatus.RESOLVED);
        assertThat(refreshedTask.getErrorMessage()).contains("Ride has already transitioned to status: CANCELLED");

        // Ride remains CANCELLED (no stale rollback to REQUESTED)
        Ride refreshedRide = rideRepository.findById(ride.getId()).orElseThrow();
        assertThat(refreshedRide.getStatus()).isEqualTo(RideStatus.CANCELLED);
    }

    @Test
    @DisplayName("Reconciliation execution is idempotent across repeated invocations")
    void testReconciliationReprocessingIsIdempotent() {
        Long driverId = 888L;
        Ride ride = createTestRide(RideStatus.ACCEPTED, driverId);

        RideReconciliationAudit task = RideReconciliationAudit.builder()
                .rideId(ride.getId())
                .driverId(driverId)
                .operation("CONFIRM_RIDE_PROBE")
                .retryCount(0)
                .status(AuditStatus.PENDING)
                .build();
        task = auditRepository.save(task);

        when(userClient.releaseReservation(eq(driverId), any(DriverReleaseRequest.class)))
                .thenReturn(ResponseEntity.ok(DriverReleaseResponse.builder().driverId(driverId).status("RELEASED").build()));

        // First execution rolls back
        reconciliationService.executeReconciliationTask(task);
        RideReconciliationAudit taskAfterFirst = auditRepository.findById(task.getId()).orElseThrow();
        assertThat(taskAfterFirst.getStatus()).isEqualTo(AuditStatus.RESOLVED);

        // Second execution on already-resolved/rolled-back state runs cleanly without error
        reconciliationService.executeReconciliationTask(taskAfterFirst);
        RideReconciliationAudit taskAfterSecond = auditRepository.findById(task.getId()).orElseThrow();
        assertThat(taskAfterSecond.getStatus()).isEqualTo(AuditStatus.RESOLVED);
    }

    @Test
    @DisplayName("Unrecognized reconciliation operation transitions to MANUAL_ATTENTION without false resolution")
    void testUnrecognizedOperationTransitionsToManualAttention() {
        RideReconciliationAudit task = RideReconciliationAudit.builder()
                .rideId(999L)
                .driverId(889L)
                .operation("UNKNOWN_OPERATION")
                .retryCount(0)
                .status(AuditStatus.PENDING)
                .build();
        task = auditRepository.save(task);

        reconciliationService.executeReconciliationTask(task);

        RideReconciliationAudit refreshed = auditRepository.findById(task.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(AuditStatus.MANUAL_ATTENTION);
        assertThat(refreshed.getErrorMessage()).contains("Unrecognized reconciliation operation");
    }
}
