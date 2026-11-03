package com.rideshare.gateway.filter;

import com.rideshare.gateway.config.GatewayRoutesConfig;
import com.rideshare.gateway.config.StreamingRequestSizeProperties;
import com.rideshare.gateway.ratelimit.InMemoryRateLimiter;
import com.rideshare.gateway.util.ProblemDetailWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Non-blocking pre-routing request-size filter that enforces the 2 MiB request payload ceiling,
 * admission control (global and per-IP concurrency caps), and genuine total upload deadline.
 *
 * Enforces:
 * 1. Fast upfront rejection for Content-Length > 2 MiB (HTTP 413).
 * 2. Unbuffered pass-through for valid Content-Length requests.
 * 3. Admission control for chunked transfer requests:
 *    - Rejects with HTTP 429 before allocating buffers when global capacity (default 50) is saturated.
 *    - Rejects with HTTP 429 before allocating buffers when per-IP capacity (default 10) is saturated.
 *    - Validates client IP using trusted-proxy-aware extraction (ignoring spoofable X-Forwarded-For).
 * 4. Bounded pre-routing aggregation up to 2 MiB with total upload deadline (default 10s):
 *    - If deadline exceeded, releases all buffers, returns HTTP 408, and avoids downstream routing.
 *    - If size limit exceeded, releases all buffers, returns HTTP 413, and avoids downstream routing.
 *    - If within limits, updates Content-Length, strips Transfer-Encoding, and forwards body.
 * 5. Exact-once release of all Netty pooled DataBuffers and concurrency permits under all termination
 *    signals (success, error, timeout, cancel, unconsumed body).
 */
@Component
@Slf4j
public class StreamingRequestSizeGlobalFilter implements GlobalFilter, Ordered {

    public static final int FILTER_ORDER = -85;
    public static final long MAX_REQUEST_SIZE_BYTES = GatewayRoutesConfig.MAX_REQUEST_SIZE_BYTES; // 2097152L (2 MiB)

    private final StreamingRequestSizeProperties properties;
    private final InMemoryRateLimiter rateLimiter;
    private final Clock clock;

    private final AtomicInteger globalActiveChunkedRequests = new AtomicInteger(0);
    private final ConcurrentHashMap<String, AtomicInteger> ipActiveChunkedRequests = new ConcurrentHashMap<>();

