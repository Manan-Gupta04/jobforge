package com.manangupta.jobforge.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** Audit record of one attempt to run a job. */
@Entity
@Table(name = "job_executions", indexes = @Index(name = "ix_exec_job", columnList = "job_id, attempt"))
public class JobExecution {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "node_id", nullable = false, length = 120)
    private String nodeId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    @Column(nullable = false, length = 20)
    private String outcome;

    @Column(columnDefinition = "TEXT")
    private String error;

    protected JobExecution() {
    }

    public JobExecution(UUID jobId, int attempt, String nodeId, Instant startedAt, Instant finishedAt,
                        String outcome, String error) {
        this.id = UUID.randomUUID();
        this.jobId = jobId;
        this.attempt = attempt;
        this.nodeId = nodeId;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.outcome = outcome;
        this.error = error;
    }

    public UUID getId() { return id; }
    public UUID getJobId() { return jobId; }
    public int getAttempt() { return attempt; }
    public String getNodeId() { return nodeId; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getOutcome() { return outcome; }
    public String getError() { return error; }
    public long getDurationMs() { return finishedAt.toEpochMilli() - startedAt.toEpochMilli(); }
}
