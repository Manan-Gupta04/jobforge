package com.manangupta.jobforge.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/** Submit many identical jobs in one request; used by the benchmark script and the dashboard. */
public record BulkSubmitRequest(
        @NotBlank String type,
        Map<String, Object> payload,
        @Min(1) @Max(50_000) int count,
        @Min(0) @Max(10) Integer priority,
        @Min(1) @Max(50) Integer maxAttempts,
        @Min(100) Long timeoutMs) {
}
