package com.rideshare.gateway.filter;

import com.rideshare.gateway.config.JwtProperties;
import com.rideshare.gateway.security.JwtValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GatewayAuthenticationFilterTest {

    private GatewayAuthenticationFilter filter;
    private JwtValidator jwtValidator;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret("IsolatedTestingJwtSecretMustBeAtLeast32BytesLongForGatewaySecurity!");
        jwtProperties.setExpirationMs(3600000L);

        jwtValidator = new JwtValidator(jwtProperties);
        filter = new GatewayAuthenticationFilter(jwtValidator);

        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Public registration endpoint is allowed without Authorization header")
    void testPublicRegister_PassesWithoutToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/register").build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain, times(1)).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Public login endpoint is allowed without Authorization header")
    void testPublicLogin_PassesWithoutToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login").build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain, times(1)).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Actuator health endpoint is allowed without Authorization header")
    void testActuatorHealth_PassesWithoutToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health").build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain, times(1)).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Actuator info endpoint is allowed without Authorization header")
    void testActuatorInfo_PassesWithoutToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/info").build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain, times(1)).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Restricted actuator endpoints (e.g. /actuator/env) are blocked with 404 Not Found")
    void testActuatorSensitiveEndpoints_BlockedWith404() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/env").build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Unregistered endpoint under /api/v1/auth is rejected with 401 when unauthenticated")
    void testUnregisteredAuthPath_RejectedWith401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/custom-admin-create").build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Protected endpoint without Authorization header returns 401 Unauthorized")
    void testProtectedRouteWithoutToken_Returns401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/me").build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Protected endpoint with malformed or invalid token returns 401 Unauthorized")
    void testProtectedRouteWithInvalidToken_Returns401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.jwt.token")
                        .build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Protected endpoint with valid end-user token (ROLE_PASSENGER) passes through")
    void testProtectedRouteWithValidPassengerToken_Passes() {
        String token = jwtValidator.generateToken(101L, "passenger@rideshare.com", "ROLE_PASSENGER");

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain, times(1)).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Protected endpoint with valid end-user token (ROLE_DRIVER) passes through")
    void testProtectedRouteWithValidDriverToken_Passes() {
        String token = jwtValidator.generateToken(202L, "driver@rideshare.com", "ROLE_DRIVER");

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/rides/driver/active")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain, times(1)).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Token with ROLE_INTERNAL_SERVICE is rejected with 403 Forbidden on public gateway")
    void testRoleInternalServiceToken_RejectedWith403() {
        String token = jwtValidator.generateToken(999L, "internal-service", "ROLE_INTERNAL_SERVICE");

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/rides/1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build()
        );

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
