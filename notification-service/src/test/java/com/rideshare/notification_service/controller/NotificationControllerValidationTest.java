package com.rideshare.notification_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rideshare.notification_service.config.ClockConfig;
import com.rideshare.notification_service.config.JacksonConfig;
import com.rideshare.notification_service.dto.request.CreateNotificationRequest;
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

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, AuthEntryPointJwt.class, JwtUtils.class, JwtProperties.class, ClockConfig.class, JacksonConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
public class NotificationControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private NotificationService notificationService;

    private UserPrincipal internalPrincipal() {
        return new UserPrincipal(
                0L,
                "internal@rideshare.test",
                "",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE")),
                true
        );
    }

    @Test
    @DisplayName("Should return 400 Bad Request with invalidParams when required fields are missing")
    void testMissingRequiredFields() throws Exception {
        CreateNotificationRequest request = CreateNotificationRequest.builder()
                .recipientUserId(null) // missing
                .recipientRole(null)   // missing
                .eventType(null)       // missing
                .title("")             // blank
                .message("   ")        // blank
                .idempotencyKey(null)  // missing
                .build();

        mockMvc.perform(post("/api/v1/notifications")
                        .with(user(internalPrincipal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://api.rideshare.com/errors/validation-failed"))
                .andExpect(jsonPath("$.invalidParams.recipientUserId").exists())
                .andExpect(jsonPath("$.invalidParams.recipientRole").exists())
                .andExpect(jsonPath("$.invalidParams.eventType").exists())
                .andExpect(jsonPath("$.invalidParams.title").exists())
                .andExpect(jsonPath("$.invalidParams.message").exists())
                .andExpect(jsonPath("$.invalidParams.idempotencyKey").exists());
    }

    @Test
    @DisplayName("Should return 400 Bad Request when idempotencyKey is blank")
    void testBlankIdempotencyKey() throws Exception {
        CreateNotificationRequest request = CreateNotificationRequest.builder()
                .recipientUserId(101L)
                .recipientRole("ROLE_PASSENGER")
                .eventType(NotificationEventType.RIDE_REQUESTED)
                .title("Valid Title")
                .message("Valid Message")
                .idempotencyKey("   ") // blank
                .build();

        mockMvc.perform(post("/api/v1/notifications")
                        .with(user(internalPrincipal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.invalidParams.idempotencyKey").exists());
    }
}
