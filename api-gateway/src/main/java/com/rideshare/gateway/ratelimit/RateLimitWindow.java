package com.rideshare.gateway.ratelimit;

import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.atomic.AtomicInteger;

@Getter
public class RateLimitWindow {

    private final long windowStartEpochMs;
    private final AtomicInteger requestCount;
    @Setter
    private volatile long lastActivityEpochMs;

    public RateLimitWindow(long windowStartEpochMs) {
        this.windowStartEpochMs = windowStartEpochMs;
        this.requestCount = new AtomicInteger(1);
        this.lastActivityEpochMs = windowStartEpochMs;
    }
}
