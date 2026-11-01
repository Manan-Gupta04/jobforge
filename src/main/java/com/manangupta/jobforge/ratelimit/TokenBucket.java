package com.manangupta.jobforge.ratelimit;

/**
 * Classic token bucket: refills at {@code ratePerSecond} up to {@code capacity} tokens.
 * Thread-safe via synchronisation; contention is low because it sits behind the claim loop.
 */
public final class TokenBucket {

    private final double ratePerSecond;
    private final double capacity;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(double ratePerSecond, double capacity) {
        this(ratePerSecond, capacity, System.nanoTime());
    }

    /** Visible for tests: start the bucket's clock at a chosen instant. */
    TokenBucket(double ratePerSecond, double capacity, long nowNanos) {
        if (ratePerSecond <= 0 || capacity <= 0) {
            throw new IllegalArgumentException("rate and capacity must be positive");
        }
        this.ratePerSecond = ratePerSecond;
        this.capacity = capacity;
        this.tokens = capacity;
        this.lastRefillNanos = nowNanos;
    }

    public synchronized boolean tryAcquire() {
        refill(System.nanoTime());
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    /** Visible for tests: advance time explicitly. */
    synchronized boolean tryAcquireAt(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    private void refill(long nowNanos) {
        double elapsedSeconds = (nowNanos - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSeconds > 0) {
            tokens = Math.min(capacity, tokens + elapsedSeconds * ratePerSecond);
            lastRefillNanos = nowNanos;
        }
    }

    public double ratePerSecond() {
        return ratePerSecond;
    }
}
