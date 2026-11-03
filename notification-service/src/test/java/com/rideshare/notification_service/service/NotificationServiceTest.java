package com.rideshare.notification_service.service;

import com.rideshare.notification_service.dto.request.CreateNotificationRequest;
import com.rideshare.notification_service.dto.response.*;
import com.rideshare.notification_service.entity.Notification;
import com.rideshare.notification_service.entity.NotificationEventType;
import com.rideshare.notification_service.exception.ResourceNotFoundException;
import com.rideshare.notification_service.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationPersistenceHelper persistenceHelper;

    private Clock fixedClock;
    private NotificationServiceImpl notificationService;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2026-10-21T10:00:00Z"), ZoneId.of("UTC"));
        notificationService = new NotificationServiceImpl(notificationRepository, persistenceHelper, fixedClock);
    }

    @Test
    @DisplayName("Should create and persist notification with is_read=false and optional amount")
    void testCreateNotificationSuccess() {
        CreateNotificationRequest request = CreateNotificationRequest.builder()
                .recipientUserId(101L)
                .recipientRole("ROLE_PASSENGER")
                .eventType(NotificationEventType.RIDE_COMPLETED)
                .title("Ride Completed")
                .message("Final fare: ₹150.00")
                .referenceRideId(501L)
                .amount(new BigDecimal("150.00"))
                .idempotencyKey("ride-501-RIDE_COMPLETED-ROLE_PASSENGER-101")
                .build();

        when(notificationRepository.findByIdempotencyKey(request.getIdempotencyKey())).thenReturn(Optional.empty());

        Notification saved = Notification.builder()
                .id(1L)
                .recipientUserId(101L)
                .recipientRole("ROLE_PASSENGER")
                .eventType(NotificationEventType.RIDE_COMPLETED)
                .title(request.getTitle())
                .message(request.getMessage())
                .referenceRideId(501L)
                .amount(new BigDecimal("150.00"))
                .isRead(false)
                .idempotencyKey(request.getIdempotencyKey())
                .createdAt(LocalDateTime.now(fixedClock))
                .build();

        when(persistenceHelper.saveInNewTransaction(any(Notification.class))).thenReturn(saved);

        NotificationResponse response = notificationService.createNotification(request);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getRecipientUserId()).isEqualTo(101L);
        assertThat(response.getAmount()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(response.getIsRead()).isFalse();
        assertThat(response.getCreatedAt()).isEqualTo(LocalDateTime.now(fixedClock));
    }

    @Test
    @DisplayName("Should return existing notification without state change on concurrent unique collision")
    void testConcurrentDuplicateIngestionReturnsExistingWithoutStateChange() {
        CreateNotificationRequest request = CreateNotificationRequest.builder()
                .recipientUserId(101L)
                .recipientRole("ROLE_PASSENGER")
                .eventType(NotificationEventType.RIDE_ACCEPTED)
                .title("Driver Assigned")
                .message("Driver #201 has accepted your ride.")
                .referenceRideId(501L)
                .idempotencyKey("ride-501-RIDE_ACCEPTED-ROLE_PASSENGER-101")
                .build();

        // Initial check finds nothing (racing threads both see null)
        when(notificationRepository.findByIdempotencyKey(request.getIdempotencyKey()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(Notification.builder()
                        .id(42L)
                        .recipientUserId(101L)
                        .recipientRole("ROLE_PASSENGER")
                        .eventType(NotificationEventType.RIDE_ACCEPTED)
                        .title("Driver Assigned")
                        .message("Driver #201 has accepted your ride.")
                        .referenceRideId(501L)
                        .isRead(true) // Was already marked as read previously
                        .readAt(LocalDateTime.now(fixedClock).minusMinutes(5))
                        .idempotencyKey(request.getIdempotencyKey())
                        .createdAt(LocalDateTime.now(fixedClock).minusMinutes(10))
                        .build()));

        // Insertion throws duplicate key violation
        when(persistenceHelper.saveInNewTransaction(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry for key 'uk_notifications_idempotency_key'"));

        NotificationResponse response = notificationService.createNotification(request);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(42L);
        // Invariant: read status and readAt timestamp are completely preserved
        assertThat(response.getIsRead()).isTrue();
        assertThat(response.getReadAt()).isEqualTo(LocalDateTime.now(fixedClock).minusMinutes(5));
    }

    @Test
    @DisplayName("Should clamp requested page size to maximum 100")
    void testGetNotificationsPaginationClamping() {
        when(notificationRepository.findByRecipientUserIdOrderByCreatedAtDescIdDesc(eq(101L), any(PageRequest.class)))
                .thenAnswer(inv -> {
                    PageRequest pr = inv.getArgument(1);
                    assertThat(pr.getPageSize()).isEqualTo(100); // Proves clamped to 100
                    return new PageImpl<>(List.of(), pr, 0);
                });

        NotificationPageResponse pageResponse = notificationService.getUserNotifications(101L, false, 0, 500);

        assertThat(pageResponse.getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("Should return accurate unread notifications count")
    void testGetUnreadCount() {
        when(notificationRepository.countByRecipientUserIdAndIsReadFalse(101L)).thenReturn(7L);

        UnreadCountResponse response = notificationService.getUnreadCount(101L);

        assertThat(response.getUnreadCount()).isEqualTo(7L);
    }

    @Test
    @DisplayName("Should mark own notification as read with timestamp")
    void testMarkReadByOwnerSuccess() {
        Notification existing = Notification.builder()
                .id(10L)
                .recipientUserId(101L)
                .isRead(false)
                .build();

        when(notificationRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        MarkReadResponse response = notificationService.markAsRead(10L, 101L);

        assertThat(response.getId()).isEqualTo(10L);
        assertThat(response.getIsRead()).isTrue();
        assertThat(response.getReadAt()).isEqualTo(LocalDateTime.now(fixedClock));
    }

    @Test
    @DisplayName("Should be idempotent when marking already read notification")
    void testMarkReadIdempotent() {
        LocalDateTime originalReadAt = LocalDateTime.now(fixedClock).minusMinutes(2);
        Notification existing = Notification.builder()
                .id(10L)
                .recipientUserId(101L)
                .isRead(true)
                .readAt(originalReadAt)
                .build();

        when(notificationRepository.findById(10L)).thenReturn(Optional.of(existing));

        MarkReadResponse response = notificationService.markAsRead(10L, 101L);

        assertThat(response.getIsRead()).isTrue();
        assertThat(response.getReadAt()).isEqualTo(originalReadAt);
        verify(notificationRepository, never()).save(any(Notification.class));
    }

    @Test
    @DisplayName("Should throw 404 ResourceNotFoundException when user attempts to mark unowned notification")
    void testMarkReadByNonOwnerThrowsNotFound() {
        Notification unowned = Notification.builder()
                .id(10L)
                .recipientUserId(202L) // Belongs to User 202
                .isRead(false)
                .build();

        when(notificationRepository.findById(10L)).thenReturn(Optional.of(unowned));

        // User 101 tries to mark it as read
        assertThatThrownBy(() -> notificationService.markAsRead(10L, 101L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Notification not found with id: 10");
    }

    @Test
    @DisplayName("Should mark all unread notifications as read in bulk")
    void testMarkAllAsRead() {
        when(notificationRepository.markAllAsReadForUser(eq(101L), any(LocalDateTime.class))).thenReturn(5);

        MarkAllReadResponse response = notificationService.markAllAsRead(101L);

        assertThat(response.getMarkedCount()).isEqualTo(5);
    }
}
