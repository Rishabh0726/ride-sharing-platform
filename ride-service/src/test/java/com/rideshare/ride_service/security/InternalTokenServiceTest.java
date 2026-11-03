package com.rideshare.ride_service.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class InternalTokenServiceTest {

    private JwtProperties jwtProperties;
    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret("TestSecretKeyForInternalTokenServiceTestingMustBeAtLeast32Bytes!");
        jwtProperties.setInternalTokenExpirationMs(900_000); // 15 minutes
        jwtUtils = new JwtUtils(jwtProperties);
    }

    @Test
    @DisplayName("Generated internal token contains ROLE_INTERNAL_SERVICE and service identity claims")
    void testGenerateInternalTokenClaims() {
        Clock fixedClock = Clock.fixed(Instant.now(), ZoneId.of("UTC"));
        InternalTokenService tokenService = new InternalTokenService(jwtUtils, jwtProperties, fixedClock);

        String token = tokenService.getInternalToken();
        assertThat(token).isNotBlank();

        assertThat(jwtUtils.validateToken(token)).isTrue();
        Claims claims = jwtUtils.getClaims(token);
        assertThat(claims.getSubject()).isEqualTo("0");
        assertThat(claims.get("role", String.class)).isEqualTo("ROLE_INTERNAL_SERVICE");
        assertThat(claims.get("service", String.class)).isEqualTo("ride-service");
        assertThat(claims.getIssuer()).isEqualTo("ride-sharing-platform");
    }

    @Test
    @DisplayName("Internal token is cached in memory across subsequent calls within validity window")
    void testTokenCaching() {
        Clock fixedClock = Clock.fixed(Instant.now(), ZoneId.of("UTC"));
        InternalTokenService tokenService = new InternalTokenService(jwtUtils, jwtProperties, fixedClock);

        String token1 = tokenService.getInternalToken();
        String token2 = tokenService.getInternalToken();

        assertThat(token1).isSameAs(token2);
    }

    @Test
    @DisplayName("Internal token is proactively refreshed when remaining validity is under 2 minutes")
    void testTokenProactiveRefresh() {
        Instant startTime = Instant.now();
        MutableTestClock clock = new MutableTestClock(startTime, ZoneId.of("UTC"));
        InternalTokenService tokenService = new InternalTokenService(jwtUtils, jwtProperties, clock);

        String initialToken = tokenService.getInternalToken();

        // Advance clock by 13 minutes and 30 seconds (15m expiration - 1m 30s remaining < 2m threshold)
        clock.setInstant(startTime.plusSeconds(810));

        String refreshedToken = tokenService.getInternalToken();
        assertThat(refreshedToken).isNotEqualTo(initialToken);
        assertThat(jwtUtils.validateToken(refreshedToken)).isTrue();
    }

    private static class MutableTestClock extends Clock {
        private Instant currentInstant;
        private final ZoneId zone;

        public MutableTestClock(Instant currentInstant, ZoneId zone) {
            this.currentInstant = currentInstant;
            this.zone = zone;
        }

        public void setInstant(Instant instant) {
            this.currentInstant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableTestClock(currentInstant, zone);
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }
    }
}
