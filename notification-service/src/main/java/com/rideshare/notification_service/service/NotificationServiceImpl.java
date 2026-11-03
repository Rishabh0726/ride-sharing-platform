package com.rideshare.notification_service.service;

import com.rideshare.notification_service.dto.request.CreateNotificationRequest;
import com.rideshare.notification_service.dto.response.*;
import com.rideshare.notification_service.entity.Notification;
import com.rideshare.notification_service.exception.ConflictException;
import com.rideshare.notification_service.exception.ResourceNotFoundException;
import com.rideshare.notification_service.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationPersistenceHelper persistenceHelper;
    private final Clock clock;

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    @Override
    public NotificationResponse createNotification(CreateNotificationRequest request) {
        log.info("Creating notification for recipient: {} ({}), eventType: {}, idempotencyKey: {}",
                request.getRecipientUserId(), request.getRecipientRole(), request.getEventType(), request.getIdempotencyKey());

        // 1. Fast read check
        Optional<Notification> existingOpt = notificationRepository.findByIdempotencyKey(request.getIdempotencyKey());
        if (existingOpt.isPresent()) {
            log.info("Returning existing notification for duplicate idempotencyKey: {}", request.getIdempotencyKey());
            return mapToResponse(existingOpt.get());
        }

        // 2. Build entity
        LocalDateTime now = LocalDateTime.now(clock);
        Notification entity = Notification.builder()
                .recipientUserId(request.getRecipientUserId())
                .recipientRole(request.getRecipientRole())
                .eventType(request.getEventType())
                .title(request.getTitle())
                .message(request.getMessage())
                .referenceRideId(request.getReferenceRideId())
                .amount(request.getAmount())
                .isRead(false)
                .readAt(null)
                .idempotencyKey(request.getIdempotencyKey())
                .createdAt(now)
                .build();

        // 3. Isolated insert transaction to handle concurrent collisions safely
        try {
            Notification saved = persistenceHelper.saveInNewTransaction(entity);
            return mapToResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            log.info("Concurrent insert conflict on idempotency key: {}. Reading winning record.", request.getIdempotencyKey());
            Notification winningRecord = notificationRepository.findByIdempotencyKey(request.getIdempotencyKey())
                    .orElseThrow(() -> new ConflictException("Concurrent conflict: notification could not be resolved"));
            return mapToResponse(winningRecord);
        }
    }

    @Override
    public NotificationPageResponse getUserNotifications(Long userId, boolean unreadOnly, int page, int size) {
        int validatedPage = Math.max(page, 0);
        int validatedSize = (size <= 0) ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        PageRequest pageRequest = PageRequest.of(validatedPage, validatedSize);

        Page<Notification> notificationPage;
        if (unreadOnly) {
            notificationPage = notificationRepository.findByRecipientUserIdAndIsReadFalseOrderByCreatedAtDescIdDesc(userId, pageRequest);
        } else {
            notificationPage = notificationRepository.findByRecipientUserIdOrderByCreatedAtDescIdDesc(userId, pageRequest);
        }

        List<NotificationResponse> content = notificationPage.getContent().stream()
                .map(this::mapToResponse)
                .toList();

        return NotificationPageResponse.builder()
                .content(content)
                .pageNumber(notificationPage.getNumber())
                .pageSize(notificationPage.getSize())
                .totalElements(notificationPage.getTotalElements())
                .totalPages(notificationPage.getTotalPages())
                .isLast(notificationPage.isLast())
                .build();
    }

    @Override
    public UnreadCountResponse getUnreadCount(Long userId) {
        long count = notificationRepository.countByRecipientUserIdAndIsReadFalse(userId);
        return UnreadCountResponse.builder()
                .unreadCount(count)
                .build();
    }

    @Override
    @Transactional
    public MarkReadResponse markAsRead(Long notificationId, Long userId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found with id: " + notificationId));

        // Strict ownership check: return 404 to prevent ID enumeration across users
        if (!notification.getRecipientUserId().equals(userId)) {
            log.warn("User {} attempted to mark unowned notification {} as read", userId, notificationId);
            throw new ResourceNotFoundException("Notification not found with id: " + notificationId);
        }

        if (Boolean.TRUE.equals(notification.getIsRead())) {
            return MarkReadResponse.builder()
                    .id(notification.getId())
                    .isRead(true)
                    .readAt(notification.getReadAt())
                    .build();
        }

        LocalDateTime now = LocalDateTime.now(clock);
        notification.setIsRead(true);
        notification.setReadAt(now);
        Notification updated = notificationRepository.save(notification);

        return MarkReadResponse.builder()
                .id(updated.getId())
                .isRead(true)
                .readAt(updated.getReadAt())
                .build();
    }

    @Override
    @Transactional
    public MarkAllReadResponse markAllAsRead(Long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        int updated = notificationRepository.markAllAsReadForUser(userId, now);
        return MarkAllReadResponse.builder()
                .markedCount(updated)
                .build();
    }

    private NotificationResponse mapToResponse(Notification n) {
        return NotificationResponse.builder()
                .id(n.getId())
                .recipientUserId(n.getRecipientUserId())
                .recipientRole(n.getRecipientRole())
                .eventType(n.getEventType().name())
                .title(n.getTitle())
                .message(n.getMessage())
                .referenceRideId(n.getReferenceRideId())
                .amount(n.getAmount())
                .isRead(n.getIsRead())
                .readAt(n.getReadAt())
                .createdAt(n.getCreatedAt())
                .build();
    }
}
