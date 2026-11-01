package com.manangupta.jobforge.workflow;

import java.util.List;
import java.util.Map;

/** A DAG of steps submitted as one document. Step ids are local names used in {@code dependsOn} and templates. */
public record WorkflowSpec(String name, List<Step> steps, String callbackUrl) {

    public record Step(
            String id,
            String type,
            Map<String, Object> payload,
            List<String> dependsOn,
            Integer priority,
            Integer maxAttempts,
            Long timeoutMs) {
    }
}
