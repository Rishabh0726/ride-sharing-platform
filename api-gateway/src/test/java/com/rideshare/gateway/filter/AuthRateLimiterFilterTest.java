package com.rideshare.gateway.filter;

import com.rideshare.gateway.ratelimit.InMemoryRateLimiter;
import com.rideshare.gateway.ratelimit.RateLimitDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuthRateLimiterFilterTest {

    private InMemoryRateLimiter rateLimiter;
    private AuthRateLimiterFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(InMemoryRateLimiter.class);
        filter = new AuthRateLimiterFilter(rateLimiter);
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Non-auth endpoints skip the rate limiter filter entirely")
    void testNonAuthEndpoint_SkipsRateLimiter() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/me").build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(rateLimiter, never()).extractClientIp(any());
        verify(rateLimiter, never()).tryAcquire(any());
        verify(chain, times(1)).filter(exchange);
    }

    @Test
    @DisplayName("Auth endpoint under limit passes through and sets X-RateLimit-Remaining header")
    void testAuthEndpoint_AllowsUnderLimit() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login").build()
        );

        when(rateLimiter.extractClientIp(exchange)).thenReturn("192.168.1.100");
        when(rateLimiter.tryAcquire("192.168.1.100")).thenReturn(RateLimitDecision.allowed(15));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain, times(1)).filter(exchange);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("15");
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Auth endpoint over limit returns 429 Too Many Requests with Retry-After header")
    void testAuthEndpoint_BlocksOverLimitWith429() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login").build()
        );

        when(rateLimiter.extractClientIp(exchange)).thenReturn("192.168.1.100");
        when(rateLimiter.tryAcquire("192.168.1.100")).thenReturn(RateLimitDecision.denied(45L));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("45");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Retry-After-Seconds")).isEqualTo("45");
    }

    @Test
    @DisplayName("Auth endpoint with exhausted capacity returns 429 with Retry-After 60")
    void testAuthEndpoint_BlocksOnCapacityExhaustion() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/register").build()
        );

        when(rateLimiter.extractClientIp(exchange)).thenReturn("10.0.0.99");
        when(rateLimiter.tryAcquire("10.0.0.99")).thenReturn(RateLimitDecision.capacityExhausted(60L));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("60");
    }
}
