package com.manangupta.jobforge.workflow;

import com.manangupta.jobforge.domain.JobStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record WorkflowView(
        UUID id,
        String name,
        String status,
        int totalSteps,
        int succeededSteps,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        List<StepView> steps) {

    public record StepView(
            String stepId,
            UUID jobId,
            String type,
            JobStatus status,
            int attempts,
            int maxAttempts,
            Instant startedAt,
            Instant finishedAt,
            String lastError,
            Map<String, Object> output) {
    }
}
