package com.rideshare.gateway.filter;

import com.rideshare.gateway.config.RateLimiterProperties;
import com.rideshare.gateway.config.StreamingRequestSizeProperties;
import com.rideshare.gateway.ratelimit.InMemoryRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StreamingRequestSizeGlobalFilterTest {

    private StreamingRequestSizeGlobalFilter filter;
    private GatewayFilterChain chain;
    private StreamingRequestSizeProperties streamingProperties;
    private RateLimiterProperties rateLimiterProperties;
    private InMemoryRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        streamingProperties = new StreamingRequestSizeProperties();
        rateLimiterProperties = new RateLimiterProperties();
        rateLimiter = new InMemoryRateLimiter(rateLimiterProperties, Clock.systemUTC());
        filter = new StreamingRequestSizeGlobalFilter(streamingProperties, rateLimiter, Clock.systemUTC());
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Content-Length exceeding 2 MiB is rejected upfront with 413 without calling chain or acquiring permits")
    void testContentLengthOverLimit_FastRejection() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides")
                        .remoteAddress(new InetSocketAddress("198.51.100.1", 12345))
                        .header(HttpHeaders.CONTENT_LENGTH, "2500000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("test")
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(filter.getGlobalActiveChunkedRequests()).isZero();
        assertThat(filter.getIpActiveChunkedRequests("198.51.100.1")).isZero();
    }

    @Test
    @DisplayName("Content-Length within 2 MiB passes through directly to chain without buffering or acquiring permits")
    void testContentLengthUnderLimit_PassesThrough() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides")
                        .remoteAddress(new InetSocketAddress("198.51.100.1", 12345))
                        .header(HttpHeaders.CONTENT_LENGTH, "100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("test")
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain, times(1)).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(filter.getGlobalActiveChunkedRequests()).isZero();
        assertThat(filter.getIpActiveChunkedRequests("198.51.100.1")).isZero();
    }

    @Test
    @DisplayName("Chunked payload under 2 MiB aggregates, calls chain, updates Content-Length, strips Transfer-Encoding, and releases permits")
    void testChunkedPayload_UnderLimit_AggregatesAndForwards() {
        byte[] chunkData = new byte[100 * 1024]; // 100 KiB
        Flux<DataBuffer> bodyFlux = Flux.range(0, 3) // 300 KiB total
                .map(i -> DefaultDataBufferFactory.sharedInstance.wrap(chunkData));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides")
                        .remoteAddress(new InetSocketAddress("198.51.100.10", 12345))
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(bodyFlux)
        );

        ArgumentCaptor<ServerWebExchange> exchangeCaptor = ArgumentCaptor.forClass(ServerWebExchange.class);
        AtomicInteger consumedBytes = new AtomicInteger(0);

        when(chain.filter(exchangeCaptor.capture())).thenAnswer(invocation -> {
            ServerWebExchange captured = exchangeCaptor.getValue();
            return captured.getRequest().getBody()
                    .doOnNext(buf -> consumedBytes.addAndGet(buf.readableByteCount()))
                    .then();
        });

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain, times(1)).filter(any());
        assertThat(consumedBytes.get()).isEqualTo(300 * 1024);

        ServerWebExchange captured = exchangeCaptor.getValue();
        assertThat(captured.getRequest().getHeaders().getContentLength()).isEqualTo(300 * 1024);
        assertThat(captured.getRequest().getHeaders().getFirst(HttpHeaders.TRANSFER_ENCODING)).isNull();

        // Permits must be released after completion
        assertThat(filter.getGlobalActiveChunkedRequests()).isZero();
        assertThat(filter.getIpActiveChunkedRequests("198.51.100.10")).isZero();
    }

    @Test
    @DisplayName("Chunked payload exactly at 2 MiB ceiling (2,097,152 bytes) is accepted and forwarded to chain")
    void testChunkedPayload_ExactLimit_Accepted() {
        byte[] chunkData = new byte[64 * 1024]; // 64 KiB
        Flux<DataBuffer> bodyFlux = Flux.range(0, 32) // 32 * 64 KiB = 2,097,152 bytes (exactly 2 MiB)
                .map(i -> DefaultDataBufferFactory.sharedInstance.wrap(chunkData));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides")
                        .remoteAddress(new InetSocketAddress("198.51.100.11", 12345))
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(bodyFlux)
        );

        ArgumentCaptor<ServerWebExchange> exchangeCaptor = ArgumentCaptor.forClass(ServerWebExchange.class);
        when(chain.filter(exchangeCaptor.capture())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain, times(1)).filter(any());
        ServerWebExchange captured = exchangeCaptor.getValue();
        assertThat(captured.getRequest().getHeaders().getContentLength()).isEqualTo(2097152L);
        assertThat(filter.getGlobalActiveChunkedRequests()).isZero();
        assertThat(filter.getIpActiveChunkedRequests("198.51.100.11")).isZero();
    }

    @Test
    @DisplayName("Chunked payload exceeding 2 MiB is rejected with 413, chain is NEVER called, and permits released")
    void testChunkedPayload_OverLimit_AbortsAndNeverCallsChain() {
        byte[] chunkData = new byte[100 * 1024]; // 100 KiB
        Flux<DataBuffer> bodyFlux = Flux.range(0, 25) // 2.5 MiB (> 2 MiB)
                .map(i -> DefaultDataBufferFactory.sharedInstance.wrap(chunkData));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides/1/arrive")
                        .remoteAddress(new InetSocketAddress("198.51.100.12", 12345))
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(bodyFlux)
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        // Crucial verification: Downstream chain was NEVER called
        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        // Permits must be released
        assertThat(filter.getGlobalActiveChunkedRequests()).isZero();
        assertThat(filter.getIpActiveChunkedRequests("198.51.100.12")).isZero();
    }

    @Test
    @DisplayName("Single chunk larger than 2 MiB is immediately released and rejected with 413 without calling chain")
    void testChunkedPayload_SingleLargeChunk_AbortsImmediately() {
        byte[] largeChunk = new byte[2500 * 1024]; // 2.5 MiB single chunk
        Flux<DataBuffer> bodyFlux = Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(largeChunk));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides/1/complete")
                        .remoteAddress(new InetSocketAddress("198.51.100.13", 12345))
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(bodyFlux)
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(filter.getGlobalActiveChunkedRequests()).isZero();
        assertThat(filter.getIpActiveChunkedRequests("198.51.100.13")).isZero();
    }

    @Test
    @DisplayName("When downstream does not consume the request body, buffers and permits are safely released in doFinally")
    void testChunkedPayload_UnconsumedBody_SafelyReleased() {
        byte[] chunkData = new byte[50 * 1024];
        Flux<DataBuffer> bodyFlux = Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(chunkData));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides/1/arrive")
                        .remoteAddress(new InetSocketAddress("198.51.100.14", 12345))
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(bodyFlux)
        );

        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain, times(1)).filter(any());
        assertThat(filter.getGlobalActiveChunkedRequests()).isZero();
        assertThat(filter.getIpActiveChunkedRequests("198.51.100.14")).isZero();
    }

    @Test
    @DisplayName("Empty chunked body is accepted, Content-Length set to 0, and forwarded cleanly")
    void testChunkedPayload_EmptyBody_ForwardedCleanly() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides/1/arrive")
                        .remoteAddress(new InetSocketAddress("198.51.100.15", 12345))
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .body(Flux.empty())
        );

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        when(chain.filter(captor.capture())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain, times(1)).filter(any());
        ServerWebExchange captured = captor.getValue();
        assertThat(captured.getRequest().getHeaders().getContentLength()).isZero();
        assertThat(captured.getRequest().getHeaders().getFirst(HttpHeaders.TRANSFER_ENCODING)).isNull();
        assertThat(filter.getGlobalActiveChunkedRequests()).isZero();
        assertThat(filter.getIpActiveChunkedRequests("198.51.100.15")).isZero();
    }

    @Test
    @DisplayName("Stream cancellation during chunk aggregation triggers release of all retained buffers and permits")
    void testChunkedPayload_CancelledStream_ReleasesBuffersAndPermits() {
        byte[] chunk = new byte[64 * 1024];
        Flux<DataBuffer> infiniteChunks = Flux.range(0, 10)
                .map(i -> DefaultDataBufferFactory.sharedInstance.wrap(chunk));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides")
                        .remoteAddress(new InetSocketAddress("198.51.100.16", 12345))
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .body(infiniteChunks)
        );

        StepVerifier.create(filter.filter(exchange, chain))
                .thenCancel()
                .verify();

        verify(chain, never()).filter(any());
        assertThat(filter.getGlobalActiveChunkedRequests()).isZero();
        assertThat(filter.getIpActiveChunkedRequests("198.51.100.16")).isZero();
    }

    @Test
    @DisplayName("Upload exceeding total deadline returns HTTP 408, never calls downstream, and releases permits")
    void testChunkedPayload_TotalUploadDeadline_Returns408() {
        streamingProperties.setTimeout(Duration.ofMillis(150));

        // Stream emits 4 chunks delayed by 80ms each (total ~320ms > 150ms timeout)
        byte[] chunkData = new byte[10 * 1024];
        Flux<DataBuffer> slowFlux = Flux.interval(Duration.ofMillis(80))
                .take(4)
                .map(i -> DefaultDataBufferFactory.sharedInstance.wrap(chunkData));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides/1/arrive")
                        .remoteAddress(new InetSocketAddress("198.51.100.20", 12345))
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(slowFlux)
        );

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // Downstream was never contacted
        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.REQUEST_TIMEOUT);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        // Permits must be released
        assertThat(filter.getGlobalActiveChunkedRequests()).isZero();
        assertThat(filter.getIpActiveChunkedRequests("198.51.100.20")).isZero();
    }

    @Test
    @DisplayName("Stalled chunked client without further data times out with HTTP 408 and releases permits")
    void testChunkedPayload_StalledClient_TimesOutWith408() {
        streamingProperties.setTimeout(Duration.ofMillis(100));

        // Client sends 1 chunk and then completely stalls
        byte[] chunkData = new byte[10 * 1024];
        Flux<DataBuffer> stalledFlux = Flux.<DataBuffer>just(DefaultDataBufferFactory.sharedInstance.wrap(chunkData))
                .concatWith(Mono.never());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides")
                        .remoteAddress(new InetSocketAddress("198.51.100.21", 12345))
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(stalledFlux)
        );

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.REQUEST_TIMEOUT);
        assertThat(filter.getGlobalActiveChunkedRequests()).isZero();
        assertThat(filter.getIpActiveChunkedRequests("198.51.100.21")).isZero();
    }

    @Test
    @DisplayName("Global capacity saturation rejects subsequent chunked request with HTTP 429 before body aggregation")
    void testChunkedPayload_GlobalSaturation_RejectsWith429() {
        streamingProperties.setMaxConcurrentRequests(2);

        CompletableFuture<Void> holdPermit1 = new CompletableFuture<>();
        CompletableFuture<Void> holdPermit2 = new CompletableFuture<>();

        GatewayFilterChain blockingChain = mock(GatewayFilterChain.class);
        when(blockingChain.filter(any()))
                .thenReturn(Mono.fromFuture(holdPermit1))
                .thenReturn(Mono.fromFuture(holdPermit2));

        // Start 2 concurrent requests that remain in flight
        byte[] chunk = new byte[1024];
        MockServerWebExchange ex1 = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides")
                        .remoteAddress(new InetSocketAddress("198.51.100.1", 12345))
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(chunk)))
        );
        MockServerWebExchange ex2 = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides")
                        .remoteAddress(new InetSocketAddress("198.51.100.2", 12345))
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(chunk)))
        );

        filter.filter(ex1, blockingChain).subscribe();
        filter.filter(ex2, blockingChain).subscribe();

        assertThat(filter.getGlobalActiveChunkedRequests()).isEqualTo(2);

        // 3rd request arrives from any IP
        MockServerWebExchange ex3 = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides")
                        .remoteAddress(new InetSocketAddress("198.51.100.3", 12345))
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(chunk)))
        );

        StepVerifier.create(filter.filter(ex3, chain)).verifyComplete();

        // 3rd request rejected immediately with 429
        assertThat(ex3.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ex3.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("5");
        assertThat(filter.getGlobalActiveChunkedRequests()).isEqualTo(2); // Still 2 holding

        // Release the 2 in flight
        holdPermit1.complete(null);
        holdPermit2.complete(null);

        assertThat(filter.getGlobalActiveChunkedRequests()).isZero();
    }

    @Test
    @DisplayName("Per-IP capacity saturation rejects subsequent request for same IP with HTTP 429 while allowing other IPs")
    void testChunkedPayload_PerIpSaturation_RejectsWith429() {
        streamingProperties.setMaxConcurrentPerIp(2);
        streamingProperties.setMaxConcurrentRequests(50);

        CompletableFuture<Void> holdPermit1 = new CompletableFuture<>();
        CompletableFuture<Void> holdPermit2 = new CompletableFuture<>();

        GatewayFilterChain blockingChain = mock(GatewayFilterChain.class);
        when(blockingChain.filter(any()))
                .thenReturn(Mono.fromFuture(holdPermit1))
                .thenReturn(Mono.fromFuture(holdPermit2));

        byte[] chunk = new byte[1024];
        String clientIp = "198.51.100.50";

        // Start 2 concurrent requests from clientIp
        MockServerWebExchange ex1 = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides")
                        .remoteAddress(new InetSocketAddress(clientIp, 12345))
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(chunk)))
        );
        MockServerWebExchange ex2 = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides")
                        .remoteAddress(new InetSocketAddress(clientIp, 12346))
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(chunk)))
        );

        filter.filter(ex1, blockingChain).subscribe();
        filter.filter(ex2, blockingChain).subscribe();

        assertThat(filter.getIpActiveChunkedRequests(clientIp)).isEqualTo(2);

        // 3rd request from same clientIp
        MockServerWebExchange ex3 = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides")
                        .remoteAddress(new InetSocketAddress(clientIp, 12347))
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(chunk)))
        );

        StepVerifier.create(filter.filter(ex3, chain)).verifyComplete();

        // 3rd request from clientIp rejected with 429
        assertThat(ex3.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Another client IP (198.51.100.99) should be permitted
        MockServerWebExchange exOther = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides")
                        .remoteAddress(new InetSocketAddress("198.51.100.99", 12345))
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(chunk)))
        );

        when(chain.filter(any())).thenReturn(Mono.empty());
        StepVerifier.create(filter.filter(exOther, chain)).verifyComplete();

        // Release first two
        holdPermit1.complete(null);
        holdPermit2.complete(null);

        assertThat(filter.getIpActiveChunkedRequests(clientIp)).isZero();
        assertThat(filter.getGlobalActiveChunkedRequests()).isZero();
    }

    @Test
    @DisplayName("Untrusted direct peer cannot bypass per-IP capacity saturation using spoofed X-Forwarded-For")
    void testChunkedPayload_UntrustedPeer_SpoofedXffCannotBypassPerIpCap() {
        streamingProperties.setMaxConcurrentPerIp(1);

        CompletableFuture<Void> holdPermit = new CompletableFuture<>();
        GatewayFilterChain blockingChain = mock(GatewayFilterChain.class);
        when(blockingChain.filter(any())).thenReturn(Mono.fromFuture(holdPermit));

        byte[] chunk = new byte[1024];
        String untrustedPeerIp = "198.51.100.77"; // Not in trusted-proxies (which has 127.0.0.1, ::1)

        // Request 1 from untrustedPeerIp
        MockServerWebExchange ex1 = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides")
                        .remoteAddress(new InetSocketAddress(untrustedPeerIp, 12345))
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(chunk)))
        );
        filter.filter(ex1, blockingChain).subscribe();

        assertThat(filter.getIpActiveChunkedRequests(untrustedPeerIp)).isEqualTo(1);

        // Request 2 from untrustedPeerIp spoofing X-Forwarded-For: 203.0.113.88
        MockServerWebExchange ex2 = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides")
                        .remoteAddress(new InetSocketAddress(untrustedPeerIp, 12346))
                        .header("X-Forwarded-For", "203.0.113.88")
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(chunk)))
        );

        StepVerifier.create(filter.filter(ex2, chain)).verifyComplete();

        // Request 2 must be rejected with 429 because untrusted peer IP was used, ignoring spoofed header
        assertThat(ex2.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        holdPermit.complete(null);
        assertThat(filter.getIpActiveChunkedRequests(untrustedPeerIp)).isZero();
    }

    @Test
    @DisplayName("Trusted reverse proxy resolves client IP from X-Forwarded-For correctly")
    void testChunkedPayload_TrustedProxy_ResolvesClientIpFromXff() {
        streamingProperties.setMaxConcurrentPerIp(1);

        CompletableFuture<Void> holdPermit = new CompletableFuture<>();
        GatewayFilterChain blockingChain = mock(GatewayFilterChain.class);
        when(blockingChain.filter(any())).thenReturn(Mono.fromFuture(holdPermit));

        byte[] chunk = new byte[1024];
        String trustedProxyIp = "127.0.0.1"; // In trusted-proxies

        // Client 1 via trusted proxy: X-Forwarded-For: 203.0.113.1
        MockServerWebExchange ex1 = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides")
                        .remoteAddress(new InetSocketAddress(trustedProxyIp, 12345))
                        .header("X-Forwarded-For", "203.0.113.1")
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(chunk)))
        );
        filter.filter(ex1, blockingChain).subscribe();

        assertThat(filter.getIpActiveChunkedRequests("203.0.113.1")).isEqualTo(1);

        // Client 2 via same trusted proxy: X-Forwarded-For: 203.0.113.2 (different client IP)
        MockServerWebExchange ex2 = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides")
                        .remoteAddress(new InetSocketAddress(trustedProxyIp, 12346))
                        .header("X-Forwarded-For", "203.0.113.2")
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(chunk)))
        );

        when(chain.filter(any())).thenReturn(Mono.empty());
        StepVerifier.create(filter.filter(ex2, chain)).verifyComplete();

        // Client 2 is permitted because it's a distinct client IP
        assertThat(ex2.getResponse().getStatusCode()).isNull();

        holdPermit.complete(null);
        assertThat(filter.getIpActiveChunkedRequests("203.0.113.1")).isZero();
    }

    @Test
    @DisplayName("Downstream exception releases concurrency permits cleanly")
    void testChunkedPayload_DownstreamFailure_ReleasesPermits() {
        byte[] chunk = new byte[1024];
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/rides")
                        .remoteAddress(new InetSocketAddress("198.51.100.88", 12345))
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(chunk)))
        );

        when(chain.filter(any())).thenReturn(Mono.error(new RuntimeException("Simulated downstream error")));

        StepVerifier.create(filter.filter(exchange, chain))
                .expectError(RuntimeException.class)
                .verify();

        assertThat(filter.getGlobalActiveChunkedRequests()).isZero();
        assertThat(filter.getIpActiveChunkedRequests("198.51.100.88")).isZero();
    }

    @Test
    @DisplayName("High concurrency test demonstrates zero permit leaks, non-negative counters, and clean entry removal")
    void testChunkedPayload_ConcurrentRequests_ZeroPermitLeaks() {
        int totalRequests = 40;
        AtomicInteger passedCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        Flux.range(0, totalRequests)
                .flatMap(i -> {
                    boolean isOversized = (i % 2 == 0); // 20 oversized (2.5 MiB), 20 valid (100 KiB)
                    int chunkCount = isOversized ? 25 : 1;
                    byte[] chunk = new byte[100 * 1024];
                    Flux<DataBuffer> body = Flux.range(0, chunkCount)
                            .map(c -> DefaultDataBufferFactory.sharedInstance.wrap(chunk));

                    MockServerWebExchange ex = MockServerWebExchange.from(
                            MockServerHttpRequest.post("/api/v1/rides")
                                    .remoteAddress(new InetSocketAddress("198.51.100." + (i % 5), 12345))
                                    .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                                    .body(body)
                    );

                    GatewayFilterChain localChain = mock(GatewayFilterChain.class);
                    when(localChain.filter(any())).thenAnswer(inv -> {
                        ServerWebExchange captured = inv.getArgument(0);
                        return captured.getRequest().getBody().then();
                    });

                    return filter.filter(ex, localChain)
                            .doOnSuccess(v -> {
                                if (ex.getResponse().getStatusCode() == HttpStatus.PAYLOAD_TOO_LARGE) {
                                    rejectedCount.incrementAndGet();
                                } else {
                                    passedCount.incrementAndGet();
                                }
                            });
                })
                .blockLast();

        assertThat(passedCount.get()).isEqualTo(20);
        assertThat(rejectedCount.get()).isEqualTo(20);

        // Verification of invariant: zero permit leaks, counters non-negative and exactly 0
        assertThat(filter.getGlobalActiveChunkedRequests()).isZero();
        for (int i = 0; i < 5; i++) {
            assertThat(filter.getIpActiveChunkedRequests("198.51.100." + i)).isZero();
        }
    }

    @Test
    @DisplayName("Filter order is configured to FILTER_ORDER = -85")
    void testFilterOrder() {
        assertThat(filter.getOrder()).isEqualTo(-85);
        assertThat(StreamingRequestSizeGlobalFilter.FILTER_ORDER).isEqualTo(-85);
    }
}
