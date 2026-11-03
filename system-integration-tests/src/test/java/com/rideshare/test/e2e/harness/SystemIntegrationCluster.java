package com.rideshare.test.e2e.harness;

import com.rideshare.gateway.ApiGatewayApplication;
import com.rideshare.notification_service.NotificationServiceApplication;
import com.rideshare.pricing_service.PricingServiceApplication;
import com.rideshare.pricing_service.entity.PricingConfiguration;
import com.rideshare.pricing_service.repository.PricingConfigurationRepository;
import com.rideshare.ride_service.RideServiceApplication;
import com.rideshare.user_service.UserServiceApplication;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Multi-service test harness that starts and coordinates all five microservices and the reactive API Gateway
 * on dynamic ephemeral ports with isolated in-memory H2 databases and zero local MySQL 8 contact.
 */
@Slf4j
public class SystemIntegrationCluster {

    public static final String SHARED_JWT_SECRET = "SystemIntegrationTestSecretMustBeMinimum32BytesLongForSecurity!";

    @Getter
    private int gatewayPort;
    @Getter
    private int userPort;
    @Getter
    private int pricingPort;
    @Getter
    private int notifPort;
    @Getter
    private int ridePort;

    @Getter
    private WebTestClient webTestClient;

    private ConfigurableApplicationContext userCtx;
    private ConfigurableApplicationContext pricingCtx;
    private ConfigurableApplicationContext notifCtx;
    private ConfigurableApplicationContext rideCtx;
    private ConfigurableApplicationContext gatewayCtx;

