package com.rideshare.notification_service.service;

import com.rideshare.notification_service.entity.Notification;
import com.rideshare.notification_service.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class NotificationPersistenceHelper {

    private final NotificationRepository notificationRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification saveInNewTransaction(Notification notification) {
        return notificationRepository.saveAndFlush(notification);
    }
}
