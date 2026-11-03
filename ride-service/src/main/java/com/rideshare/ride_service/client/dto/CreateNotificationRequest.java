package com.rideshare.ride_service.client.dto;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateNotificationRequest {
    private Long recipientUserId;
    private String recipientRole;
    private String eventType;
    private String title;
    private String message;
    private Long referenceRideId;
    private BigDecimal amount;
    private String idempotencyKey;
}
