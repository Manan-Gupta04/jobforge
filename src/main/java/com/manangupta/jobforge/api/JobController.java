package com.manangupta.jobforge.api;

import com.manangupta.jobforge.api.dto.*;
import com.manangupta.jobforge.domain.JobStatus;
import com.manangupta.jobforge.service.JobService;
import com.manangupta.jobforge.service.JobSpec;
import com.manangupta.jobforge.service.JsonCodec;
import com.manangupta.jobforge.service.SubmitResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
@Tag(name = "Jobs", description = "Submit, inspect, cancel and retry jobs")
public class JobController {

    private final JobService jobs;
    private final JsonCodec json;

    public JobController(JobService jobs, JsonCodec json) {
        this.jobs = jobs;
        this.json = json;
    }

    @PostMapping
    @Operation(summary = "Submit a job",
            description = "Returns 201 for a new job, or 200 with the existing job when the idempotency key was seen before.")
    public ResponseEntity<JobResponse> submit(@Valid @RequestBody SubmitJobRequest req) {
        SubmitResult r = jobs.submit(new JobSpec(req.type(), req.payload(), req.priority(), req.runAt(),
                req.cron(), req.maxAttempts(), req.timeoutMs(), req.dependsOn(), req.idempotencyKey(),
                req.callbackUrl()));
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(JobResponse.from(r.job(), json));
    }

    @PostMapping("/bulk")
    @Operation(summary = "Submit many identical jobs at once (benchmarks, batch producers)")
    public ResponseEntity<Map<String, Object>> submitBulk(@Valid @RequestBody BulkSubmitRequest req) {
        long t0 = System.nanoTime();
        JobSpec template = new JobSpec(req.type(), req.payload(), req.priority(), null, null,
                req.maxAttempts(), req.timeoutMs(), null, null, null);
        List<UUID> ids = jobs.submitBulk(template, req.count());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "submitted", ids.size(),
                "firstId", ids.isEmpty() ? null : ids.get(0),
                "elapsedMs", ms));
    }

    @GetMapping
    @Operation(summary = "List jobs, newest first, optionally filtered by status and/or type")
    public PageResponse<JobResponse> list(@RequestParam(required = false) JobStatus status,
                                          @RequestParam(required = false) String type,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(jobs.list(status, type, page, size), j -> JobResponse.from(j, json));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one job")
    public JobResponse get(@PathVariable UUID id) {
        return JobResponse.from(jobs.get(id), json);
    }

    @GetMapping("/{id}/attempts")
    @Operation(summary = "Execution history of a job: one entry per attempt, with the node that ran it")
    public List<ExecutionResponse> attempts(@PathVariable UUID id) {
        return jobs.attempts(id).stream().map(ExecutionResponse::from).toList();
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a job that has not finished; dependents are cancelled too")
    public JobResponse cancel(@PathVariable UUID id, @RequestParam(required = false) String reason) {
        return JobResponse.from(jobs.cancel(id, reason), json);
    }

    @PostMapping("/{id}/retry")
    @Operation(summary = "Re-queue a DEAD or CANCELLED job with a fresh attempt budget")
    public JobResponse retry(@PathVariable UUID id) {
        return JobResponse.from(jobs.retry(id), json);
    }
}
