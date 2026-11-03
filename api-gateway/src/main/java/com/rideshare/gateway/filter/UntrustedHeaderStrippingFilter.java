package com.rideshare.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@Slf4j
public class UntrustedHeaderStrippingFilter implements GlobalFilter, Ordered {

    public static final int FILTER_ORDER = -90;

    private static final List<String> UNTRUSTED_HEADERS = List.of(
            "X-User-Id",
            "X-User-Role",
            "X-User-Email",
            "X-User-Phone",
            "X-Service-Name"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        boolean hasUntrustedHeaders = false;

        for (String headerName : UNTRUSTED_HEADERS) {
            if (request.getHeaders().get(headerName) != null) {
                hasUntrustedHeaders = true;
                log.debug("Stripping caller-supplied untrusted header: {}", headerName);
            }
        }

        if (hasUntrustedHeaders) {
            ServerHttpRequest mutatedRequest = request.mutate()
                    .headers(headers -> UNTRUSTED_HEADERS.forEach(headers::remove))
                    .build();
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }
}
