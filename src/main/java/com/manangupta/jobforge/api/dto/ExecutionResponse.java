package com.manangupta.jobforge.api.dto;

import com.manangupta.jobforge.domain.JobExecution;

import java.time.Instant;

public record ExecutionResponse(int attempt, String nodeId, String outcome, String error,
                                Instant startedAt, Instant finishedAt, long durationMs) {

    public static ExecutionResponse from(JobExecution e) {
        return new ExecutionResponse(e.getAttempt(), e.getNodeId(), e.getOutcome(), e.getError(),
                e.getStartedAt(), e.getFinishedAt(), e.getDurationMs());
    }
}
