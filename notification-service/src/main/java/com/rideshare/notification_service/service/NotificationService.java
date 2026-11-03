package com.rideshare.notification_service.service;

import com.rideshare.notification_service.dto.request.CreateNotificationRequest;
import com.rideshare.notification_service.dto.response.*;

public interface NotificationService {

    NotificationResponse createNotification(CreateNotificationRequest request);

    NotificationPageResponse getUserNotifications(Long userId, boolean unreadOnly, int page, int size);

    UnreadCountResponse getUnreadCount(Long userId);

    MarkReadResponse markAsRead(Long notificationId, Long userId);

    MarkAllReadResponse markAllAsRead(Long userId);
}