    @Autowired
    public StreamingRequestSizeGlobalFilter(
            StreamingRequestSizeProperties properties,
            InMemoryRateLimiter rateLimiter,
            Clock clock
    ) {
        this.properties = properties != null ? properties : new StreamingRequestSizeProperties();
        this.rateLimiter = rateLimiter;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public StreamingRequestSizeGlobalFilter() {
        this(new StreamingRequestSizeProperties(), null, Clock.systemUTC());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        HttpHeaders headers = request.getHeaders();
        long contentLength = headers.getContentLength();
        long maxBytes = properties.getMaxSizeBytes();

        // 1. Fast path: Content-Length header is present and exceeds 2 MiB limit upfront
        if (contentLength > maxBytes) {
            log.warn("Request rejected: Content-Length {} exceeds limit of {} bytes", contentLength, maxBytes);
            return ProblemDetailWriter.writeProblemResponse(
                    exchange,
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Payload Too Large",
                    "Request payload exceeds the maximum allowed size of " + maxBytes + " bytes (2 MiB)",
                    "https://api.rideshare.com/errors/payload-too-large"
            );
        }

        // 2. If Content-Length is known, valid (>= 0 and <= maxBytes), and not chunked, pass through directly
        boolean isChunked = contentLength < 0 || "chunked".equalsIgnoreCase(headers.getFirst(HttpHeaders.TRANSFER_ENCODING));
        if (!isChunked) {
            return chain.filter(exchange);
        }

        // 3. Admission control for chunked transfer requests
        String clientIp = resolveClientIp(exchange);

        int currentGlobal = globalActiveChunkedRequests.incrementAndGet();
        if (currentGlobal > properties.getMaxConcurrentRequests()) {
            globalActiveChunkedRequests.decrementAndGet();
            log.warn("Chunked streaming global capacity ({}) saturated. Rejecting request from client IP: {}",
                    properties.getMaxConcurrentRequests(), clientIp);
            return ProblemDetailWriter.writeProblemResponse(
                    exchange,
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too Many Requests",
                    "Gateway chunked streaming capacity is temporarily saturated; please retry shortly",
                    "https://api.rideshare.com/errors/streaming-capacity-exceeded",
                    Map.of("Retry-After", "5")
            );
        }

        AtomicInteger ipCounter = ipActiveChunkedRequests.compute(clientIp, (key, existing) -> {
            if (existing == null) {
                return new AtomicInteger(1);
            }
            existing.incrementAndGet();
            return existing;
        });

        if (ipCounter.get() > properties.getMaxConcurrentPerIp()) {
            releaseIpPermit(clientIp);
            globalActiveChunkedRequests.decrementAndGet();
            log.warn("Chunked streaming per-IP capacity ({}) saturated for client IP: {}. Rejecting request.",
                    properties.getMaxConcurrentPerIp(), clientIp);
            return ProblemDetailWriter.writeProblemResponse(
                    exchange,
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too Many Requests",
                    "Chunked streaming capacity for your client IP is temporarily saturated; please retry shortly",
                    "https://api.rideshare.com/errors/client-streaming-capacity-exceeded",
                    Map.of("Retry-After", "5")
            );
        }

        // Atomic guard ensuring permits are released exactly once
        AtomicBoolean permitReleased = new AtomicBoolean(false);
        Runnable releasePermits = () -> {
            if (permitReleased.compareAndSet(false, true)) {
                globalActiveChunkedRequests.decrementAndGet();
                releaseIpPermit(clientIp);
            }
        };

        // 4. Bounded pre-routing aggregation with total deadline enforcement
        Duration timeout = properties.getTimeout() != null ? properties.getTimeout() : Duration.ofSeconds(10);
        long deadlineEpochMs = clock.millis() + timeout.toMillis();
        BoundedChunkedAggregator aggregator = new BoundedChunkedAggregator(maxBytes, deadlineEpochMs, clock);

        return request.getBody()
                .doOnDiscard(DataBuffer.class, DataBufferUtils::release)
                .doOnCancel(aggregator::releaseAndClear)
                .doOnError(t -> aggregator.releaseAndClear())
                .concatMap(dataBuffer -> {
                    try {
                        aggregator.accept(dataBuffer);
                        return Mono.empty();
                    } catch (Throwable t) {
                        return Mono.error(t);
                    }
                })
                .then()
                .timeout(timeout)
                .then(Mono.defer(() -> {
                    AtomicBoolean consumed = new AtomicBoolean(false);
                    List<DataBuffer> buffers = aggregator.getBuffers();

                    ServerHttpRequest decoratedRequest = new ServerHttpRequestDecorator(request) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return Flux.defer(() -> {
                                if (consumed.compareAndSet(false, true)) {
                                    return Flux.fromIterable(buffers)
                                            .doOnDiscard(DataBuffer.class, DataBufferUtils::release);
                                } else {
                                    return Flux.error(new IllegalStateException("Request body has already been consumed"));
                                }
                            });
                        }

                        @Override
                        public HttpHeaders getHeaders() {
                            HttpHeaders httpHeaders = new HttpHeaders();
                            httpHeaders.putAll(super.getHeaders());
                            httpHeaders.setContentLength(aggregator.getTotalBytes());
                            httpHeaders.remove(HttpHeaders.TRANSFER_ENCODING);
                            return httpHeaders;
                        }
                    };

                    return chain.filter(exchange.mutate().request(decoratedRequest).build())
                            .doFinally(signalType -> {
                                if (consumed.compareAndSet(false, true)) {
                                    aggregator.releaseAndClear();
                                }
                            });
                }))
                .onErrorResume(ex -> {
                    if (ex instanceof PayloadTooLargeException) {
                        log.warn("Chunked request exceeded 2 MiB ceiling at > {} bytes. Aborting before routing and rejecting with 413.", maxBytes);
                        return ProblemDetailWriter.writeProblemResponse(
                                exchange,
                                HttpStatus.PAYLOAD_TOO_LARGE,
                                "Payload Too Large",
                                "Request payload exceeds the maximum allowed size of " + maxBytes + " bytes (2 MiB)",
                                "https://api.rideshare.com/errors/payload-too-large"
                        );
                    } else if (ex instanceof TimeoutException || ex instanceof UploadTimeoutException) {
                        log.warn("Chunked request exceeded total aggregation deadline of {}s. Aborting before routing and rejecting with 408.", timeout.toSeconds());
                        return ProblemDetailWriter.writeProblemResponse(
                                exchange,
                                HttpStatus.REQUEST_TIMEOUT,
                                "Request Timeout",
                                "Request upload exceeded the maximum allowed duration of " + timeout.toSeconds() + " seconds",
                                "https://api.rideshare.com/errors/request-timeout"
                        );
                    }
                    return Mono.error(ex);
                })
                .doFinally(signalType -> releasePermits.run());
    }

    private String resolveClientIp(ServerWebExchange exchange) {
        if (rateLimiter != null) {
            return rateLimiter.extractClientIp(exchange);
        }
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return "unknown";
        }
        return remoteAddress.getAddress().getHostAddress();
    }

    private void releaseIpPermit(String clientIp) {
        ipActiveChunkedRequests.computeIfPresent(clientIp, (key, counter) -> {
            int remaining = counter.decrementAndGet();
            return (remaining <= 0) ? null : counter;
        });
    }

    public int getGlobalActiveChunkedRequests() {
        return globalActiveChunkedRequests.get();
    }

    public int getIpActiveChunkedRequests(String ip) {
        AtomicInteger counter = ipActiveChunkedRequests.get(ip);
        return counter != null ? counter.get() : 0;
    }

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }

    /**
     * Bounded accumulator that retains DataBuffers up to maxBytes and enforces deadlineEpochMs.
     * If maxBytes is exceeded, immediately releases all buffers and throws PayloadTooLargeException.
     * If deadline is exceeded, immediately releases all buffers and throws UploadTimeoutException.
     */
    static class BoundedChunkedAggregator {
        private final long maxBytes;
        private final long deadlineEpochMs;
        private final Clock clock;
        private final List<DataBuffer> buffers = new ArrayList<>();
        private long totalBytes = 0;
        private boolean exceeded = false;
        private boolean timedOut = false;
        private final AtomicBoolean released = new AtomicBoolean(false);

        BoundedChunkedAggregator(long maxBytes, long deadlineEpochMs, Clock clock) {
            this.maxBytes = maxBytes;
            this.deadlineEpochMs = deadlineEpochMs;
            this.clock = clock != null ? clock : Clock.systemUTC();
        }

        BoundedChunkedAggregator(long maxBytes) {
            this(maxBytes, Long.MAX_VALUE, Clock.systemUTC());
        }

        synchronized void accept(DataBuffer buffer) {
            if (released.get() || exceeded || timedOut) {
                DataBufferUtils.release(buffer);
                return;
            }

            if (clock.millis() > deadlineEpochMs) {
                timedOut = true;
                DataBufferUtils.release(buffer);
                releaseAndClear();
                throw new UploadTimeoutException("Chunked upload exceeded total deadline");
            }

            int count = buffer.readableByteCount();
            if (totalBytes + count > maxBytes) {
                exceeded = true;
                DataBufferUtils.release(buffer);
                releaseAndClear();
                throw new PayloadTooLargeException(
                        "Request payload exceeds the maximum allowed size of " + maxBytes + " bytes (2 MiB)"
                );
            }

            totalBytes += count;
            buffers.add(buffer);
        }

        synchronized void releaseAndClear() {
            if (released.compareAndSet(false, true)) {
                for (DataBuffer b : buffers) {
                    try {
                        DataBufferUtils.release(b);
                    } catch (Throwable ignored) {}
                }
                buffers.clear();
            }
        }

        synchronized List<DataBuffer> getBuffers() {
            return buffers;
        }

        synchronized long getTotalBytes() {
            return totalBytes;
        }
    }

    static class PayloadTooLargeException extends RuntimeException {
        PayloadTooLargeException(String message) {
            super(message);
        }
    }

    static class UploadTimeoutException extends RuntimeException {
        UploadTimeoutException(String message) {
            super(message);
        }
    }
}
