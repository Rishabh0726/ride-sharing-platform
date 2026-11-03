package com.rideshare.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InternalEndpointBlockFilterTest {

    private InternalEndpointBlockFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new InternalEndpointBlockFilter();
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Public driver availability discovery passes through")
    void testPublicDriverAvailable_Passes() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/drivers/available?latitude=12.9716&longitude=77.5946").build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain, times(1)).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Public end-user notification retrieval passes through")
    void testPublicNotificationInbox_Passes() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/notifications").build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain, times(1)).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Internal driver reserve endpoint is blocked with 403 Forbidden")
    void testDriverReserve_BlockedWith403() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/drivers/42/reserve").build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Internal driver confirm-ride endpoint is blocked with 403 Forbidden")
    void testDriverConfirmRide_BlockedWith403() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/drivers/42/confirm-ride").build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Internal driver release-reservation endpoint is blocked with 403 Forbidden")
    void testDriverReleaseReservation_BlockedWith403() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/drivers/42/release-reservation").build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Internal driver complete-ride endpoint is blocked with 403 Forbidden")
    void testDriverCompleteRide_BlockedWith403() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/drivers/42/complete-ride").build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Internal pricing quote lookup endpoint is blocked with 403 Forbidden")
    void testPricingQuoteLookup_BlockedWith403() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/pricing/quotes/quote-token-xyz-123").build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Internal notification ingestion endpoint is blocked with 403 Forbidden")
    void testNotificationIngestion_BlockedWith403() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/notifications").build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Case variations of internal endpoints are blocked with 403 Forbidden")
    void testCaseVariation_BlockedWith403() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/API/V1/DRIVERS/42/RESERVE").build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Trailing slash variations of internal endpoints are blocked with 403 Forbidden")
    void testTrailingSlash_BlockedWith403() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/drivers/42/reserve/").build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
