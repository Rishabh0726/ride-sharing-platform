package com.rideshare.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRoutesConfig {

    @Value("${user-service.url:${USER_SERVICE_URL:http://localhost:8081}}")
    private String userServiceUrl;

    @Value("${ride-service.url:${RIDE_SERVICE_URL:http://localhost:8082}}")
    private String rideServiceUrl;

    @Value("${pricing-service.url:${PRICING_SERVICE_URL:http://localhost:8083}}")
    private String pricingServiceUrl;

    @Value("${notification-service.url:${NOTIFICATION_SERVICE_URL:http://localhost:8084}}")
    private String notificationServiceUrl;

    public static final long MAX_REQUEST_SIZE_BYTES = 2097152L;

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                // User Service Routes (Auth, User Profiles, Driver Availability & Discovery)
                .route("user-service-auth", r -> r.path("/api/v1/auth/**")
                        .filters(f -> f.setRequestSize(MAX_REQUEST_SIZE_BYTES))
                        .uri(userServiceUrl))
                .route("user-service-users", r -> r.path("/api/v1/users/**")
                        .filters(f -> f.setRequestSize(MAX_REQUEST_SIZE_BYTES))
                        .uri(userServiceUrl))
                .route("user-service-drivers", r -> r.path("/api/v1/drivers/**")
                        .filters(f -> f.setRequestSize(MAX_REQUEST_SIZE_BYTES))
                        .uri(userServiceUrl))

                // Ride Service Routes (Ride Lifecycle State Machine)
                .route("ride-service", r -> r.path("/api/v1/rides/**")
                        .filters(f -> f.setRequestSize(MAX_REQUEST_SIZE_BYTES))
                        .uri(rideServiceUrl))

                // Pricing Service Routes (Fare Estimation Quotes)
                .route("pricing-service", r -> r.path("/api/v1/pricing/**")
                        .filters(f -> f.setRequestSize(MAX_REQUEST_SIZE_BYTES))
                        .uri(pricingServiceUrl))

                // Notification Service Routes (In-App Notification Inbox)
                .route("notification-service", r -> r.path("/api/v1/notifications/**")
                        .filters(f -> f.setRequestSize(MAX_REQUEST_SIZE_BYTES))
                        .uri(notificationServiceUrl))
                .build();
    }
}
