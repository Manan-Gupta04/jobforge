package com.manangupta.jobforge.workflow;

import com.manangupta.jobforge.domain.Job;
import com.manangupta.jobforge.domain.JobStatus;
import com.manangupta.jobforge.repository.JobRepository;
import com.manangupta.jobforge.service.JsonCodec;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code {{steps.<stepId>.output.<path>}}} references in a job's payload against the
 * results of upstream steps in the same workflow, right before the job runs. This is what lets a
 * step's output flow into the next step's input without the client gluing anything together.
 *
 * <p>A string that is exactly one reference is replaced by the raw value (so numbers, lists and
 * objects keep their type); otherwise references are interpolated as text. Nested maps and lists
 * are resolved recursively.
 */
@Component
public class TemplateResolver {

    private static final Pattern REF = Pattern.compile(
            "\\{\\{\\s*steps\\.([A-Za-z0-9_-]+)\\.output(?:\\.([A-Za-z0-9_.\\-\\[\\]]+))?\\s*}}");

    private final JobRepository jobs;
    private final JsonCodec json;

    public TemplateResolver(JobRepository jobs, JsonCodec json) {
        this.jobs = jobs;
        this.json = json;
    }

    public Map<String, Object> resolve(Map<String, Object> payload, Job job) {
        if (job.getWorkflowId() == null || payload == null || payload.isEmpty()) {
            return payload;
        }
        Map<String, Map<String, Object>> cache = new HashMap<>();
        Object out = resolveValue(payload, job, cache);
        @SuppressWarnings("unchecked")
        Map<String, Object> resolved = (Map<String, Object>) out;
        return resolved;
    }

    private Object resolveValue(Object value, Job job, Map<String, Map<String, Object>> cache) {
        if (value instanceof String s) {
            return resolveString(s, job, cache);
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), resolveValue(v, job, cache)));
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            l.forEach(v -> out.add(resolveValue(v, job, cache)));
            return out;
        }
        return value;
    }

    private Object resolveString(String s, Job job, Map<String, Map<String, Object>> cache) {
        Matcher m = REF.matcher(s);
        if (!m.find()) {
            return s;
        }
        // Whole-string reference keeps the referenced value's type.
        if (m.start() == 0 && m.end() == s.length()) {
            return lookup(m.group(1), m.group(2), job, cache);
        }
        StringBuilder sb = new StringBuilder();
        m.reset();
        int last = 0;
        while (m.find()) {
            sb.append(s, last, m.start());
            Object v = lookup(m.group(1), m.group(2), job, cache);
            sb.append(v == null ? "" : v instanceof String str ? str : json.write(Map.of("v", v)).replaceAll("^\\{\"v\":|}$", ""));
            last = m.end();
        }
        sb.append(s.substring(last));
        return sb.toString();
    }

    private Object lookup(String stepId, String path, Job job, Map<String, Map<String, Object>> cache) {
        Map<String, Object> output = cache.computeIfAbsent(stepId, id -> {
            Job upstream = jobs.findByWorkflowIdAndStepId(job.getWorkflowId(), id)
                    .orElseThrow(() -> new TemplateException("Step '" + id + "' does not exist in workflow "
                            + job.getWorkflowId()));
            if (upstream.getStatus() != JobStatus.SUCCEEDED) {
                throw new TemplateException("Step '" + id + "' has not succeeded (status " + upstream.getStatus()
                        + "); declare it in dependsOn so this step waits for it");
            }
            return json.read(upstream.getResult());
        });
        if (path == null || path.isBlank()) {
            return output;
        }
        Object current = output;
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else if (current instanceof List<?> list && part.matches("\\d+")) {
                int i = Integer.parseInt(part);
                current = i < list.size() ? list.get(i) : null;
            } else {
                current = null;
            }
            if (current == null) {
                throw new TemplateException("Step '" + stepId + "' output has no value at '" + path + "'");
            }
        }
        return current;
    }

    /** A template that cannot be resolved is a permanent error for this attempt. */
    public static class TemplateException extends RuntimeException {
        public TemplateException(String message) {
            super(message);
        }
    }
}
