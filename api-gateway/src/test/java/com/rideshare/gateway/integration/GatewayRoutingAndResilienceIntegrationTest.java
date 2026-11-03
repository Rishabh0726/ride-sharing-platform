package com.rideshare.gateway.integration;

import com.rideshare.gateway.security.JwtValidator;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayRoutingAndResilienceIntegrationTest {

    private static MockWebServer mockBackEnd;

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @Autowired
    private JwtValidator jwtValidator;

    @BeforeAll
    static void setUpAll() throws IOException {
        mockBackEnd = new MockWebServer();
        mockBackEnd.start();
    }

    @AfterAll
    static void tearDownAll() throws IOException {
        if (mockBackEnd != null) {
            mockBackEnd.shutdown();
        }
    }

    @BeforeEach
    void setUp() {
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String mockUrl = "http://127.0.0.1:" + mockBackEnd.getPort();
        registry.add("user-service.url", () -> mockUrl);
        registry.add("ride-service.url", () -> mockUrl);
        registry.add("pricing-service.url", () -> mockUrl);
        registry.add("notification-service.url", () -> mockUrl);
        registry.add("USER_SERVICE_URL", () -> mockUrl);
        registry.add("RIDE_SERVICE_URL", () -> mockUrl);
        registry.add("PRICING_SERVICE_URL", () -> mockUrl);
        registry.add("NOTIFICATION_SERVICE_URL", () -> mockUrl);
    }

    @Test
    @DisplayName("Public auth endpoint is proxied to downstream and returns 201 Created")
    void testPublicAuthRegistration_ProxiesSuccessfully() throws InterruptedException {
        mockBackEnd.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"token\":\"dummy-jwt\",\"user\":{\"id\":101,\"email\":\"test@rideshare.com\"}}"));

        webTestClient.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"test@rideshare.com\",\"password\":\"SecurePass123!\",\"name\":\"Test User\",\"role\":\"PASSENGER\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.token").isEqualTo("dummy-jwt");

        RecordedRequest recordedRequest = mockBackEnd.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
        assertThat(recordedRequest.getPath()).isEqualTo("/api/v1/auth/register");
    }

    @Test
    @DisplayName("Protected route with valid JWT is proxied to downstream and returns 200 OK")
    void testProtectedEndpoint_WithValidToken_ProxiesSuccessfully() throws InterruptedException {
        String token = jwtValidator.generateToken(101L, "passenger@rideshare.com", "ROLE_PASSENGER");

        mockBackEnd.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":101,\"email\":\"passenger@rideshare.com\",\"name\":\"Passenger One\"}"));

        webTestClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.email").isEqualTo("passenger@rideshare.com");

        RecordedRequest recordedRequest = mockBackEnd.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
        assertThat(recordedRequest.getPath()).isEqualTo("/api/v1/users/me");
        assertThat(recordedRequest.getHeader("Authorization")).isEqualTo("Bearer " + token);
    }

    @Test
    @DisplayName("Untrusted caller-supplied X-User-* headers are stripped before reaching downstream")
    void testUntrustedHeaderStripping_DownstreamDoesNotReceiveSpoofedHeaders() throws InterruptedException {
        String token = jwtValidator.generateToken(101L, "passenger@rideshare.com", "ROLE_PASSENGER");

        mockBackEnd.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("[]"));

        webTestClient.get()
                .uri("/api/v1/notifications")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-User-Id", "999")
                .header("X-User-Role", "ROLE_ADMIN")
                .header("X-Service-Name", "ride-service")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recordedRequest = mockBackEnd.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
        assertThat(recordedRequest.getHeader("X-User-Id")).isNull();
        assertThat(recordedRequest.getHeader("X-User-Role")).isNull();
        assertThat(recordedRequest.getHeader("X-Service-Name")).isNull();
        assertThat(recordedRequest.getHeader("Authorization")).isEqualTo("Bearer " + token);
    }

    @Test
    @DisplayName("Downstream 4xx errors and ProblemDetail bodies are preserved and forwarded untouched")
    void testDownstream4xxPreservation_ReturnsDownstreamStatusAndBody() throws InterruptedException {
        String token = jwtValidator.generateToken(101L, "passenger@rideshare.com", "ROLE_PASSENGER");

        String downstreamProblem = "{\"type\":\"https://api.rideshare.com/errors/conflict\",\"title\":\"Conflict\",\"status\":409,\"detail\":\"Ride cannot be cancelled in status COMPLETED\"}";

        mockBackEnd.enqueue(new MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", "application/problem+json")
                .setBody(downstreamProblem));

        webTestClient.post()
                .uri("/api/v1/rides/55/cancel")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"Changed mind\"}")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.status").isEqualTo(409)
                .jsonPath("$.title").isEqualTo("Conflict")
                .jsonPath("$.detail").isEqualTo("Ride cannot be cancelled in status COMPLETED");

        RecordedRequest recordedRequest = mockBackEnd.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
    }

    @Test
    @DisplayName("Payload within 2 MiB limit passes through to downstream")
    void testPayloadUnderLimit_PassesSuccessfully() throws InterruptedException {
        String token = jwtValidator.generateToken(101L, "passenger@rideshare.com", "ROLE_PASSENGER");

        mockBackEnd.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"OK\"}"));

        // 100 KiB payload
        String payload = "{\"data\":\"" + "a".repeat(100 * 1024) + "\"}";

        webTestClient.post()
                .uri("/api/v1/rides")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recordedRequest = mockBackEnd.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
    }

    @Test
    @DisplayName("Payload exceeding 2 MiB limit is rejected with 413 Payload Too Large without reaching downstream")
    void testPayloadOver2MiB_RejectedWith413() {
        String token = jwtValidator.generateToken(101L, "passenger@rideshare.com", "ROLE_PASSENGER");
        int countBefore = mockBackEnd.getRequestCount();

        // 2.2 MiB payload
        byte[] largeBytes = new byte[2 * 1024 * 1024 + 200 * 1024];

        webTestClient.post()
                .uri("/api/v1/rides")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(largeBytes)
                .exchange()
                .expectStatus().isEqualTo(413);

        // Verify downstream received zero additional requests
        assertThat(mockBackEnd.getRequestCount()).isEqualTo(countBefore);
    }

    @Test
    @DisplayName("Payload exactly at 2 MiB limit with Content-Length passes through successfully")
    void testPayloadExactly2MiB_ContentLength_PassesSuccessfully() throws InterruptedException {
        String token = jwtValidator.generateToken(101L, "passenger@rideshare.com", "ROLE_PASSENGER");

        mockBackEnd.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"OK\"}"));

        byte[] exact2MiB = new byte[2 * 1024 * 1024]; // Exactly 2097152 bytes

        webTestClient.post()
                .uri("/api/v1/rides")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(exact2MiB)
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recordedRequest = mockBackEnd.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
    }

    @Test
    @DisplayName("Chunked payload exceeding 2 MiB limit is rejected with 413 Payload Too Large")
    void testPayloadOver2MiB_ChunkedTransfer_RejectedWith413() throws InterruptedException {
        String token = jwtValidator.generateToken(101L, "passenger@rideshare.com", "ROLE_PASSENGER");
        int countBefore = mockBackEnd.getRequestCount();

        // 25 chunks of 100 KiB = 2.5 MiB (exceeds 2 MiB limit)
        byte[] chunk = new byte[100 * 1024];
        Flux<DataBuffer> chunkFlux = Flux.range(0, 25)
                .map(i -> DefaultDataBufferFactory.sharedInstance.wrap(chunk));

        webTestClient.post()
                .uri("/api/v1/rides")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(BodyInserters.fromDataBuffers(chunkFlux))
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(413)
                .jsonPath("$.title").isEqualTo("Payload Too Large");

        // Assert with absolute certainty that downstream was NEVER contacted
        assertThat(mockBackEnd.getRequestCount()).isEqualTo(countBefore);
        RecordedRequest truncatedRequest = mockBackEnd.takeRequest(200, TimeUnit.MILLISECONDS);
        assertThat(truncatedRequest).isNull();
    }

    @Test
    @DisplayName("Chunked payload below 2 MiB limit streams through to downstream successfully")
    void testPayloadUnder2MiB_ChunkedTransfer_PassesSuccessfully() throws InterruptedException {
        String token = jwtValidator.generateToken(101L, "passenger@rideshare.com", "ROLE_PASSENGER");

        mockBackEnd.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"OK\"}"));

        // 5 chunks of 100 KiB = 500 KiB (< 2 MiB)
        byte[] chunk = new byte[100 * 1024];
        Flux<DataBuffer> chunkFlux = Flux.range(0, 5)
                .map(i -> DefaultDataBufferFactory.sharedInstance.wrap(chunk));

        webTestClient.post()
                .uri("/api/v1/rides")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(BodyInserters.fromDataBuffers(chunkFlux))
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recordedRequest = mockBackEnd.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
    }

    @Test
    @DisplayName("Chunked payload exactly at 2 MiB limit streams through successfully")
    void testPayloadExactly2MiB_ChunkedTransfer_PassesSuccessfully() throws InterruptedException {
        String token = jwtValidator.generateToken(101L, "passenger@rideshare.com", "ROLE_PASSENGER");

        mockBackEnd.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"status\":\"OK\"}"));

        // 32 chunks of 64 KiB = 2,097,152 bytes (exactly 2 MiB)
        byte[] chunk = new byte[64 * 1024];
        Flux<DataBuffer> chunkFlux = Flux.range(0, 32)
                .map(i -> DefaultDataBufferFactory.sharedInstance.wrap(chunk));

        webTestClient.post()
                .uri("/api/v1/rides")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(BodyInserters.fromDataBuffers(chunkFlux))
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recordedRequest = mockBackEnd.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
    }

    @Test
    @DisplayName("Internal endpoint call is blocked at gateway with 403 Forbidden without reaching downstream")
    void testInternalEndpoint_BlockedWith403() {
        int countBefore = mockBackEnd.getRequestCount();

        webTestClient.post()
                .uri("/api/v1/drivers/42/reserve")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"rideId\":100}")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.title").isEqualTo("Forbidden");

        assertThat(mockBackEnd.getRequestCount()).isEqualTo(countBefore);
    }

    @Test
    @DisplayName("Focused Test: Verify downstream invocation and side-effect behavior for oversized chunked request")
    void testOversizedChunkedPayload_DownstreamInvocationAndSideEffectCheck() throws InterruptedException {
        String token = jwtValidator.generateToken(101L, "driver@rideshare.com", "ROLE_DRIVER");
        int countBefore = mockBackEnd.getRequestCount();

        AtomicBoolean downstreamInvoked = new AtomicBoolean(false);
        AtomicBoolean sideEffectProduced = new AtomicBoolean(false);
        AtomicInteger invocationCount = new AtomicInteger(0);

        mockBackEnd.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                downstreamInvoked.set(true);
                invocationCount.incrementAndGet();
                sideEffectProduced.set(true);
                return new MockResponse().setResponseCode(200).setBody("{\"status\":\"PROCESSED\"}");
            }
        });

        // 25 chunks of 100 KiB = 2.5 MiB (> 2 MiB limit)
        byte[] chunk = new byte[100 * 1024];
        Flux<DataBuffer> chunkFlux = Flux.range(0, 25)
                .map(i -> DefaultDataBufferFactory.sharedInstance.wrap(chunk));

        try {
            webTestClient.post()
                    .uri("/api/v1/rides/1/arrive")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(BodyInserters.fromDataBuffers(chunkFlux))
                    .exchange()
                    .expectStatus().isEqualTo(413)
                    .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.status").isEqualTo(413)
                    .jsonPath("$.title").isEqualTo("Payload Too Large");

            // Assert with absolute certainty that downstream was NEVER contacted
            assertThat(downstreamInvoked.get()).isFalse();
            assertThat(sideEffectProduced.get()).isFalse();
            assertThat(invocationCount.get()).isZero();
            assertThat(mockBackEnd.getRequestCount()).isEqualTo(countBefore);

            // Verify no request was captured by mockBackEnd
            RecordedRequest recorded = mockBackEnd.takeRequest(200, TimeUnit.MILLISECONDS);
            assertThat(recorded).isNull();
        } finally {
            mockBackEnd.setDispatcher(new QueueDispatcher());
        }
    }
}
