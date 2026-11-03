package com.rideshare.ride_service.security;

import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Date;

@Service
@RequiredArgsConstructor
@Slf4j
public class InternalTokenService {

    private final JwtUtils jwtUtils;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    private String cachedToken;
    private long cachedTokenExpiryMillis = 0;

    public synchronized String getInternalToken() {
        long nowMillis = clock.millis();
        // Refresh token if within 2 minutes (120,000 ms) of expiry
        if (cachedToken == null || (cachedTokenExpiryMillis - nowMillis) < 120_000) {
            generateAndCacheToken(nowMillis);
        }
        return cachedToken;
    }

    private void generateAndCacheToken(long nowMillis) {
        long expiryMillis = nowMillis + jwtProperties.getInternalTokenExpirationMs();
        Date issuedAt = new Date(nowMillis);
        Date expiration = new Date(expiryMillis);

        this.cachedToken = Jwts.builder()
                .subject("0")
                .claim("email", "internal-ride-service@rideshare.internal")
                .claim("role", "ROLE_INTERNAL_SERVICE")
                .claim("service", "ride-service")
                .issuer("ride-sharing-platform")
                .issuedAt(issuedAt)
                .expiration(expiration)
                .signWith(jwtUtils.getSigningKey(), Jwts.SIG.HS256)
                .compact();

        this.cachedTokenExpiryMillis = expiryMillis;
        log.info("Generated new internal service JWT valid until {}", expiration);
    }
}
