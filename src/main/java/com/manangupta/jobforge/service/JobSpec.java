package com.manangupta.jobforge.service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Validated, defaulted description of a job to create. Built by the API layer. */
public record JobSpec(
        String type,
        Map<String, Object> payload,
        Integer priority,
        Instant runAt,
        String cron,
        Integer maxAttempts,
        Long timeoutMs,
        Set<UUID> dependsOn,
        String idempotencyKey,
        String callbackUrl) {

    public static JobSpec of(String type, Map<String, Object> payload) {
        return new JobSpec(type, payload, null, null, null, null, null, null, null, null);
    }

    public JobSpec withIdempotencyKey(String key) {
        return new JobSpec(type, payload, priority, runAt, cron, maxAttempts, timeoutMs, dependsOn, key, callbackUrl);
    }

    public JobSpec withDependsOn(Set<UUID> deps) {
        return new JobSpec(type, payload, priority, runAt, cron, maxAttempts, timeoutMs, deps, idempotencyKey, callbackUrl);
    }

    public JobSpec withMaxAttempts(int attempts) {
        return new JobSpec(type, payload, priority, runAt, cron, attempts, timeoutMs, dependsOn, idempotencyKey, callbackUrl);
    }

    public JobSpec withTimeoutMs(long timeout) {
        return new JobSpec(type, payload, priority, runAt, cron, maxAttempts, timeout, dependsOn, idempotencyKey, callbackUrl);
    }

    public JobSpec withRunAt(Instant at) {
        return new JobSpec(type, payload, priority, at, cron, maxAttempts, timeoutMs, dependsOn, idempotencyKey, callbackUrl);
    }

    public JobSpec withCron(String cronExpression) {
        return new JobSpec(type, payload, priority, runAt, cronExpression, maxAttempts, timeoutMs, dependsOn, idempotencyKey, callbackUrl);
    }

    public JobSpec withPriority(int p) {
        return new JobSpec(type, payload, p, runAt, cron, maxAttempts, timeoutMs, dependsOn, idempotencyKey, callbackUrl);
    }

    public JobSpec withCallbackUrl(String url) {
        return new JobSpec(type, payload, priority, runAt, cron, maxAttempts, timeoutMs, dependsOn, idempotencyKey, url);
    }
}
