package com.rideshare.gateway.ratelimit;

import com.rideshare.gateway.config.RateLimiterProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class InMemoryRateLimiter {

    private final RateLimiterProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, RateLimitWindow> clientWindows = new ConcurrentHashMap<>();

    public InMemoryRateLimiter(RateLimiterProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public RateLimitDecision tryAcquire(String clientIp) {
        if (!properties.isEnabled()) {
            return RateLimitDecision.allowed(properties.getMaxRequests());
        }

        long now = clock.millis();
        long windowDurationMs = properties.getWindowSeconds() * 1000L;
        int maxRequests = properties.getMaxRequests();
        int maxCapacity = properties.getMaxCapacity();

        // Check if map is saturated and client is new
        if (clientWindows.size() >= maxCapacity && !clientWindows.containsKey(clientIp)) {
            // Attempt immediate eviction of expired records
            cleanupExpiredWindowsInternal(now, windowDurationMs);

            if (clientWindows.size() >= maxCapacity && !clientWindows.containsKey(clientIp)) {
                log.warn("Rate limiter active client capacity ({}) exhausted. Failing closed for client IP: {}", maxCapacity, clientIp);
                return RateLimitDecision.capacityExhausted(properties.getWindowSeconds());
            }
        }

        RateLimitWindow window = clientWindows.compute(clientIp, (key, existing) -> {
            if (existing == null || now >= existing.getWindowStartEpochMs() + windowDurationMs) {
                return new RateLimitWindow(now);
            }
            existing.setLastActivityEpochMs(now);
            existing.getRequestCount().incrementAndGet();
            return existing;
        });

        int currentCount = window.getRequestCount().get();
        if (currentCount <= maxRequests) {
            return RateLimitDecision.allowed(maxRequests - currentCount);
        } else {
            long elapsedMs = now - window.getWindowStartEpochMs();
            long remainingMs = Math.max(1000L, windowDurationMs - elapsedMs);
            long retryAfterSeconds = (remainingMs + 999L) / 1000L;
            return RateLimitDecision.denied(retryAfterSeconds);
        }
    }

    public String extractClientIp(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return "unknown";
        }

        String directPeerIp = remoteAddress.getAddress().getHostAddress();
        List<String> trustedProxies = properties.getTrustedProxies();

        // If direct connection is not from a trusted proxy, ignore X-Forwarded-For to prevent spoofing
        if (!isTrustedProxy(directPeerIp, trustedProxies)) {
            return directPeerIp;
        }

        // Direct peer is trusted proxy, parse X-Forwarded-For header from right to left
        List<String> xffHeaders = exchange.getRequest().getHeaders().get("X-Forwarded-For");
        if (xffHeaders == null || xffHeaders.isEmpty()) {
            return directPeerIp;
        }

        String xffValue = String.join(",", xffHeaders);
        String[] ips = xffValue.split(",");

        for (int i = ips.length - 1; i >= 0; i--) {
            String candidateIp = ips[i].trim();
            if (!candidateIp.isEmpty() && !isTrustedProxy(candidateIp, trustedProxies)) {
                return candidateIp;
            }
        }

        return directPeerIp;
    }

    private boolean isTrustedProxy(String ip, List<String> trustedProxies) {
        if (trustedProxies == null || trustedProxies.isEmpty()) {
            return false;
        }
        for (String trusted : trustedProxies) {
            if (ip.equalsIgnoreCase(trusted.trim())) {
                return true;
            }
        }
        return false;
    }

    @Scheduled(fixedRate = 60000)
    public void scheduledCleanup() {
        long now = clock.millis();
        long windowDurationMs = properties.getWindowSeconds() * 1000L;
        cleanupExpiredWindowsInternal(now, windowDurationMs);
    }

    private void cleanupExpiredWindowsInternal(long now, long windowDurationMs) {
        // Crucial invariant: removeIf only purges windows where now >= windowStart + duration
        // Active windows are NEVER removed or reset.
        clientWindows.entrySet().removeIf(entry -> now >= entry.getValue().getWindowStartEpochMs() + windowDurationMs);
    }

    public int getActiveClientCount() {
        return clientWindows.size();
    }

    public void resetAll() {
        clientWindows.clear();
    }
}
