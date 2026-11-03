package com.rideshare.gateway.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayErrorWebExceptionHandlerTest {

    private GatewayErrorWebExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GatewayErrorWebExceptionHandler();
    }

    @Test
    @DisplayName("PayloadTooLargeException maps to RFC 7807 413 Payload Too Large")
    void testPayloadTooLarge_MapsTo413() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides").build()
        );

        ResponseStatusException ex = new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Exceeded maximum allowed size");

        StepVerifier.create(handler.handle(exchange, ex)).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(exchange.getResponse().getHeaders().getContentType().toString()).contains("application/problem+json");
    }

    @Test
    @DisplayName("ConnectException maps to RFC 7807 502 Bad Gateway")
    void testConnectException_MapsTo502() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/me").build()
        );

        ConnectException ex = new ConnectException("Connection refused: no further information");

        StepVerifier.create(handler.handle(exchange, ex)).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(exchange.getResponse().getHeaders().getContentType().toString()).contains("application/problem+json");
    }

    @Test
    @DisplayName("TimeoutException maps to RFC 7807 504 Gateway Timeout")
    void testTimeoutException_MapsTo504() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/pricing/estimate").build()
        );

        TimeoutException ex = new TimeoutException("Response timed out after 3000ms");

        StepVerifier.create(handler.handle(exchange, ex)).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(exchange.getResponse().getHeaders().getContentType().toString()).contains("application/problem+json");
    }

    @Test
    @DisplayName("ResponseStatusException maps to respective status code")
    void testResponseStatusException_PreservesStatus() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/unknown").build()
        );

        ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found");

        StepVerifier.create(handler.handle(exchange, ex)).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
