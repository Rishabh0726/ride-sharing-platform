package com.rideshare.gateway.ratelimit;

import com.rideshare.gateway.config.RateLimiterProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRateLimiterTest {

    private MutableClock clock;
    private RateLimiterProperties properties;
    private InMemoryRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-21T12:00:00Z"));
        properties = new RateLimiterProperties();
        properties.setEnabled(true);
        properties.setMaxRequests(20);
        properties.setWindowSeconds(60L);
        properties.setMaxCapacity(100);
        properties.setTrustedProxies(List.of("127.0.0.1", "10.0.0.1"));

        rateLimiter = new InMemoryRateLimiter(properties, clock);
    }

    @Test
    @DisplayName("20 requests within 60 seconds are allowed; 21st request is denied with 429 Retry-After")
    void test20RequestsAllowed_21stDenied() {
        String clientIp = "192.168.1.100";

        for (int i = 1; i <= 20; i++) {
            RateLimitDecision decision = rateLimiter.tryAcquire(clientIp);
            assertThat(decision.isAllowed()).isTrue();
            assertThat(decision.getRemainingRequests()).isEqualTo(20 - i);
        }

        // 21st request should be rejected
        RateLimitDecision deniedDecision = rateLimiter.tryAcquire(clientIp);
        assertThat(deniedDecision.isAllowed()).isFalse();
        assertThat(deniedDecision.getRetryAfterSeconds()).isGreaterThan(0L);
        assertThat(deniedDecision.getRetryAfterSeconds()).isLessThanOrEqualTo(60L);
    }

    @Test
    @DisplayName("Window reset: after window duration (60s) elapses, requests are allowed again")
    void testWindowReset_AllowedAfterWindowElapses() {
        String clientIp = "192.168.1.101";

        for (int i = 1; i <= 20; i++) {
            rateLimiter.tryAcquire(clientIp);
        }
        assertThat(rateLimiter.tryAcquire(clientIp).isAllowed()).isFalse();

        // Advance clock by 61 seconds
        clock.advanceSeconds(61);

        RateLimitDecision freshDecision = rateLimiter.tryAcquire(clientIp);
        assertThat(freshDecision.isAllowed()).isTrue();
        assertThat(freshDecision.getRemainingRequests()).isEqualTo(19);
    }

    @Test
    @DisplayName("Different IP addresses maintain independent rate limit counters")
    void testDifferentIps_HaveIndependentCounters() {
        String ip1 = "10.1.1.1";
        String ip2 = "10.1.1.2";

        for (int i = 1; i <= 20; i++) {
            rateLimiter.tryAcquire(ip1);
        }
        assertThat(rateLimiter.tryAcquire(ip1).isAllowed()).isFalse();

        // ip2 should still have full quota
        RateLimitDecision ip2Decision = rateLimiter.tryAcquire(ip2);
        assertThat(ip2Decision.isAllowed()).isTrue();
        assertThat(ip2Decision.getRemainingRequests()).isEqualTo(19);
    }

    @Test
    @DisplayName("Eviction preserves active clients: active clients are not reset during cleanup")
    void testEviction_PreservesActiveClients() {
        String activeIp = "192.168.1.50";
        String expiredIp = "192.168.1.51";

        // Record expired IP at t = 0
        rateLimiter.tryAcquire(expiredIp);

        // Advance clock by 61 seconds and record active IP
        clock.advanceSeconds(61);
        for (int i = 1; i <= 20; i++) {
            rateLimiter.tryAcquire(activeIp);
        }
        assertThat(rateLimiter.tryAcquire(activeIp).isAllowed()).isFalse();

        // Run scheduled cleanup
        rateLimiter.scheduledCleanup();

        // Active IP must STILL be rate limited (not cleared or reset)
        RateLimitDecision activeAfterCleanup = rateLimiter.tryAcquire(activeIp);
        assertThat(activeAfterCleanup.isAllowed()).isFalse();

        // Expired IP window was cleared, so it can make a fresh request
        RateLimitDecision expiredAfterCleanup = rateLimiter.tryAcquire(expiredIp);
        assertThat(expiredAfterCleanup.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("Capacity exhaustion: when capacity is saturated, fail closed for new IPs")
    void testCapacityExhaustion_FailsClosed() {
        properties.setMaxCapacity(5); // Small capacity for test

        for (int i = 1; i <= 5; i++) {
            RateLimitDecision d = rateLimiter.tryAcquire("10.0.0." + i);
            assertThat(d.isAllowed()).isTrue();
        }

        // 6th client exceeds capacity ceiling
        RateLimitDecision overflowDecision = rateLimiter.tryAcquire("10.0.0.6");
        assertThat(overflowDecision.isAllowed()).isFalse();
        assertThat(overflowDecision.isCapacityExhausted()).isTrue();
        assertThat(overflowDecision.getRetryAfterSeconds()).isEqualTo(60L);
    }

    @Test
    @DisplayName("Untrusted peer IP ignores caller-supplied X-Forwarded-For header to prevent spoofing")
    void testUntrustedPeerIp_IgnoresXForwardedFor() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login")
                        .remoteAddress(new InetSocketAddress("203.0.113.195", 54321))
                        .header("X-Forwarded-For", "198.51.100.22, 192.0.2.1")
                        .build()
        );

        String clientIp = rateLimiter.extractClientIp(exchange);
        assertThat(clientIp).isEqualTo("203.0.113.195");
    }

    @Test
    @DisplayName("Trusted proxy IP extracts rightmost untrusted client IP from X-Forwarded-For")
    void testTrustedProxy_ExtractsClientIp() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login")
                        .remoteAddress(new InetSocketAddress("127.0.0.1", 54321)) // Trusted
                        .header("X-Forwarded-For", "203.0.113.50, 10.0.0.1")      // 10.0.0.1 is also trusted proxy
                        .build()
        );

        String clientIp = rateLimiter.extractClientIp(exchange);
        assertThat(clientIp).isEqualTo("203.0.113.50");
    }

    private static class MutableClock extends Clock {
        private final AtomicLong millis;
        private final ZoneId zone;

        public MutableClock(Instant initialInstant) {
            this.millis = new AtomicLong(initialInstant.toEpochMilli());
            this.zone = ZoneId.of("UTC");
        }

        public void advanceSeconds(long seconds) {
            millis.addAndGet(seconds * 1000L);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public long millis() {
            return millis.get();
        }
    }
}