    public void start() {
        log.info("Starting SystemIntegrationCluster with 5 microservices on dynamic ports...");

        // Set system properties for externalized placeholders
        System.setProperty("JWT_SECRET", SHARED_JWT_SECRET);
        System.setProperty("DB_USERNAME", "sa");
        System.setProperty("DB_PASSWORD", "");
        System.setProperty("SPRING_DATASOURCE_USERNAME", "sa");
        System.setProperty("SPRING_DATASOURCE_PASSWORD", "");

        // 1. User Service (Servlet WebMVC)
        userCtx = new SpringApplicationBuilder(UserServiceApplication.class)
                .profiles("test")
                .web(WebApplicationType.SERVLET)
                .run(
                        "--server.port=0",
                        "--spring.cloud.gateway.enabled=false",
                        "--spring.cloud.gateway.server.webflux.enabled=false",
                        "--spring.datasource.url=jdbc:h2:mem:e2e_user_db;DB_CLOSE_DELAY=-1;MODE=MySQL",
                        "--spring.datasource.driver-class-name=org.h2.Driver",
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--spring.jpa.hibernate.ddl-auto=create-drop",
                        "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                        "--spring.flyway.enabled=false",
                        "--application.jwt.secret=" + SHARED_JWT_SECRET,
                        "--jwt.secret=" + SHARED_JWT_SECRET,
                        "--JWT_SECRET=" + SHARED_JWT_SECRET,
                        "--spring.main.banner-mode=off"
                );
        userPort = resolvePort(userCtx);
        log.info("Started user-service on dynamic port {}", userPort);

        // 2. Pricing Service (Servlet WebMVC)
        pricingCtx = new SpringApplicationBuilder(PricingServiceApplication.class)
                .profiles("test")
                .web(WebApplicationType.SERVLET)
                .run(
                        "--server.port=0",
                        "--spring.cloud.gateway.enabled=false",
                        "--spring.cloud.gateway.server.webflux.enabled=false",
                        "--spring.datasource.url=jdbc:h2:mem:e2e_pricing_db;DB_CLOSE_DELAY=-1;MODE=MySQL",
                        "--SPRING_DATASOURCE_URL=jdbc:h2:mem:e2e_pricing_db;DB_CLOSE_DELAY=-1;MODE=MySQL",
                        "--spring.datasource.driver-class-name=org.h2.Driver",
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--SPRING_DATASOURCE_USERNAME=sa",
                        "--SPRING_DATASOURCE_PASSWORD=",
                        "--spring.jpa.hibernate.ddl-auto=create-drop",
                        "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                        "--spring.flyway.enabled=false",
                        "--jwt.secret=" + SHARED_JWT_SECRET,
                        "--JWT_SECRET=" + SHARED_JWT_SECRET,
                        "--spring.main.banner-mode=off"
                );
        pricingPort = resolvePort(pricingCtx);
        log.info("Started pricing-service on dynamic port {}", pricingPort);

        // Seed default SEDAN pricing configuration
        PricingConfigurationRepository pricingRepo = pricingCtx.getBean(PricingConfigurationRepository.class);
        if (pricingRepo.findByVehicleTypeIgnoreCaseAndActiveTrue("SEDAN").isEmpty()) {
            pricingRepo.save(PricingConfiguration.builder()
                    .vehicleType("SEDAN")
                    .baseFare(new BigDecimal("50.00"))
                    .ratePerKm(new BigDecimal("15.00"))
                    .bookingFee(new BigDecimal("15.00"))
                    .currency("INR")
                    .active(true)
                    .build());
        }

        // 3. Notification Service (Servlet WebMVC)
        notifCtx = new SpringApplicationBuilder(NotificationServiceApplication.class)
                .profiles("test")
                .web(WebApplicationType.SERVLET)
                .run(
                        "--server.port=0",
                        "--spring.cloud.gateway.enabled=false",
                        "--spring.cloud.gateway.server.webflux.enabled=false",
                        "--spring.datasource.url=jdbc:h2:mem:e2e_notif_db;DB_CLOSE_DELAY=-1;MODE=MySQL",
                        "--SPRING_DATASOURCE_URL=jdbc:h2:mem:e2e_notif_db;DB_CLOSE_DELAY=-1;MODE=MySQL",
                        "--spring.datasource.driver-class-name=org.h2.Driver",
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--SPRING_DATASOURCE_USERNAME=sa",
                        "--SPRING_DATASOURCE_PASSWORD=",
                        "--spring.jpa.hibernate.ddl-auto=create-drop",
                        "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                        "--spring.flyway.enabled=false",
                        "--jwt.secret=" + SHARED_JWT_SECRET,
                        "--JWT_SECRET=" + SHARED_JWT_SECRET,
                        "--spring.main.banner-mode=off"
                );
        notifPort = resolvePort(notifCtx);
        log.info("Started notification-service on dynamic port {}", notifPort);

        // 4. Ride Service (Servlet WebMVC - Feign client URLs bound to dynamic ports of dependencies)
        rideCtx = new SpringApplicationBuilder(RideServiceApplication.class)
                .profiles("test")
                .web(WebApplicationType.SERVLET)
                .run(
                        "--server.port=0",
                        "--spring.cloud.gateway.enabled=false",
                        "--spring.cloud.gateway.server.webflux.enabled=false",
                        "--spring.datasource.url=jdbc:h2:mem:e2e_ride_db;DB_CLOSE_DELAY=-1;MODE=MySQL",
                        "--SPRING_DATASOURCE_URL=jdbc:h2:mem:e2e_ride_db;DB_CLOSE_DELAY=-1;MODE=MySQL",
                        "--spring.datasource.driver-class-name=org.h2.Driver",
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--SPRING_DATASOURCE_USERNAME=sa",
                        "--SPRING_DATASOURCE_PASSWORD=",
                        "--spring.jpa.hibernate.ddl-auto=create-drop",
                        "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                        "--spring.flyway.enabled=false",
                        "--user-service.url=http://127.0.0.1:" + userPort,
                        "--USER_SERVICE_URL=http://127.0.0.1:" + userPort,
                        "--pricing-service.url=http://127.0.0.1:" + pricingPort,
                        "--PRICING_SERVICE_URL=http://127.0.0.1:" + pricingPort,
                        "--notification-service.url=http://127.0.0.1:" + notifPort,
                        "--NOTIFICATION_SERVICE_URL=http://127.0.0.1:" + notifPort,
                        "--jwt.secret=" + SHARED_JWT_SECRET,
                        "--JWT_SECRET=" + SHARED_JWT_SECRET,
                        "--spring.main.banner-mode=off"
                );
        ridePort = resolvePort(rideCtx);
        log.info("Started ride-service on dynamic port {}", ridePort);

        // 5. API Gateway (Reactive WebFlux - Reverse proxy routing bound to dynamic ports of all 4 services)
        // Exclude DataSource, JPA/Hibernate, and Spring Security auto-configurations that are present on the composite test classpath
        gatewayCtx = new SpringApplicationBuilder(ApiGatewayApplication.class)
                .profiles("test")
                .web(WebApplicationType.REACTIVE)
                .run(
                        "--server.port=0",
                        "--spring.cloud.gateway.enabled=true",
                        "--spring.cloud.gateway.server.webflux.enabled=true",
                        "--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration,org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration,org.springframework.boot.security.autoconfigure.actuate.web.reactive.ReactiveManagementWebSecurityAutoConfiguration,org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
                        "--user-service.url=http://127.0.0.1:" + userPort,
                        "--USER_SERVICE_URL=http://127.0.0.1:" + userPort,
                        "--ride-service.url=http://127.0.0.1:" + ridePort,
                        "--RIDE_SERVICE_URL=http://127.0.0.1:" + ridePort,
                        "--pricing-service.url=http://127.0.0.1:" + pricingPort,
                        "--PRICING_SERVICE_URL=http://127.0.0.1:" + pricingPort,
                        "--notification-service.url=http://127.0.0.1:" + notifPort,
                        "--NOTIFICATION_SERVICE_URL=http://127.0.0.1:" + notifPort,
                        "--jwt.secret=" + SHARED_JWT_SECRET,
                        "--JWT_SECRET=" + SHARED_JWT_SECRET,
                        "--spring.main.banner-mode=off"
                );
        gatewayPort = resolvePort(gatewayCtx);
        log.info("Started api-gateway on dynamic port {}", gatewayPort);

        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + gatewayPort)
                .responseTimeout(Duration.ofSeconds(15))
                .build();
    }

    public void stop() {
        log.info("Stopping SystemIntegrationCluster...");
        safeClose(gatewayCtx, "api-gateway");
        safeClose(rideCtx, "ride-service");
        safeClose(notifCtx, "notification-service");
        safeClose(pricingCtx, "pricing-service");
        safeClose(userCtx, "user-service");
    }

    private int resolvePort(ConfigurableApplicationContext ctx) {
        Integer port = ctx.getEnvironment().getProperty("local.server.port", Integer.class);
        if (port != null && port > 0) {
            return port;
        }
        try {
            java.lang.reflect.Method getWebServerMethod = ctx.getClass().getMethod("getWebServer");
            Object webServer = getWebServerMethod.invoke(ctx);
            java.lang.reflect.Method getPortMethod = webServer.getClass().getMethod("getPort");
            return (Integer) getPortMethod.invoke(webServer);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to resolve dynamic server port for context: " + ctx, ex);
        }
    }

    private void safeClose(ConfigurableApplicationContext ctx, String serviceName) {
        if (ctx != null && ctx.isActive()) {
            try {
                ctx.close();
                log.info("Closed context for {}", serviceName);
            } catch (Exception ex) {
                log.warn("Error closing context for {}: {}", serviceName, ex.getMessage());
            }
        }
    }
}
