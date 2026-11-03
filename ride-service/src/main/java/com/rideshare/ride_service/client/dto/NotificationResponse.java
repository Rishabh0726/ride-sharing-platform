package com.rideshare.ride_service.client.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationResponse {
    private Long id;
    private Long recipientUserId;
    private String recipientRole;
    private String eventType;
    private String title;
    private String message;
    private Long referenceRideId;
    private BigDecimal amount;
    private Boolean isRead;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
