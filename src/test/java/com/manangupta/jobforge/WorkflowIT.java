package com.manangupta.jobforge;

import com.manangupta.jobforge.domain.Job;
import com.manangupta.jobforge.domain.JobStatus;
import com.manangupta.jobforge.repository.JobRepository;
import com.manangupta.jobforge.service.JobClaimService;
import com.manangupta.jobforge.service.JobDispatcher;
import com.manangupta.jobforge.service.JobService;
import com.manangupta.jobforge.service.JobSpec;
import com.manangupta.jobforge.workflow.ExampleWorkflows;
import com.manangupta.jobforge.workflow.WorkflowService;
import com.manangupta.jobforge.workflow.WorkflowSpec;
import com.manangupta.jobforge.workflow.WorkflowView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** LLM pipelines end to end with the mock provider, plus the per-type rate limiter under load. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jobforge-workflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "jobforge.rate-limits.llm=4"
})
@ActiveProfiles("test")
class WorkflowIT {

    @Autowired WorkflowService workflows;
    @Autowired JobService jobs;
    @Autowired JobRepository repo;
    @Autowired JobClaimService claims;
    @Autowired JobDispatcher dispatcher;

    private WorkflowView awaitWorkflow(UUID id, String status) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(workflows.get(id).status()).isEqualTo(status));
        return workflows.get(id);
    }

    @Test
    void outputsFlowBetweenStepsThroughTemplates() {
        WorkflowView submitted = workflows.submit(new WorkflowSpec("pipeline", List.of(
                new WorkflowSpec.Step("source", "echo", Map.of("text", "JobForge runs LLM pipelines reliably."),
                        List.of(), null, null, null),
                new WorkflowSpec.Step("summarize", "llm",
                        Map.of("prompt", "Summarize this: {{steps.source.output.text}}", "maxTokens", 50),
                        List.of("source"), null, null, null),
                new WorkflowSpec.Step("classify", "llm",
                        Map.of("prompt", "Classify into a category: {{steps.summarize.output.text}}"),
                        List.of("summarize"), null, null, null),
                new WorkflowSpec.Step("report", "echo",
                        Map.of("category", "{{steps.classify.output.text}}",
                                "summary", "{{steps.summarize.output.text}}",
                                "provider", "{{steps.summarize.output.provider}}"),
                        List.of("classify"), null, null, null)
        ), null));
        assertThat(submitted.status()).isEqualTo("RUNNING");

        WorkflowView done = awaitWorkflow(submitted.id(), "SUCCEEDED");

        Map<String, Object> report = done.steps().stream().filter(s -> s.stepId().equals("report")).findFirst()
                .orElseThrow().output();
        assertThat(report.get("provider")).isEqualTo("mock");
        assertThat((String) report.get("summary")).startsWith("[mock summary]").contains("JobForge runs LLM pipelines");
        assertThat((String) report.get("category")).isIn("technology", "business", "science", "general");
        assertThat(done.succeededSteps()).isEqualTo(4);
        assertThat(done.finishedAt()).isNotNull();
    }

    @Test
    void parallelStepsRunConcurrentlyAndMergeStepWaitsForAll() {
        WorkflowView submitted = workflows.submit(ExampleWorkflows.batchSummaries(
                List.of("alpha text", "beta text", "gamma text"), null));

        WorkflowView done = awaitWorkflow(submitted.id(), "SUCCEEDED");

        Map<String, Object> merged = done.steps().stream().filter(s -> s.stepId().equals("merge")).findFirst()
                .orElseThrow().output();
        assertThat(merged).containsKeys("summary0", "summary1", "summary2");
        assertThat((String) merged.get("summary1")).contains("beta text");
        Job merge = repo.findByWorkflowIdAndStepId(submitted.id(), "merge").orElseThrow();
        for (int i = 0; i < 3; i++) {
            Job item = repo.findByWorkflowIdAndStepId(submitted.id(), "item-" + i).orElseThrow();
            assertThat(merge.getStartedAt()).isAfterOrEqualTo(item.getFinishedAt());
        }
    }

    @Test
    void templateReferencingAStepThatIsNotADependencyFailsTheStepNotTheEngine() {
        WorkflowView submitted = workflows.submit(new WorkflowSpec("bad", List.of(
                new WorkflowSpec.Step("slow", "sleep", Map.of("ms", 3000), List.of(), null, null, null),
                new WorkflowSpec.Step("eager", "echo", Map.of("x", "{{steps.slow.output.sleptMs}}"),
                        List.of(), null, 1, null)
        ), null));

        awaitWorkflow(submitted.id(), "FAILED");
        Job eager = repo.findByWorkflowIdAndStepId(submitted.id(), "eager").orElseThrow();
        assertThat(eager.getStatus()).isEqualTo(JobStatus.DEAD);
        assertThat(eager.getLastError()).contains("Template error").contains("has not succeeded");
    }

    @Test
    void failedStepCancelsDownstreamAndMarksWorkflowFailed() {
        WorkflowView submitted = workflows.submit(new WorkflowSpec("failing", List.of(
                new WorkflowSpec.Step("boom", "fail", Map.of(), List.of(), null, 1, null),
                new WorkflowSpec.Step("after", "echo", Map.of(), List.of("boom"), null, null, null)
        ), null));

        WorkflowView done = awaitWorkflow(submitted.id(), "FAILED");
        assertThat(done.steps()).extracting(WorkflowView.StepView::status)
                .containsExactly(JobStatus.DEAD, JobStatus.CANCELLED);
    }

    @Test
    void rateLimitCapsHowManyLlmJobsAreClaimedPerSecond() {
        dispatcher.setPaused(true);
        try {
            jobs.submitBulk(JobSpec.of("llm", Map.of("prompt", "hi")), 40);
            // Bucket starts full (4 tokens) and refills at 4/s; a burst of claims right now gets ~4.
            int claimedNow = claims.claimBatch(40).size();
            assertThat(claimedNow).isBetween(4, 5);
            int claimedAgain = claims.claimBatch(40).size();
            assertThat(claimedAgain).isLessThanOrEqualTo(1);
        } finally {
            dispatcher.setPaused(false);
        }
    }
}
