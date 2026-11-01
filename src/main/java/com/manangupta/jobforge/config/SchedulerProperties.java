package com.manangupta.jobforge.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Tunables for the scheduler. Every value can be overridden with an environment variable,
 * e.g. {@code JOBFORGE_WORKERS=16}.
 */
@Validated
@ConfigurationProperties(prefix = "jobforge")
public class SchedulerProperties {

    /** Number of jobs this node executes concurrently. */
    @Min(1)
    private int workers = 8;

    /** How often the dispatcher polls the database for claimable jobs. */
    @Min(10)
    private long pollIntervalMs = 500;

    /** How long a claimed job is leased to a worker before it is considered abandoned. */
    @Min(1)
    private long leaseSeconds = 30;

    /** How often the reaper looks for jobs whose lease has expired. */
    @Min(100)
    private long reaperIntervalMs = 5000;

    /** Base delay for the exponential backoff between retries. */
    @Min(1)
    private long retryBaseDelayMs = 1000;

    /** Upper bound on the retry delay. */
    @Min(1)
    private long retryMaxDelayMs = 300_000;

    /** Default per-job execution timeout when the submitter does not specify one. */
    @Min(100)
    private long defaultTimeoutMs = 60_000;

    /** Default maximum attempts when the submitter does not specify one. */
    @Min(1)
    private int defaultMaxAttempts = 3;

    /** Optional stable node identifier; a random one is generated when blank. */
    private String nodeId = "";

    /** Per-node claim rate limits by job type, in jobs per second, e.g. {@code rate-limits.llm: 2}. */
    private java.util.Map<String, Double> rateLimits = new java.util.LinkedHashMap<>();

    public int getWorkers() { return workers; }
    public void setWorkers(int workers) { this.workers = workers; }
    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    public long getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(long leaseSeconds) { this.leaseSeconds = leaseSeconds; }
    public long getReaperIntervalMs() { return reaperIntervalMs; }
    public void setReaperIntervalMs(long reaperIntervalMs) { this.reaperIntervalMs = reaperIntervalMs; }
    public long getRetryBaseDelayMs() { return retryBaseDelayMs; }
    public void setRetryBaseDelayMs(long retryBaseDelayMs) { this.retryBaseDelayMs = retryBaseDelayMs; }
    public long getRetryMaxDelayMs() { return retryMaxDelayMs; }
    public void setRetryMaxDelayMs(long retryMaxDelayMs) { this.retryMaxDelayMs = retryMaxDelayMs; }
    public long getDefaultTimeoutMs() { return defaultTimeoutMs; }
    public void setDefaultTimeoutMs(long defaultTimeoutMs) { this.defaultTimeoutMs = defaultTimeoutMs; }
    public int getDefaultMaxAttempts() { return defaultMaxAttempts; }
    public void setDefaultMaxAttempts(int defaultMaxAttempts) { this.defaultMaxAttempts = defaultMaxAttempts; }
    public java.util.Map<String, Double> getRateLimits() { return rateLimits; }
    public void setRateLimits(java.util.Map<String, Double> rateLimits) { this.rateLimits = rateLimits; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
}
