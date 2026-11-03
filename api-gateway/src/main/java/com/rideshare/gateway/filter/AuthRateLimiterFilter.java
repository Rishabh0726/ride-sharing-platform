package com.rideshare.gateway.filter;

import com.rideshare.gateway.ratelimit.InMemoryRateLimiter;
import com.rideshare.gateway.ratelimit.RateLimitDecision;
import com.rideshare.gateway.util.ProblemDetailWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuthRateLimiterFilter implements GlobalFilter, Ordered {

    public static final int FILTER_ORDER = -60;

    private final InMemoryRateLimiter rateLimiter;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        HttpMethod method = exchange.getRequest().getMethod();

        // Rate limiting applies specifically to public authentication endpoints
        boolean isAuthEndpoint = method == HttpMethod.POST &&
                (path.equals("/api/v1/auth/register") || path.equals("/api/v1/auth/login"));

        if (!isAuthEndpoint) {
            return chain.filter(exchange);
        }

        String clientIp = rateLimiter.extractClientIp(exchange);
        RateLimitDecision decision = rateLimiter.tryAcquire(clientIp);

        if (decision.isAllowed()) {
            exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", String.valueOf(decision.getRemainingRequests()));
            return chain.filter(exchange);
        }

        long retryAfterSeconds = decision.getRetryAfterSeconds();
        Map<String, String> rateLimitHeaders = Map.of(
                "Retry-After", String.valueOf(retryAfterSeconds),
                "X-RateLimit-Retry-After-Seconds", String.valueOf(retryAfterSeconds)
        );

        if (decision.isCapacityExhausted()) {
            log.warn("Rate limit capacity saturated. Rejecting request for client IP: {}", clientIp);
            return ProblemDetailWriter.writeProblemResponse(
                    exchange,
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too Many Requests",
                    "Authentication rate-limiting capacity is temporarily saturated; please retry shortly",
                    "https://api.rideshare.com/errors/too-many-requests",
                    rateLimitHeaders
            );
        }

        log.warn("Rate limit exceeded for client IP: {}. Retry after {} seconds", clientIp, retryAfterSeconds);
        return ProblemDetailWriter.writeProblemResponse(
                exchange,
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                "Rate limit exceeded: maximum 20 requests per minute allowed on authentication endpoints",
                "https://api.rideshare.com/errors/too-many-requests",
                rateLimitHeaders
        );
    }

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }
}
