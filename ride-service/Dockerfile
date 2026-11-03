# syntax=docker/dockerfile:1

# Stage 1: Build
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /build

# Install curl for Maven wrapper binary resolution
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates && \
    rm -rf /var/lib/apt/lists/*

# Copy Maven wrapper and POM hierarchy
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY user-service/pom.xml user-service/pom.xml
COPY pricing-service/pom.xml pricing-service/pom.xml
COPY ride-service/pom.xml ride-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY system-integration-tests/pom.xml system-integration-tests/pom.xml

RUN chmod +x ./mvnw

# Copy service source code
COPY ride-service/src ride-service/src

# Build ride-service artifact
RUN ./mvnw clean package -DskipTests -pl ride-service -am

# Extract layered JAR using Spring Boot tools
RUN java -Djarmode=tools -jar ride-service/target/ride-service-*.jar extract --layers --launcher --destination ride-service/target/extracted

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-jammy AS runtime

# Install curl for container health checks
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# Run as non-root user
RUN groupadd -r appgroup && useradd -r -g appgroup -d /app -s /sbin/nologin appuser

WORKDIR /app

# Copy extracted layers with non-root ownership
COPY --from=builder --chown=appuser:appgroup /build/ride-service/target/extracted/dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /build/ride-service/target/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=appuser:appgroup /build/ride-service/target/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=appuser:appgroup /build/ride-service/target/extracted/application/ ./

USER appuser:appgroup

EXPOSE 8082

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
