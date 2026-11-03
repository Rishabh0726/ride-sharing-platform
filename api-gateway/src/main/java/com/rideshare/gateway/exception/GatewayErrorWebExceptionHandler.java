package com.rideshare.gateway.exception;

import com.rideshare.gateway.util.ProblemDetailWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.net.ConnectException;

@Component
@Order(-2)
@Slf4j
public class GatewayErrorWebExceptionHandler implements WebExceptionHandler, Ordered {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        log.debug("Gateway exception encountered: {} - {}", ex.getClass().getName(), ex.getMessage());

        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }

        // 1. Payload Too Large (RequestSize filter or WebFlux limit)
        if (ex.getClass().getSimpleName().contains("PayloadTooLarge") ||
            (ex instanceof ResponseStatusException rse && rse.getStatusCode() == HttpStatus.PAYLOAD_TOO_LARGE)) {
            return ProblemDetailWriter.writeProblemResponse(
                    exchange,
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Payload Too Large",
                    "Request payload exceeds the maximum allowed size of 2097152 bytes (2 MiB)",
                    "https://api.rideshare.com/errors/payload-too-large"
            );
        }

        // 2. Downstream Connectivity / Unreachable (502 Bad Gateway)
        if (ex instanceof ConnectException || ex.getCause() instanceof ConnectException ||
            ex.getClass().getName().contains("AnnotatedConnectException")) {
            log.error("Downstream service connection refused: {}", ex.getMessage());
            return ProblemDetailWriter.writeProblemResponse(
                    exchange,
                    HttpStatus.BAD_GATEWAY,
                    "Bad Gateway",
                    "Downstream microservice is temporarily unreachable",
                    "https://api.rideshare.com/errors/bad-gateway"
            );
        }

        // 3. Downstream Read / Response Timeout (504 Gateway Timeout)
        if (ex instanceof java.util.concurrent.TimeoutException ||
            (ex.getCause() != null && ex.getCause() instanceof java.util.concurrent.TimeoutException) ||
            ex.getClass().getName().contains("TimeoutException")) {
            log.error("Downstream service response timed out: {}", ex.getMessage());
            return ProblemDetailWriter.writeProblemResponse(
                    exchange,
                    HttpStatus.GATEWAY_TIMEOUT,
                    "Gateway Timeout",
                    "Downstream microservice response timed out",
                    "https://api.rideshare.com/errors/gateway-timeout"
            );
        }

        // 4. ResponseStatusException
        if (ex instanceof ResponseStatusException rse) {
            HttpStatus status = HttpStatus.resolve(rse.getStatusCode().value());
            if (status == null) {
                status = HttpStatus.INTERNAL_SERVER_ERROR;
            }
            return ProblemDetailWriter.writeProblemResponse(
                    exchange,
                    status,
                    status.getReasonPhrase(),
                    rse.getReason() != null ? rse.getReason() : rse.getMessage(),
                    "https://api.rideshare.com/errors/" + status.value()
            );
        }

        // 5. Generic Internal Server Error
        log.error("Unhandled gateway error: ", ex);
        return ProblemDetailWriter.writeProblemResponse(
                exchange,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                "An unexpected gateway error occurred",
                "https://api.rideshare.com/errors/internal-error"
        );
    }

    @Override
    public int getOrder() {
        return -2;
    }
}
