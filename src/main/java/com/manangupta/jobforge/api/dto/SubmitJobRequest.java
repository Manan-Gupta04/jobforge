package com.manangupta.jobforge.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record SubmitJobRequest(
        @Schema(description = "Registered handler type", example = "echo")
        @NotBlank String type,

        @Schema(description = "Arbitrary JSON object handed to the handler", example = "{\"hello\":\"world\"}")
        Map<String, Object> payload,

        @Schema(description = "0 (lowest) to 10 (highest); higher runs first", example = "5")
        @Min(0) @Max(10) Integer priority,

        @Schema(description = "Earliest time to run (ISO-8601). Defaults to now.")
        Instant runAt,

        @Schema(description = "Spring 6-field cron (sec min hour day month weekday) for recurring jobs",
                example = "0 */5 * * * *")
        String cron,

        @Schema(description = "Attempts before dead-lettering", example = "3")
        @Min(1) @Max(50) Integer maxAttempts,

        @Schema(description = "Per-attempt timeout in milliseconds", example = "60000")
        @Min(100) @Max(3_600_000) Long timeoutMs,

        @Schema(description = "Ids of jobs that must SUCCEED before this one runs")
        Set<UUID> dependsOn,

        @Schema(description = "Client-chosen key; resubmitting with the same key returns the original job")
        @Size(max = 200) String idempotencyKey,

        @Schema(description = "URL to POST a JSON summary to when the job reaches a terminal state")
        @Size(max = 2000) String callbackUrl) {
}
