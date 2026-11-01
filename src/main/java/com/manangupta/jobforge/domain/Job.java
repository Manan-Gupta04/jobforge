package com.manangupta.jobforge.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A unit of work. The row doubles as the lease record: {@code leaseOwner}/{@code leaseUntil}
 * say which node is running it and until when, and {@code version} lets claims be done as a
 * compare-and-swap so two nodes can never run the same job at once.
 */
@Entity
@Table(name = "jobs", indexes = {
        @Index(name = "ix_jobs_claim", columnList = "status, run_at, priority"),
        @Index(name = "ix_jobs_lease", columnList = "status, lease_until"),
        @Index(name = "ix_jobs_created", columnList = "created_at"),
        @Index(name = "ix_jobs_workflow", columnList = "workflow_id, step_id")
}, uniqueConstraints = @UniqueConstraint(name = "uq_jobs_idempotency", columnNames = "idempotency_key"))
public class Job {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String type;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private int priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @Column(name = "run_at", nullable = false)
    private Instant runAt;

    @Column(name = "cron_expression", length = 120)
    private String cronExpression;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "timeout_ms", nullable = false)
    private long timeoutMs;

    @Column(name = "lease_owner", length = 120)
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(columnDefinition = "TEXT")
    private String result;

    @Column(name = "idempotency_key", length = 200)
    private String idempotencyKey;

    @Column(name = "callback_url", length = 2000)
    private String callbackUrl;

    /** Set when the job is a step of a workflow; steps reference each other by {@code stepId}. */
    @Column(name = "workflow_id")
    private UUID workflowId;

    @Column(name = "workflow_name", length = 120)
    private String workflowName;

    @Column(name = "step_id", length = 80)
    private String stepId;

    /** Topological position of the step within its workflow; gives a stable display order. */
    @Column(name = "step_order")
    private Integer stepOrder;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "job_dependencies", joinColumns = @JoinColumn(name = "job_id"),
            indexes = @Index(name = "ix_job_dependencies_dep", columnList = "depends_on"))
    @Column(name = "depends_on", nullable = false)
    private Set<UUID> dependsOn = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected Job() {
    }

    public static Job create(String type, String payload, int priority, Instant runAt, String cron,
                             int maxAttempts, long timeoutMs, Set<UUID> dependsOn,
                             String idempotencyKey, String callbackUrl) {
        Job j = new Job();
        Instant now = Instant.now();
        j.id = UUID.randomUUID();
        j.type = type;
        j.payload = payload;
        j.priority = priority;
        j.runAt = runAt == null ? now : runAt;
        j.cronExpression = cron;
        j.maxAttempts = maxAttempts;
        j.timeoutMs = timeoutMs;
        j.dependsOn = dependsOn == null ? new HashSet<>() : new HashSet<>(dependsOn);
        j.idempotencyKey = idempotencyKey;
        j.callbackUrl = callbackUrl;
        j.status = j.dependsOn.isEmpty() ? JobStatus.PENDING : JobStatus.WAITING;
        j.createdAt = now;
        j.updatedAt = now;
        return j;
    }

    public void attachToWorkflow(UUID workflowId, String workflowName, String stepId, int stepOrder) {
        this.workflowId = workflowId;
        this.workflowName = workflowName;
        this.stepId = stepId;
        this.stepOrder = stepOrder;
    }

    /** Next occurrence of a cron job: same spec, fresh identity, no idempotency key. */
    public Job nextOccurrence(Instant runAt) {
        return create(type, payload, priority, runAt, cronExpression, maxAttempts, timeoutMs,
                Set.of(), null, callbackUrl);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    // --- state transitions -------------------------------------------------------------

    public void markSucceeded(String result) {
        this.status = JobStatus.SUCCEEDED;
        this.result = result;
        this.lastError = null;
        this.finishedAt = Instant.now();
        clearLease();
    }

    public void scheduleRetry(Instant nextRunAt, String error) {
        this.status = JobStatus.PENDING;
        this.runAt = nextRunAt;
        this.lastError = error;
        clearLease();
    }

    public void markDead(String error) {
        this.status = JobStatus.DEAD;
        this.lastError = error;
        this.finishedAt = Instant.now();
        clearLease();
    }

    public void markCancelled(String reason) {
        this.status = JobStatus.CANCELLED;
        this.lastError = reason;
        this.finishedAt = Instant.now();
        clearLease();
    }

    public void release() {
        this.status = JobStatus.PENDING;
    }

    /** Operator-triggered retry of a dead job: reset the attempt counter and re-queue. */
    public void resurrect() {
        this.status = JobStatus.PENDING;
        this.attempts = 0;
        this.runAt = Instant.now();
        this.finishedAt = null;
        this.lastError = null;
        clearLease();
    }

    public void extendLease(Instant until) {
        this.leaseUntil = until;
    }

    private void clearLease() {
        this.leaseOwner = null;
        this.leaseUntil = null;
    }

    public boolean hasAttemptsLeft() {
        return attempts < maxAttempts;
    }

    // --- getters -----------------------------------------------------------------------

    public UUID getId() { return id; }
    public String getType() { return type; }
    public String getPayload() { return payload; }
    public int getPriority() { return priority; }
    public JobStatus getStatus() { return status; }
    public Instant getRunAt() { return runAt; }
    public String getCronExpression() { return cronExpression; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public long getTimeoutMs() { return timeoutMs; }
    public String getLeaseOwner() { return leaseOwner; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public String getLastError() { return lastError; }
    public String getResult() { return result; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getCallbackUrl() { return callbackUrl; }
    public UUID getWorkflowId() { return workflowId; }
    public String getWorkflowName() { return workflowName; }
    public String getStepId() { return stepId; }
    public Integer getStepOrder() { return stepOrder; }
    public Set<UUID> getDependsOn() { return dependsOn; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public long getVersion() { return version; }
}
