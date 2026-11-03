package com.rideshare.ride_service.client;

import com.rideshare.ride_service.client.dto.CreateNotificationRequest;
import com.rideshare.ride_service.client.dto.NotificationResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "notification-service", url = "${notification-service.url:http://localhost:8084}")
public interface NotificationClient {

    @PostMapping("/api/v1/notifications")
    ResponseEntity<NotificationResponse> createNotification(@RequestBody CreateNotificationRequest request);
}
