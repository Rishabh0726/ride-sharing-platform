package com.rideshare.notification_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rideshare.notification_service.config.ClockConfig;
import com.rideshare.notification_service.config.JacksonConfig;
import com.rideshare.notification_service.dto.request.CreateNotificationRequest;
import com.rideshare.notification_service.dto.response.*;
import com.rideshare.notification_service.entity.NotificationEventType;
import com.rideshare.notification_service.security.*;
import com.rideshare.notification_service.service.NotificationService;
import com.rideshare.notification_service.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, AuthEntryPointJwt.class, JwtUtils.class, JwtProperties.class, ClockConfig.class, JacksonConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
public class NotificationControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private NotificationService notificationService;

    private UserPrincipal createPrincipal(Long userId, String role) {
        return new UserPrincipal(
                userId,
                role.toLowerCase() + "@rideshare.test",
                "",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(role)),
                true
        );
    }

    // ==========================================
    // 1. Ingestion Endpoint Security (/api/v1/notifications)
    // ==========================================

    @Test
    @DisplayName("POST /api/v1/notifications with ROLE_INTERNAL_SERVICE should succeed (201 Created)")
    void testCreateNotificationInternalServicePermitted() throws Exception {
        CreateNotificationRequest request = CreateNotificationRequest.builder()
                .recipientUserId(101L)
                .recipientRole("ROLE_PASSENGER")
                .eventType(NotificationEventType.RIDE_REQUESTED)
                .title("Ride Requested")
                .message("Your ride request has been submitted.")
                .idempotencyKey("test-idemp-1")
                .build();

        NotificationResponse response = NotificationResponse.builder()
                .id(1L)
                .recipientUserId(101L)
                .recipientRole("ROLE_PASSENGER")
                .eventType("RIDE_REQUESTED")
                .title("Ride Requested")
                .message("Your ride request has been submitted.")
                .isRead(false)
                .createdAt(LocalDateTime.now())
                .build();

        when(notificationService.createNotification(any(CreateNotificationRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/notifications")
                        .with(user(createPrincipal(0L, "ROLE_INTERNAL_SERVICE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L));
    }

    @Test
    @DisplayName("POST /api/v1/notifications with ROLE_PASSENGER should be rejected (403 Forbidden)")
    void testCreateNotificationPassengerForbidden() throws Exception {
        CreateNotificationRequest request = CreateNotificationRequest.builder()
                .recipientUserId(101L)
                .recipientRole("ROLE_PASSENGER")
                .eventType(NotificationEventType.RIDE_REQUESTED)
                .title("Ride Requested")
                .message("Message")
                .idempotencyKey("test-idemp-2")
                .build();

        mockMvc.perform(post("/api/v1/notifications")
                        .with(user(createPrincipal(101L, "ROLE_PASSENGER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/notifications with ROLE_DRIVER should be rejected (403 Forbidden)")
    void testCreateNotificationDriverForbidden() throws Exception {
        CreateNotificationRequest request = CreateNotificationRequest.builder()
                .recipientUserId(201L)
                .recipientRole("ROLE_DRIVER")
                .eventType(NotificationEventType.DRIVER_ASSIGNED)
                .title("Driver Assigned")
                .message("Message")
                .idempotencyKey("test-idemp-3")
                .build();

        mockMvc.perform(post("/api/v1/notifications")
                        .with(user(createPrincipal(201L, "ROLE_DRIVER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/notifications unauthenticated should be rejected (401 Unauthorized)")
    void testCreateNotificationUnauthenticated() throws Exception {
        CreateNotificationRequest request = CreateNotificationRequest.builder()
                .recipientUserId(101L)
                .recipientRole("ROLE_PASSENGER")
                .eventType(NotificationEventType.RIDE_REQUESTED)
                .title("Ride Requested")
                .message("Message")
                .idempotencyKey("test-idemp-4")
                .build();

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    // ==========================================
    // 2. End-User Inbox Endpoint Security (GET /api/v1/notifications)
    // ==========================================

    @Test
    @DisplayName("GET /api/v1/notifications with ROLE_PASSENGER should succeed (200 OK)")
    void testGetNotificationsPassengerPermitted() throws Exception {
        when(notificationService.getUserNotifications(eq(101L), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(NotificationPageResponse.builder().content(List.of()).pageSize(20).build());

        mockMvc.perform(get("/api/v1/notifications")
                        .with(user(createPrincipal(101L, "ROLE_PASSENGER"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/notifications with ROLE_INTERNAL_SERVICE must be rejected (403 Forbidden)")
    void testGetNotificationsInternalServiceForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")
                        .with(user(createPrincipal(0L, "ROLE_INTERNAL_SERVICE"))))
                .andExpect(status().isForbidden());
    }

    // ==========================================
    // 3. Unread Count Endpoint Security (GET /api/v1/notifications/unread-count)
    // ==========================================

    @Test
    @DisplayName("GET /api/v1/notifications/unread-count with ROLE_DRIVER should succeed (200 OK)")
    void testGetUnreadCountDriverPermitted() throws Exception {
        when(notificationService.getUnreadCount(eq(201L)))
                .thenReturn(UnreadCountResponse.builder().unreadCount(3L).build());

        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .with(user(createPrincipal(201L, "ROLE_DRIVER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(3L));
    }

    @Test
    @DisplayName("GET /api/v1/notifications/unread-count with ROLE_INTERNAL_SERVICE must be rejected (403 Forbidden)")
    void testGetUnreadCountInternalServiceForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .with(user(createPrincipal(0L, "ROLE_INTERNAL_SERVICE"))))
                .andExpect(status().isForbidden());
    }

    // ==========================================
    // 4. Mark Read Endpoint Security (PATCH /api/v1/notifications/{id}/read)
    // ==========================================

    @Test
    @DisplayName("PATCH /api/v1/notifications/{id}/read with ROLE_PASSENGER should succeed (200 OK)")
    void testMarkReadPassengerPermitted() throws Exception {
        when(notificationService.markAsRead(eq(1L), eq(101L)))
                .thenReturn(MarkReadResponse.builder().id(1L).isRead(true).readAt(LocalDateTime.now()).build());

        mockMvc.perform(patch("/api/v1/notifications/1/read")
                        .with(user(createPrincipal(101L, "ROLE_PASSENGER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isRead").value(true));
    }

    @Test
    @DisplayName("PATCH /api/v1/notifications/{id}/read with ROLE_INTERNAL_SERVICE must be rejected (403 Forbidden)")
    void testMarkReadInternalServiceForbidden() throws Exception {
        mockMvc.perform(patch("/api/v1/notifications/1/read")
                        .with(user(createPrincipal(0L, "ROLE_INTERNAL_SERVICE"))))
                .andExpect(status().isForbidden());
    }

    // ==========================================
    // 5. Mark All Read Endpoint Security (PATCH /api/v1/notifications/read-all)
    // ==========================================

    @Test
    @DisplayName("PATCH /api/v1/notifications/read-all with ROLE_PASSENGER should succeed (200 OK)")
    void testMarkAllReadPassengerPermitted() throws Exception {
        when(notificationService.markAllAsRead(eq(101L)))
                .thenReturn(MarkAllReadResponse.builder().markedCount(4).build());

        mockMvc.perform(patch("/api/v1/notifications/read-all")
                        .with(user(createPrincipal(101L, "ROLE_PASSENGER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markedCount").value(4));
    }

    @Test
    @DisplayName("PATCH /api/v1/notifications/read-all with ROLE_INTERNAL_SERVICE must be rejected (403 Forbidden)")
    void testMarkAllReadInternalServiceForbidden() throws Exception {
        mockMvc.perform(patch("/api/v1/notifications/read-all")
                        .with(user(createPrincipal(0L, "ROLE_INTERNAL_SERVICE"))))
                .andExpect(status().isForbidden());
    }
}
