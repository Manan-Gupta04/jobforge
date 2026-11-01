package com.manangupta.jobforge.handler;

import java.util.Map;

/** Outcome of a successful execution; {@code output} is persisted as the job's result. */
public record JobResult(Map<String, Object> output) {

    public static JobResult of(Map<String, Object> output) {
        return new JobResult(output);
    }

    public static JobResult empty() {
        return new JobResult(Map.of());
    }
}
