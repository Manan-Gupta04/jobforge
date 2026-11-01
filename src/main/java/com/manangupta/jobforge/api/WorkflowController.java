package com.manangupta.jobforge.api;

import com.manangupta.jobforge.workflow.ExampleWorkflows;
import com.manangupta.jobforge.workflow.WorkflowService;
import com.manangupta.jobforge.workflow.WorkflowSpec;
import com.manangupta.jobforge.workflow.WorkflowView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workflows")
@Tag(name = "Workflows", description = "Submit a DAG of steps as one document; outputs flow between steps via templates")
public class WorkflowController {

    private final WorkflowService workflows;

    public WorkflowController(WorkflowService workflows) {
        this.workflows = workflows;
    }

    @PostMapping
    @Operation(summary = "Submit a workflow",
            description = "Steps reference each other by id in dependsOn, and payload strings may contain "
                    + "{{steps.<id>.output.<path>}} which is resolved from the upstream step's result at run time.")
    public ResponseEntity<WorkflowView> submit(@RequestBody WorkflowSpec spec) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workflows.submit(spec));
    }

    @GetMapping
    @Operation(summary = "Recent workflows with per-step status")
    public List<WorkflowView> recent(@RequestParam(defaultValue = "20") int limit) {
        return workflows.recent(limit);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One workflow with every step's status, attempts and output")
    public WorkflowView get(@PathVariable UUID id) {
        return workflows.get(id);
    }

    @PostMapping("/examples/content-pipeline")
    @Operation(summary = "Demo: fetch a URL, then summarise, classify and extract keywords with an LLM")
    public ResponseEntity<WorkflowView> contentPipeline(@RequestBody Map<String, String> body) {
        String url = body.getOrDefault("url", "https://en.wikipedia.org/wiki/Job_scheduler");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(workflows.submit(ExampleWorkflows.contentPipeline(url, body.get("callbackUrl"))));
    }

    @PostMapping("/examples/batch-summaries")
    @Operation(summary = "Demo: map-reduce; one LLM job per text in parallel, then a merge step")
    public ResponseEntity<WorkflowView> batchSummaries(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> texts = (List<String>) body.getOrDefault("texts", List.of(
                "JobForge claims jobs with a compare-and-swap update so no node runs a job twice.",
                "Leases let a reaper recover work from nodes that crash mid-job.",
                "Exponential backoff with jitter prevents retry storms after an outage."));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(workflows.submit(ExampleWorkflows.batchSummaries(texts, (String) body.get("callbackUrl"))));
    }
}
