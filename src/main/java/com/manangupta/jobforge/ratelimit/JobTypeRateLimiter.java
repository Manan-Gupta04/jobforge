package com.manangupta.jobforge.ratelimit;

import com.manangupta.jobforge.config.SchedulerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-node, per-job-type rate limits configured as {@code jobforge.rate-limits.<type>=<jobs/sec>}.
 * A type with no limit is unthrottled. Used by the claim loop: a job whose type is over its limit
 * is simply not claimed this cycle and stays in the queue, so limits never lose work.
 *
 * <p>Typical use: cap {@code llm} jobs to stay under a provider's requests-per-minute quota.
 */
@Component
public class JobTypeRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(JobTypeRateLimiter.class);

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public JobTypeRateLimiter(SchedulerProperties props) {
        props.getRateLimits().forEach((type, rate) -> {
            if (rate != null && rate > 0) {
                // Burst capacity of one second's worth (at least 1) keeps the limit tight but smooth.
                buckets.put(type, new TokenBucket(rate, Math.max(1.0, rate)));
                log.info("Rate limit for job type '{}': {} jobs/s per node", type, rate);
            }
        });
    }

    /** True if a job of this type may start now; consumes a token when it does. */
    public boolean tryAcquire(String type) {
        TokenBucket bucket = buckets.get(type);
        return bucket == null || bucket.tryAcquire();
    }

    public Map<String, Double> limits() {
        Map<String, Double> out = new java.util.TreeMap<>();
        buckets.forEach((k, v) -> out.put(k, v.ratePerSecond()));
        return out;
    }
}
