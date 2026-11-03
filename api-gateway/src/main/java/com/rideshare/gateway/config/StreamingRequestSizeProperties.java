package com.rideshare.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "gateway.streaming")
@Getter
@Setter
public class StreamingRequestSizeProperties {

    /**
     * Maximum payload size in bytes (default 2 MiB = 2,097,152 bytes).
     */
    private long maxSizeBytes = GatewayRoutesConfig.MAX_REQUEST_SIZE_BYTES; // 2097152L

    /**
     * Maximum total concurrent chunked aggregations across the entire gateway.
     */
    private int maxConcurrentRequests = 50;

    /**
     * Maximum concurrent chunked aggregations per validated client IP.
     */
    private int maxConcurrentPerIp = 10;

    /**
     * Maximum total aggregation duration for a chunked upload.
     */
    private Duration timeout = Duration.ofSeconds(10);
}
