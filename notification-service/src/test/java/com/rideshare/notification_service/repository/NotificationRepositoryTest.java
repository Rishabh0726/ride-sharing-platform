package com.rideshare.notification_service.repository;

import com.rideshare.notification_service.entity.Notification;
import com.rideshare.notification_service.entity.NotificationEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
public class NotificationRepositoryTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    @DisplayName("Should enforce unique constraint on idempotency_key in H2")
    void testUniqueIdempotencyKeyConstraint() {
        LocalDateTime now = LocalDateTime.now();
        Notification n1 = Notification.builder()
                .recipientUserId(101L)
                .recipientRole("ROLE_PASSENGER")
                .eventType(NotificationEventType.RIDE_REQUESTED)
                .title("Ride Requested")
                .message("Your ride request has been submitted.")
                .idempotencyKey("idemp-unique-123")
                .isRead(false)
                .createdAt(now)
                .build();
        notificationRepository.saveAndFlush(n1);

        Notification n2 = Notification.builder()
                .recipientUserId(101L)
                .recipientRole("ROLE_PASSENGER")
                .eventType(NotificationEventType.RIDE_REQUESTED)
                .title("Ride Requested")
                .message("Duplicate ride request.")
                .idempotencyKey("idemp-unique-123")
                .isRead(false)
                .createdAt(now)
                .build();

        assertThatThrownBy(() -> notificationRepository.saveAndFlush(n2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Should query notifications with deterministic ordering when created_at ties")
    void testDeterministicOrderingOnCreatedAtTie() {
        LocalDateTime tiedTime = LocalDateTime.of(2026, 10, 21, 10, 0, 0);

        Notification n1 = Notification.builder()
                .recipientUserId(101L)
                .recipientRole("ROLE_PASSENGER")
                .eventType(NotificationEventType.RIDE_REQUESTED)
                .title("First Notification")
                .message("Message 1")
                .idempotencyKey("key-1")
                .isRead(false)
                .createdAt(tiedTime)
                .build();
        Notification n2 = Notification.builder()
                .recipientUserId(101L)
                .recipientRole("ROLE_PASSENGER")
                .eventType(NotificationEventType.RIDE_ACCEPTED)
                .title("Second Notification")
                .message("Message 2")
                .idempotencyKey("key-2")
                .isRead(false)
                .createdAt(tiedTime)
                .build();

        notificationRepository.save(n1);
        notificationRepository.save(n2);
        notificationRepository.flush();

        Page<Notification> page = notificationRepository.findByRecipientUserIdOrderByCreatedAtDescIdDesc(
                101L, PageRequest.of(0, 10)
        );

        assertThat(page.getContent()).hasSize(2);
        // Secondary sort on id DESC guarantees n2 comes before n1 because n2 was persisted second
        assertThat(page.getContent().get(0).getId()).isGreaterThan(page.getContent().get(1).getId());
        assertThat(page.getContent().get(0).getTitle()).isEqualTo("Second Notification");
    }

    @Test
    @DisplayName("Should persist and retrieve optional BigDecimal amount field")
    void testPersistAndRetrieveAmountField() {
        Notification n = Notification.builder()
                .recipientUserId(101L)
                .recipientRole("ROLE_PASSENGER")
                .eventType(NotificationEventType.RIDE_COMPLETED)
                .title("Ride Completed")
                .message("Final fare: ₹146.30")
                .amount(new BigDecimal("146.30"))
                .idempotencyKey("key-completed-1")
                .isRead(false)
                .createdAt(LocalDateTime.now())
                .build();

        Notification saved = notificationRepository.saveAndFlush(n);
        Notification retrieved = notificationRepository.findById(saved.getId()).orElseThrow();

        assertThat(retrieved.getAmount()).isEqualByComparingTo(new BigDecimal("146.30"));
    }
}
