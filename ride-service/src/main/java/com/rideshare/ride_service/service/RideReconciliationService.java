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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RideReconciliationService {

    private final RideReconciliationAuditRepository auditRepository;
    private final RideRepository rideRepository;
    private final RideStateTransitionService transitionService;
    private final UserClient userClient;

    public static final int MAX_RETRIES = 5;

    @Transactional
    public void recordCompensationFailure(Long rideId, Long driverId, String operation, String errorMessage) {
        log.warn("Recording compensation failure for rideId: {}, driverId: {}, operation: {}, error: {}",
                rideId, driverId, operation, errorMessage);

        RideReconciliationAudit audit = RideReconciliationAudit.builder()
                .rideId(rideId)
                .driverId(driverId)
                .operation(operation)
                .retryCount(0)
                .status(AuditStatus.PENDING)
                .errorMessage(errorMessage)
                .build();

        auditRepository.save(audit);
    }

    @Scheduled(fixedDelay = 60000)
    public void reconcilePendingTasks() {
        List<RideReconciliationAudit> pendingTasks = auditRepository.findByStatusAndRetryCountLessThan(
                AuditStatus.PENDING, MAX_RETRIES
        );

        if (pendingTasks.isEmpty()) {
            return;
        }

        log.info("Found {} pending reconciliation tasks to execute", pendingTasks.size());

        for (RideReconciliationAudit task : pendingTasks) {
            executeReconciliationTask(task);
        }
    }

    public void executeReconciliationTask(RideReconciliationAudit task) {
        task.setRetryCount(task.getRetryCount() + 1);

        try {
            if ("COMPLETE_RIDE".equalsIgnoreCase(task.getOperation())) {
                userClient.completeRide(task.getDriverId(), DriverActionRequest.builder().rideId(task.getRideId()).build());
                task.setStatus(AuditStatus.RESOLVED);
                task.setErrorMessage(null);
                log.info("Reconciliation COMPLETE_RIDE task resolved for rideId: {}, driverId: {}", task.getRideId(), task.getDriverId());
            } else if ("RELEASE_RESERVATION".equalsIgnoreCase(task.getOperation())) {
                userClient.releaseReservation(task.getDriverId(), DriverReleaseRequest.builder().rideId(task.getRideId()).build());
                task.setStatus(AuditStatus.RESOLVED);
                task.setErrorMessage(null);
                log.info("Reconciliation RELEASE_RESERVATION task resolved for rideId: {}, driverId: {}", task.getRideId(), task.getDriverId());
            } else if ("CONFIRM_RIDE_PROBE".equalsIgnoreCase(task.getOperation())) {
                reconcileConfirmRideProbe(task);
            } else {
                task.setStatus(AuditStatus.MANUAL_ATTENTION);
                task.setErrorMessage("Unrecognized reconciliation operation: " + task.getOperation());
                log.error("Task {} has unrecognized operation: {}", task.getId(), task.getOperation());
            }
        } catch (FeignException.Conflict conflictEx) {
            // For COMPLETE_RIDE or RELEASE_RESERVATION, 409 means driver state has already moved on (lease expired, or ride already completed)
            log.info("Remote user-service returned 409 Conflict during reconciliation (driver state already transitioned): {}", conflictEx.getMessage());
            task.setStatus(AuditStatus.RESOLVED);
            task.setErrorMessage("Resolved via remote conflict confirmation: " + conflictEx.getMessage());
        } catch (Exception ex) {
            log.warn("Reconciliation attempt {} failed for task {}: {}", task.getRetryCount(), task.getId(), ex.getMessage());
            task.setErrorMessage(ex.getMessage());
            if (task.getRetryCount() >= MAX_RETRIES) {
                task.setStatus(AuditStatus.MANUAL_ATTENTION);
                log.error("Task {} exceeded max retries and requires MANUAL_ATTENTION", task.getId());
            }
        }

        auditRepository.save(task);
    }

    private void reconcileConfirmRideProbe(RideReconciliationAudit task) {
        Long rideId = task.getRideId();
        Long driverId = task.getDriverId();

        Optional<Ride> rideOpt = rideRepository.findById(rideId);
        if (rideOpt.isEmpty()) {
            task.setStatus(AuditStatus.MANUAL_ATTENTION);
            task.setErrorMessage("Ride not found with id: " + rideId);
            return;
        }

        Ride ride = rideOpt.get();

        // If local ride has already advanced beyond ACCEPTED (e.g. DRIVER_ARRIVING, IN_PROGRESS, COMPLETED)
        // or was CANCELLED, we must not execute a stale rollback!
        if (ride.getStatus() != RideStatus.ACCEPTED) {
            log.info("Ride {} is in status {}, no rollback needed. Marking task RESOLVED.", rideId, ride.getStatus());
            task.setStatus(AuditStatus.RESOLVED);
            task.setErrorMessage("Ride has already transitioned to status: " + ride.getStatus());
            return;
        }

        try {
            ResponseEntity<DriverReleaseResponse> probeResponse = userClient.releaseReservation(
                    driverId, DriverReleaseRequest.builder().rideId(rideId).build()
            );

            // Probe returned 200 OK: reservation was still held and is now released.
            // Confirm never executed remotely. Conditionally roll back local ride if still in ACCEPTED.
            boolean rolledBack = transitionService.rollbackAcceptanceLocally(
                    rideId, driverId, "Reconciliation: driver reservation was released; rolled back to REQUESTED"
            );
            task.setStatus(AuditStatus.RESOLVED);
            task.setErrorMessage(rolledBack ? "Rollback executed: ride returned to REQUESTED" : "Ride state changed concurrently, rollback skipped");
        } catch (FeignException.Conflict conflictEx) {
            String msg = conflictEx.getMessage() != null ? conflictEx.getMessage().toLowerCase() : "";
            if (msg.contains("active trip")) {
                // Driver is confirmed ON_RIDE for this ride. Remote confirm succeeded! Local ACCEPTED is aligned.
                log.info("Probe confirmed driver {} is ON_RIDE for ride {}. Local state ACCEPTED is consistent.", driverId, rideId);
                task.setStatus(AuditStatus.RESOLVED);
                task.setErrorMessage("Remote confirm verified via active trip status");
            } else if (msg.contains("reserved by another ride") || msg.contains("stale release request")) {
                // Driver lease expired and was claimed by another ride. Roll back local ride if still in ACCEPTED.
                transitionService.rollbackAcceptanceLocally(
                        rideId, driverId, "Reconciliation: driver lease expired or claimed by another ride"
                );
                task.setStatus(AuditStatus.RESOLVED);
                task.setErrorMessage("Lease expired and reclaimed by another ride; local ride rolled back to REQUESTED");
            } else {
                // Ambiguous 409 Conflict: do not assume success or release
                log.warn("Ambiguous conflict from probe during reconciliation for ride {}: {}", rideId, conflictEx.getMessage());
                task.setErrorMessage("Ambiguous remote probe conflict: " + conflictEx.getMessage());
                if (task.getRetryCount() >= MAX_RETRIES) {
                    task.setStatus(AuditStatus.MANUAL_ATTENTION);
                }
            }
        }
    }
}
