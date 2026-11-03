package com.rideshare.ride_service.service;

import com.rideshare.ride_service.client.NotificationClient;
import com.rideshare.ride_service.client.dto.CreateNotificationRequest;
import com.rideshare.ride_service.entity.Ride;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
@Slf4j
public class RideNotificationService {

    private final NotificationClient notificationClient;

    public void notifyRideRequested(Ride ride) {
        String idempotencyKey = "ride-" + ride.getId() + "-RIDE_REQUESTED-ROLE_PASSENGER-" + ride.getPassengerId();
        CreateNotificationRequest request = CreateNotificationRequest.builder()
                .recipientUserId(ride.getPassengerId())
                .recipientRole("ROLE_PASSENGER")
                .eventType("RIDE_REQUESTED")
                .title("Ride Requested")
                .message("Your ride request has been submitted. Searching for nearby drivers...")
                .referenceRideId(ride.getId())
                .idempotencyKey(idempotencyKey)
                .build();
        dispatchNotificationSafe(request);
    }

    public void notifyRideAccepted(Ride ride) {
        // 1. Notify Passenger
        String passengerKey = "ride-" + ride.getId() + "-RIDE_ACCEPTED-ROLE_PASSENGER-" + ride.getPassengerId();
        CreateNotificationRequest passengerReq = CreateNotificationRequest.builder()
                .recipientUserId(ride.getPassengerId())
                .recipientRole("ROLE_PASSENGER")
                .eventType("RIDE_ACCEPTED")
                .title("Driver Assigned")
                .message("Driver #" + ride.getDriverId() + " has accepted your ride request and is on the way.")
                .referenceRideId(ride.getId())
                .idempotencyKey(passengerKey)
                .build();
        dispatchNotificationSafe(passengerReq);

        // 2. Notify Driver
        if (ride.getDriverId() != null) {
            String driverKey = "ride-" + ride.getId() + "-DRIVER_ASSIGNED-ROLE_DRIVER-" + ride.getDriverId();
            CreateNotificationRequest driverReq = CreateNotificationRequest.builder()
                    .recipientUserId(ride.getDriverId())
                    .recipientRole("ROLE_DRIVER")
                    .eventType("DRIVER_ASSIGNED")
                    .title("Ride Confirmed")
                    .message("You have accepted Ride #" + ride.getId() + ". Please proceed to pickup.")
                    .referenceRideId(ride.getId())
                    .idempotencyKey(driverKey)
                    .build();
            dispatchNotificationSafe(driverReq);
        }
    }

    public void notifyDriverArrived(Ride ride) {
        String idempotencyKey = "ride-" + ride.getId() + "-DRIVER_ARRIVING-ROLE_PASSENGER-" + ride.getPassengerId();
        CreateNotificationRequest request = CreateNotificationRequest.builder()
                .recipientUserId(ride.getPassengerId())
                .recipientRole("ROLE_PASSENGER")
                .eventType("DRIVER_ARRIVING")
                .title("Driver Arrived")
                .message("Your driver has arrived at the pickup location.")
                .referenceRideId(ride.getId())
                .idempotencyKey(idempotencyKey)
                .build();
        dispatchNotificationSafe(request);
    }

    public void notifyRideStarted(Ride ride) {
        String dest = (ride.getDestinationLocationName() != null && !ride.getDestinationLocationName().isBlank())
                ? ride.getDestinationLocationName()
                : "destination";
        String idempotencyKey = "ride-" + ride.getId() + "-RIDE_STARTED-ROLE_PASSENGER-" + ride.getPassengerId();
        CreateNotificationRequest request = CreateNotificationRequest.builder()
                .recipientUserId(ride.getPassengerId())
                .recipientRole("ROLE_PASSENGER")
                .eventType("RIDE_STARTED")
                .title("Ride Started")
                .message("Your trip to " + dest + " has started.")
                .referenceRideId(ride.getId())
                .idempotencyKey(idempotencyKey)
                .build();
        dispatchNotificationSafe(request);
    }

