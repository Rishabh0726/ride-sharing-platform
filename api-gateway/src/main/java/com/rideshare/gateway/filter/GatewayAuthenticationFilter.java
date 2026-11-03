package com.rideshare.gateway.filter;

import com.rideshare.gateway.security.JwtValidator;
import com.rideshare.gateway.util.ProblemDetailWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Locale;

@Component
@RequiredArgsConstructor
@Slf4j
public class GatewayAuthenticationFilter implements GlobalFilter, Ordered {

    public static final int FILTER_ORDER = -70;

    private final JwtValidator jwtValidator;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        HttpMethod method = exchange.getRequest().getMethod();

        // 1. Actuator isolation: only /actuator/health and /actuator/info are allowed publicly
        if (path.startsWith("/actuator")) {
            boolean isAllowedActuator = method == HttpMethod.GET &&
                    (path.equals("/actuator/health") || path.startsWith("/actuator/health/") ||
                     path.equals("/actuator/info") || path.startsWith("/actuator/info/"));

            if (isAllowedActuator) {
                return chain.filter(exchange);
            } else {
                log.warn("Blocked access to restricted actuator endpoint: {} {}", method, path);
                return ProblemDetailWriter.writeProblemResponse(
                        exchange,
                        HttpStatus.NOT_FOUND,
                        "Not Found",
                        "Actuator endpoint is not publicly exposed",
                        "https://api.rideshare.com/errors/not-found"
                );
            }
        }

        // 2. Exact public authentication allowlist: POST /api/v1/auth/register and POST /api/v1/auth/login
        boolean isPublicAuth = method == HttpMethod.POST &&
                (path.equals("/api/v1/auth/register") || path.equals("/api/v1/auth/login"));

        if (isPublicAuth) {
            return chain.filter(exchange);
        }

        // 3. For all other endpoints, require valid Bearer JWT
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("Unauthenticated request to protected route: {} {}", method, path);
            return ProblemDetailWriter.writeProblemResponse(
                    exchange,
                    HttpStatus.UNAUTHORIZED,
                    "Unauthorized",
                    "Full authentication is required to access this resource",
                    "https://api.rideshare.com/errors/unauthorized"
            );
        }

        String token = authHeader.substring(7).trim();
        if (!jwtValidator.validateToken(token)) {
            log.debug("Invalid or expired JWT token for route: {} {}", method, path);
            return ProblemDetailWriter.writeProblemResponse(
                    exchange,
                    HttpStatus.UNAUTHORIZED,
                    "Unauthorized",
                    "JWT token is invalid or expired",
                    "https://api.rideshare.com/errors/unauthorized"
            );
        }

        // 4. Reject ROLE_INTERNAL_SERVICE at the public gateway
        String role = jwtValidator.getRole(token);
        if (role != null && "ROLE_INTERNAL_SERVICE".equalsIgnoreCase(role.trim())) {
            log.warn("Rejected ROLE_INTERNAL_SERVICE token on public gateway route: {} {}", method, path);
            return ProblemDetailWriter.writeProblemResponse(
                    exchange,
                    HttpStatus.FORBIDDEN,
                    "Forbidden",
                    "ROLE_INTERNAL_SERVICE tokens are strictly forbidden from accessing public gateway routes",
                    "https://api.rideshare.com/errors/forbidden"
            );
        }

        // 5. Token is valid and belongs to an authorized end-user role. Forward request with original Bearer token
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }
}
