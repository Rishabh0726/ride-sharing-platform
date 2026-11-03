package com.rideshare.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "jwt")
@Getter
@Setter
public class JwtProperties {

    /**
     * Secret key for validating HS256 HMAC JWT signatures (minimum 32 bytes).
     */
    private String secret;

    /**
     * Default expiration time in milliseconds.
     */
    private long expirationMs = 86400000L;
}
