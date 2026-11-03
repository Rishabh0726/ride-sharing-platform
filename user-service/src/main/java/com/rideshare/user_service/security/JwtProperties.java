package com.rideshare.user_service.security;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@ConfigurationProperties(prefix = "application.jwt")
@Getter
@Setter
public class JwtProperties {

    private String secret;
    private long expirationMs = 86400000L; // 24 hours default

    @PostConstruct
    public void validateSecret() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "CRITICAL SECURITY CONFIGURATION FAILURE: JWT secret is missing! " +
                "The environment variable 'JWT_SECRET' must be provided."
            );
        }

        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                "CRITICAL SECURITY CONFIGURATION FAILURE: JWT signing secret is insecure! " +
                "HMAC-SHA256 requires at least 256 bits (32 bytes). Found only " + keyBytes.length + " bytes."
            );
        }
    }
}
