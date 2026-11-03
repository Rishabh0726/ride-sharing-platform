package com.rideshare.gateway.ratelimit;

import lombok.Getter;

@Getter
public class RateLimitDecision {

    private final boolean allowed;
    private final int remainingRequests;
    private final long retryAfterSeconds;
    private final boolean capacityExhausted;

    private RateLimitDecision(boolean allowed, int remainingRequests, long retryAfterSeconds, boolean capacityExhausted) {
        this.allowed = allowed;
        this.remainingRequests = remainingRequests;
        this.retryAfterSeconds = retryAfterSeconds;
        this.capacityExhausted = capacityExhausted;
    }

    public static RateLimitDecision allowed(int remainingRequests) {
        return new RateLimitDecision(true, remainingRequests, 0L, false);
    }

    public static RateLimitDecision denied(long retryAfterSeconds) {
        return new RateLimitDecision(false, 0, retryAfterSeconds, false);
    }

    public static RateLimitDecision capacityExhausted(long retryAfterSeconds) {
        return new RateLimitDecision(false, 0, retryAfterSeconds, true);
    }
}