    public void notifyRideCompleted(Ride ride) {
        BigDecimal finalFare = ride.getFinalFare() != null
                ? ride.getFinalFare()
                : (ride.getEstimatedFare() != null ? ride.getEstimatedFare() : BigDecimal.ZERO);
        String formattedFare = "₹" + finalFare.setScale(2, RoundingMode.HALF_UP).toPlainString();

        // 1. Notify Passenger
        String passengerKey = "ride-" + ride.getId() + "-RIDE_COMPLETED-ROLE_PASSENGER-" + ride.getPassengerId();
        CreateNotificationRequest passengerReq = CreateNotificationRequest.builder()
                .recipientUserId(ride.getPassengerId())
                .recipientRole("ROLE_PASSENGER")
                .eventType("RIDE_COMPLETED")
                .title("Ride Completed")
                .message("Your ride has completed. Final fare: " + formattedFare + ". Thank you for riding!")
                .amount(finalFare)
                .referenceRideId(ride.getId())
                .idempotencyKey(passengerKey)
                .build();
        dispatchNotificationSafe(passengerReq);

        // 2. Notify Driver
        if (ride.getDriverId() != null) {
            String driverKey = "ride-" + ride.getId() + "-RIDE_COMPLETED-ROLE_DRIVER-" + ride.getDriverId();
            CreateNotificationRequest driverReq = CreateNotificationRequest.builder()
                    .recipientUserId(ride.getDriverId())
                    .recipientRole("ROLE_DRIVER")
                    .eventType("RIDE_COMPLETED")
                    .title("Trip Completed")
                    .message("Ride #" + ride.getId() + " completed successfully. Fare recorded: " + formattedFare + ".")
                    .amount(finalFare)
                    .referenceRideId(ride.getId())
                    .idempotencyKey(driverKey)
                    .build();
            dispatchNotificationSafe(driverReq);
        }
    }

    public void notifyRideCancelled(Ride ride, String cancelledBy, String reason) {
        String effectiveReason = (reason != null && !reason.isBlank()) ? reason.trim() : "Cancelled by " + cancelledBy;

        // 1. Notify Passenger
        String passengerMessage;
        if ("DRIVER".equalsIgnoreCase(cancelledBy)) {
            passengerMessage = "Your ride #" + ride.getId() + " was cancelled by the driver: " + effectiveReason + ". Please request a new ride.";
        } else if ("PASSENGER".equalsIgnoreCase(cancelledBy)) {
            boolean hasCustom = !effectiveReason.equalsIgnoreCase("Cancelled by PASSENGER");
            passengerMessage = "Your ride #" + ride.getId() + " was successfully cancelled." + (hasCustom ? " Reason: " + effectiveReason : "");
        } else {
            passengerMessage = "Your ride #" + ride.getId() + " was cancelled by " + cancelledBy + ": " + effectiveReason;
        }

        String passengerKey = "ride-" + ride.getId() + "-RIDE_CANCELLED-ROLE_PASSENGER-" + ride.getPassengerId();
        CreateNotificationRequest passengerReq = CreateNotificationRequest.builder()
                .recipientUserId(ride.getPassengerId())
                .recipientRole("ROLE_PASSENGER")
                .eventType("RIDE_CANCELLED")
                .title("Ride Cancelled")
                .message(passengerMessage)
                .referenceRideId(ride.getId())
                .idempotencyKey(passengerKey)
                .build();
        dispatchNotificationSafe(passengerReq);

        // 2. Notify Driver (Only if a driver was assigned)
        if (ride.getDriverId() != null) {
            String driverMessage;
            if ("DRIVER".equalsIgnoreCase(cancelledBy)) {
                driverMessage = "You cancelled Ride #" + ride.getId() + ". Reason: " + effectiveReason + ".";
            } else {
                driverMessage = "Ride #" + ride.getId() + " was cancelled by the passenger. Reason: " + effectiveReason + ". You are now available for new rides.";
            }

            String driverKey = "ride-" + ride.getId() + "-RIDE_CANCELLED-ROLE_DRIVER-" + ride.getDriverId();
            CreateNotificationRequest driverReq = CreateNotificationRequest.builder()
                    .recipientUserId(ride.getDriverId())
                    .recipientRole("ROLE_DRIVER")
                    .eventType("RIDE_CANCELLED")
                    .title("Ride Cancelled")
                    .message(driverMessage)
                    .referenceRideId(ride.getId())
                    .idempotencyKey(driverKey)
                    .build();
            dispatchNotificationSafe(driverReq);
        }
    }

    public void dispatchNotificationSafe(CreateNotificationRequest request) {
        try {
            notificationClient.createNotification(request);
        } catch (Exception ex) {
            log.warn("Non-fatal: Failed to deliver in-app notification to user {} for ride {}: {}",
                    request.getRecipientUserId(), request.getReferenceRideId(), ex.getMessage());
            // Swallowed intentionally: notification failure must never roll back ride transaction
        }
    }
}
