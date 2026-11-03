package com.rideshare.gateway.filter;

import com.rideshare.gateway.util.ProblemDetailWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.regex.Pattern;

@Component
@Slf4j
public class InternalEndpointBlockFilter implements GlobalFilter, Ordered {

    public static final int FILTER_ORDER = -80;

    private static final Pattern INTERNAL_DRIVER_OPERATIONS_PATTERN =
            Pattern.compile("^/api/v1/drivers/[^/]+/(reserve|confirm-ride|release-reservation|complete-ride)(/.*)?$");

    private static final Pattern INTERNAL_PRICING_QUOTES_PATTERN =
            Pattern.compile("^/api/v1/pricing/quotes(/.*)?$");

    private static final Pattern INTERNAL_NOTIFICATIONS_INGEST_PATTERN =
            Pattern.compile("^/api/v1/notifications(/.*)?$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String canonicalPath = exchange.getRequest().getPath().value().toLowerCase(Locale.ROOT);
        HttpMethod method = exchange.getRequest().getMethod();

        // 1. Internal driver reservation, confirmation, release, and completion
        if (method == HttpMethod.POST && INTERNAL_DRIVER_OPERATIONS_PATTERN.matcher(canonicalPath).matches()) {
            log.warn("Blocked public access to internal driver operational route: {} {}", method, canonicalPath);
            return blockInternalEndpoint(exchange);
        }

        // 2. Internal pricing quote retrieval and snapshot verification
        if (INTERNAL_PRICING_QUOTES_PATTERN.matcher(canonicalPath).matches()) {
            log.warn("Blocked public access to internal pricing quotes route: {} {}", method, canonicalPath);
            return blockInternalEndpoint(exchange);
        }

        // 3. Internal notification event ingestion (POST /api/v1/notifications)
        if (method == HttpMethod.POST && INTERNAL_NOTIFICATIONS_INGEST_PATTERN.matcher(canonicalPath).matches()) {
            log.warn("Blocked public access to internal notification ingestion route: {} {}", method, canonicalPath);
            return blockInternalEndpoint(exchange);
        }

        return chain.filter(exchange);
    }

    private Mono<Void> blockInternalEndpoint(ServerWebExchange exchange) {
        return ProblemDetailWriter.writeProblemResponse(
                exchange,
                HttpStatus.FORBIDDEN,
                "Forbidden",
                "Access to internal microservice endpoint is forbidden through the public API Gateway",
                "https://api.rideshare.com/errors/forbidden"
        );
    }

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }
}
