package com.manangupta.jobforge.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manangupta.jobforge.domain.Job;
import com.manangupta.jobforge.domain.JobStatus;
import com.manangupta.jobforge.repository.JobRepository;
import com.manangupta.jobforge.service.JsonCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemplateResolverTest {

    @Mock JobRepository jobs;
    TemplateResolver resolver;
    UUID workflowId = UUID.randomUUID();
    Job current;

    @BeforeEach
    void setUp() {
        resolver = new TemplateResolver(jobs, new JsonCodec(new ObjectMapper()));
        current = Job.create("echo", "{}", 5, Instant.now(), null, 3, 1000, Set.of(), null, null);
        current.attachToWorkflow(workflowId, "wf", "me", 1);
    }

    private Job upstream(String stepId, JobStatus status, String resultJson) {
        Job j = Job.create("llm", "{}", 5, Instant.now(), null, 3, 1000, Set.of(), null, null);
        j.attachToWorkflow(workflowId, "wf", stepId, 0);
        if (status == JobStatus.SUCCEEDED) {
            forceRunning(j);
            j.markSucceeded(resultJson);
        }
        when(jobs.findByWorkflowIdAndStepId(workflowId, stepId)).thenReturn(Optional.of(j));
        return j;
    }

    private static void forceRunning(Job j) {
        try {
            var f = Job.class.getDeclaredField("status");
            f.setAccessible(true);
            f.set(j, JobStatus.RUNNING);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void interpolatesTextIntoStrings() {
        upstream("fetch", JobStatus.SUCCEEDED, "{\"text\":\"hello world\",\"status\":200}");

        Map<String, Object> out = resolver.resolve(
                Map.of("prompt", "Summarize: {{steps.fetch.output.text}} (status {{ steps.fetch.output.status }})"), current);

        assertThat(out.get("prompt")).isEqualTo("Summarize: hello world (status 200)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void wholeStringReferenceKeepsType() {
        upstream("fetch", JobStatus.SUCCEEDED, "{\"status\":200,\"tags\":[\"a\",\"b\"],\"meta\":{\"k\":1}}");

        Map<String, Object> out = resolver.resolve(Map.of(
                "status", "{{steps.fetch.output.status}}",
                "second", "{{steps.fetch.output.tags.1}}",
                "whole", "{{steps.fetch.output}}",
                "nested", Map.of("k", "{{steps.fetch.output.meta.k}}"),
                "list", List.of("{{steps.fetch.output.status}}", "plain")), current);

        assertThat(out.get("status")).isEqualTo(200);
        assertThat(out.get("second")).isEqualTo("b");
        assertThat(out.get("whole")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) out.get("nested")).get("k")).isEqualTo(1);
        assertThat((List<Object>) out.get("list")).containsExactly(200, "plain");
    }

    @Test
    void leavesPayloadsWithoutReferencesUntouched() {
        Map<String, Object> payload = Map.of("prompt", "no templates here", "n", 3);
        assertThat(resolver.resolve(payload, current)).isEqualTo(payload);
    }

    @Test
    void ignoresTemplatesForJobsOutsideAWorkflow() {
        Job plain = Job.create("echo", "{}", 5, Instant.now(), null, 3, 1000, Set.of(), null, null);
        Map<String, Object> payload = Map.of("prompt", "{{steps.x.output.text}}");
        assertThat(resolver.resolve(payload, plain)).isEqualTo(payload);
    }

    @Test
    void failsClearlyWhenUpstreamHasNotSucceeded() {
        upstream("fetch", JobStatus.PENDING, null);

        assertThatThrownBy(() -> resolver.resolve(Map.of("p", "{{steps.fetch.output.text}}"), current))
                .isInstanceOf(TemplateResolver.TemplateException.class)
                .hasMessageContaining("has not succeeded")
                .hasMessageContaining("dependsOn");
    }

    @Test
    void failsClearlyOnMissingStepOrPath() {
        when(jobs.findByWorkflowIdAndStepId(workflowId, "nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.resolve(Map.of("p", "{{steps.nope.output.text}}"), current))
                .isInstanceOf(TemplateResolver.TemplateException.class)
                .hasMessageContaining("does not exist");

        upstream("fetch", JobStatus.SUCCEEDED, "{\"text\":\"x\"}");
        assertThatThrownBy(() -> resolver.resolve(Map.of("p", "{{steps.fetch.output.missing.deep}}"), current))
                .isInstanceOf(TemplateResolver.TemplateException.class)
                .hasMessageContaining("no value at 'missing.deep'");
    }
}
