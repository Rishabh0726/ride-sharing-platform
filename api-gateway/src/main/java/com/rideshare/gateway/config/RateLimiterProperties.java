package com.rideshare.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "gateway.rate-limiter")
@Getter
@Setter
public class RateLimiterProperties {

    private boolean enabled = true;

    /**
     * Maximum allowed requests within the rate limiting window per IP.
     */
    private int maxRequests = 20;

    /**
     * Window duration in seconds.
     */
    private long windowSeconds = 60L;

    /**
     * Maximum capacity of distinct active clients tracked in memory before capacity exhaustion.
     */
    private int maxCapacity = 10000;

    /**
     * List of trusted reverse proxy IP addresses allowed to supply X-Forwarded-For headers.
     */
    private List<String> trustedProxies = new ArrayList<>(List.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1"));
}
