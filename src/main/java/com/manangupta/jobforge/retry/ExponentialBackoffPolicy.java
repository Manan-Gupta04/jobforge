package com.manangupta.jobforge.retry;

import com.manangupta.jobforge.config.SchedulerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code base * 2^(attempt-1)} capped at {@code max}, with ±20% jitter so that a burst of jobs
 * failing together does not retry in lock-step (the "thundering herd" problem).
 */
@Component
public class ExponentialBackoffPolicy implements BackoffPolicy {

    private final long baseMs;
    private final long maxMs;
    private final double jitter;

    @Autowired
    public ExponentialBackoffPolicy(SchedulerProperties props) {
        this(props.getRetryBaseDelayMs(), props.getRetryMaxDelayMs(), 0.2);
    }

    public ExponentialBackoffPolicy(long baseMs, long maxMs, double jitter) {
        if (baseMs <= 0 || maxMs <= 0) {
            throw new IllegalArgumentException("delays must be positive");
        }
        if (jitter < 0 || jitter >= 1) {
            throw new IllegalArgumentException("jitter must be in [0,1)");
        }
        this.baseMs = baseMs;
        this.maxMs = maxMs;
        this.jitter = jitter;
    }

    @Override
    public Duration delayFor(int attempt) {
        int exponent = Math.max(0, Math.min(attempt - 1, 30));
        long raw = Math.min(maxMs, baseMs * (1L << exponent));
        double factor = 1.0 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * jitter;
        long withJitter = Math.round(raw * factor);
        return Duration.ofMillis(Math.max(1, Math.min(maxMs, withJitter)));
    }
}
