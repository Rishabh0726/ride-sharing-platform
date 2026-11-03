package com.rideshare.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class UntrustedHeaderStrippingFilterTest {

    private UntrustedHeaderStrippingFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new UntrustedHeaderStrippingFilter();
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Caller-supplied untrusted headers are stripped before downstream routing")
    void testUntrustedHeadersAreStripped() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides")
                        .header("X-User-Id", "999")
                        .header("X-User-Role", "ROLE_ADMIN")
                        .header("X-User-Email", "attacker@evil.com")
                        .header("X-User-Phone", "+19999999999")
                        .header("X-Service-Name", "ride-service")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid.token")
                        .header("Idempotency-Key", "idemp-key-123")
                        .build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain, times(1)).filter(captor.capture());

        ServerWebExchange capturedExchange = captor.getValue();
        HttpHeaders capturedHeaders = capturedExchange.getRequest().getHeaders();

        // Verify untrusted headers were removed
        assertThat(capturedHeaders.get("X-User-Id")).isNull();
        assertThat(capturedHeaders.get("X-User-Role")).isNull();
        assertThat(capturedHeaders.get("X-User-Email")).isNull();
        assertThat(capturedHeaders.get("X-User-Phone")).isNull();
        assertThat(capturedHeaders.get("X-Service-Name")).isNull();

        // Verify legitimate headers were preserved
        assertThat(capturedHeaders.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer valid.token");
        assertThat(capturedHeaders.getFirst("Idempotency-Key")).isEqualTo("idemp-key-123");
    }

    @Test
    @DisplayName("Request with no untrusted headers passes through without mutation")
    void testRequestWithoutUntrustedHeaders_Passes() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid.token")
                        .build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain, times(1)).filter(exchange);
    }
}
