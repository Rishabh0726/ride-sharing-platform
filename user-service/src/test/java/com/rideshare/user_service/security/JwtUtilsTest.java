package com.rideshare.user_service.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilsTest {

    private JwtProperties jwtProperties;
    private JwtUtils jwtUtils;

    private static final String VALID_SECRET = "very_secure_test_signing_key_at_least_32_bytes_long!";
    private static final long EXPIRATION_MS = 3600000L; // 1 hour

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret(VALID_SECRET);
        jwtProperties.setExpirationMs(EXPIRATION_MS);
        jwtProperties.validateSecret();

        jwtUtils = new JwtUtils(jwtProperties);
    }

    @Test
    @DisplayName("Should successfully generate a valid token and extract claims")
    void shouldGenerateAndExtractClaims() {
        Long userId = 101L;
        String email = "rider@example.com";
        String role = "ROLE_PASSENGER";

        String token = jwtUtils.generateToken(userId, email, role);

        assertThat(token).isNotBlank();
        assertThat(jwtUtils.validateToken(token)).isTrue();
        assertThat(jwtUtils.getUserIdFromToken(token)).isEqualTo(userId);
        assertThat(jwtUtils.getEmailFromToken(token)).isEqualTo(email);
        assertThat(jwtUtils.getRoleFromToken(token)).isEqualTo(role);
    }

    @Test
    @DisplayName("Should reject tampered token signature")
    void shouldRejectTamperedToken() {
        String token = jwtUtils.generateToken(101L, "rider@example.com", "ROLE_PASSENGER");
        String tamperedToken = token.substring(0, token.length() - 5) + "abcde";

        assertThat(jwtUtils.validateToken(tamperedToken)).isFalse();
    }

    @Test
    @DisplayName("Should reject expired token")
    void shouldRejectExpiredToken() {
        JwtProperties expiredProps = new JwtProperties();
        expiredProps.setSecret(VALID_SECRET);
        expiredProps.setExpirationMs(-1000L); // Negative expiration
        expiredProps.validateSecret();

        JwtUtils expiredJwtUtils = new JwtUtils(expiredProps);
        String expiredToken = expiredJwtUtils.generateToken(101L, "rider@example.com", "ROLE_PASSENGER");

        assertThat(jwtUtils.validateToken(expiredToken)).isFalse();
    }

    @Test
    @DisplayName("Should fail startup when secret is missing or empty")
    void shouldFailWhenSecretIsMissing() {
        JwtProperties missingProps = new JwtProperties();
        missingProps.setSecret(null);

        assertThatThrownBy(missingProps::validateSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT secret is missing");

        missingProps.setSecret("   ");
        assertThatThrownBy(missingProps::validateSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT secret is missing");
    }

    @Test
    @DisplayName("Should fail startup when secret is less than 32 bytes (256 bits)")
    void shouldFailWhenSecretIsTooShort() {
        JwtProperties shortProps = new JwtProperties();
        shortProps.setSecret("short_key_under_32_bytes!");

        assertThatThrownBy(shortProps::validateSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HMAC-SHA256 requires at least 256 bits (32 bytes)");
    }
}
