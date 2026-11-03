package com.rideshare.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PathSanitizationGlobalFilterTest {

    private PathSanitizationGlobalFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new PathSanitizationGlobalFilter();
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Valid normal path passes sanitization filter")
    void testValidPath_Passes() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/me").build()
        );

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain, times(1)).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Malformed percent-encoding is rejected with 400 Bad Request")
    void testMalformedPercentEncoding_Returns400() {
        org.springframework.http.server.reactive.ServerHttpRequest request = mock(org.springframework.http.server.reactive.ServerHttpRequest.class);
        URI uri = mock(URI.class);
        when(uri.getRawPath()).thenReturn("/api/v1/auth/%g1");
        when(request.getURI()).thenReturn(uri);
        org.springframework.http.server.RequestPath path = mock(org.springframework.http.server.RequestPath.class);
        when(path.value()).thenReturn("/api/v1/auth/%g1");
        when(request.getPath()).thenReturn(path);

        org.springframework.web.server.ServerWebExchange exchange = mock(org.springframework.web.server.ServerWebExchange.class);
        org.springframework.mock.http.server.reactive.MockServerHttpResponse response = new org.springframework.mock.http.server.reactive.MockServerHttpResponse();
        when(exchange.getRequest()).thenReturn(request);
        when(exchange.getResponse()).thenReturn(response);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Encoded forward slash (%2f) is rejected with 400 Bad Request")
    void testEncodedForwardSlash_Returns400() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(org.springframework.http.HttpMethod.GET, URI.create("http://localhost:8080/api%2fv1/drivers/1/reserve")).build()
        );

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Encoded backslash (%5c) is rejected with 400 Bad Request")
    void testEncodedBackslash_Returns400() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(org.springframework.http.HttpMethod.GET, URI.create("http://localhost:8080/api%5cv1/drivers")).build()
        );

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Double percent-encoding (%25) is rejected with 400 Bad Request")
    void testDoublePercentEncoding_Returns400() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(org.springframework.http.HttpMethod.GET, URI.create("http://localhost:8080/api/%252f/drivers")).build()
        );

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Dot segment path traversal (/../) is rejected with 400 Bad Request")
    void testDotSegmentTraversal_Returns400() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(org.springframework.http.HttpMethod.GET, URI.create("http://localhost:8080/api/v1/rides/../drivers/1/reserve")).build()
        );

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Duplicate path separators (//) are rejected with 400 Bad Request")
    void testDuplicateSlashes_Returns400() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(org.springframework.http.HttpMethod.GET, URI.create("http://localhost:8080/api//v1/drivers/1/reserve")).build()
        );

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Null bytes (%00) are rejected with 400 Bad Request")
    void testNullBytes_Returns400() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(org.springframework.http.HttpMethod.GET, URI.create("http://localhost:8080/api/v1/rides%00")).build()
        );

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
