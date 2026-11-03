package com.rideshare.notification_service.controller;

import com.rideshare.notification_service.dto.request.CreateNotificationRequest;
import com.rideshare.notification_service.dto.response.*;
import com.rideshare.notification_service.security.UserPrincipal;
import com.rideshare.notification_service.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Notification Operations", description = "Endpoints for managing in-app notifications and read tracking")
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_INTERNAL_SERVICE')")
    @Operation(summary = "Ingest notification (Internal)", description = "Restricted to internal microservices (ROLE_INTERNAL_SERVICE)")
    public ResponseEntity<NotificationResponse> createNotification(
            @Valid @RequestBody CreateNotificationRequest request
    ) {
        NotificationResponse response = notificationService.createNotification(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_PASSENGER', 'ROLE_DRIVER', 'ROLE_ADMIN')")
    @Operation(summary = "Get user notifications", description = "Returns paginated notifications for the authenticated user")
    public ResponseEntity<NotificationPageResponse> getUserNotifications(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(value = "unreadOnly", defaultValue = "false") boolean unreadOnly,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        NotificationPageResponse response = notificationService.getUserNotifications(principal.getId(), unreadOnly, page, size);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/unread-count")
    @PreAuthorize("hasAnyAuthority('ROLE_PASSENGER', 'ROLE_DRIVER', 'ROLE_ADMIN')")
    @Operation(summary = "Get unread notifications count", description = "Returns the number of unread notifications for the authenticated user")
    public ResponseEntity<UnreadCountResponse> getUnreadCount(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        UnreadCountResponse response = notificationService.getUnreadCount(principal.getId());
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/read")
    @PreAuthorize("hasAnyAuthority('ROLE_PASSENGER', 'ROLE_DRIVER', 'ROLE_ADMIN')")
    @Operation(summary = "Mark notification as read", description = "Marks a specific notification as read if owned by the authenticated user")
    public ResponseEntity<MarkReadResponse> markAsRead(
            @PathVariable("id") Long id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        MarkReadResponse response = notificationService.markAsRead(id, principal.getId());
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/read-all")
    @PreAuthorize("hasAnyAuthority('ROLE_PASSENGER', 'ROLE_DRIVER', 'ROLE_ADMIN')")
    @Operation(summary = "Mark all notifications as read", description = "Marks all unread notifications as read for the authenticated user")
    public ResponseEntity<MarkAllReadResponse> markAllAsRead(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        MarkAllReadResponse response = notificationService.markAllAsRead(principal.getId());
        return ResponseEntity.ok(response);
    }
}
