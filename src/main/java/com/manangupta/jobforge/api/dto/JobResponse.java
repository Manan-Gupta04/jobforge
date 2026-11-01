package com.manangupta.jobforge.api.dto;

import com.manangupta.jobforge.domain.Job;
import com.manangupta.jobforge.domain.JobStatus;
import com.manangupta.jobforge.service.JsonCodec;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record JobResponse(
        UUID id,
        String type,
        JobStatus status,
        int priority,
        Map<String, Object> payload,
        Map<String, Object> result,
        String lastError,
        int attempts,
        int maxAttempts,
        long timeoutMs,
        Instant runAt,
        String cron,
        Set<UUID> dependsOn,
        String idempotencyKey,
        String callbackUrl,
        UUID workflowId,
        String workflowName,
        String stepId,
        String leaseOwner,
        Instant leaseUntil,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt) {

    public static JobResponse from(Job j, JsonCodec json) {
        return new JobResponse(
                j.getId(), j.getType(), j.getStatus(), j.getPriority(),
                json.read(j.getPayload()), json.read(j.getResult()), j.getLastError(),
                j.getAttempts(), j.getMaxAttempts(), j.getTimeoutMs(), j.getRunAt(), j.getCronExpression(),
                j.getDependsOn(), j.getIdempotencyKey(), j.getCallbackUrl(),
                j.getWorkflowId(), j.getWorkflowName(), j.getStepId(),
                j.getLeaseOwner(), j.getLeaseUntil(),
                j.getCreatedAt(), j.getUpdatedAt(), j.getStartedAt(), j.getFinishedAt());
    }
}
