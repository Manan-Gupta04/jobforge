package com.manangupta.jobforge.service;

import com.manangupta.jobforge.config.NodeIdentity;
import com.manangupta.jobforge.domain.Job;
import com.manangupta.jobforge.domain.JobExecution;
import com.manangupta.jobforge.domain.JobStatus;
import com.manangupta.jobforge.repository.JobExecutionRepository;
import com.manangupta.jobforge.repository.JobRepository;
import com.manangupta.jobforge.retry.BackoffPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * All state transitions after a job has been claimed. Every method is one transaction, so the
 * job row, its execution record, downstream releases and the next cron occurrence commit (or
 * roll back) together.
 */
@Service
public class JobStateService {

    private static final Logger log = LoggerFactory.getLogger(JobStateService.class);

    private final JobRepository jobs;
    private final JobExecutionRepository executions;
    private final BackoffPolicy backoff;
    private final NodeIdentity node;
    private final JobEvents events;

    public JobStateService(JobRepository jobs, JobExecutionRepository executions, BackoffPolicy backoff,
                           NodeIdentity node, JobEvents events) {
        this.jobs = jobs;
        this.executions = executions;
        this.backoff = backoff;
        this.node = node;
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(UUID id, String resultJson, Instant startedAt) {
        Job job = jobs.findById(id).orElseThrow(() -> new JobNotFoundException(id));
        if (job.getStatus() != JobStatus.RUNNING) {
            // Lease was reaped and another worker has (or will) run it; this result is stale.
            log.warn("Discarding result of job {}: status is {} not RUNNING", id, job.getStatus());
            return;
        }
        Instant now = Instant.now();
        job.markSucceeded(resultJson);
        executions.save(new JobExecution(id, job.getAttempts(), node.id(), startedAt, now, "SUCCEEDED", null));
        releaseDependents(job);
        scheduleNextCronOccurrence(job, now);
        events.terminal(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID id, String error, Instant startedAt) {
        Job job = jobs.findById(id).orElseThrow(() -> new JobNotFoundException(id));
        if (job.getStatus() != JobStatus.RUNNING) {
            log.warn("Discarding failure of job {}: status is {} not RUNNING", id, job.getStatus());
            return;
        }
        Instant now = Instant.now();
        executions.save(new JobExecution(id, job.getAttempts(), node.id(), startedAt, now, "FAILED", error));
        if (job.hasAttemptsLeft()) {
            Instant next = now.plus(backoff.delayFor(job.getAttempts()));
            job.scheduleRetry(next, error);
            log.info("Job {} attempt {}/{} failed; retry at {}", id, job.getAttempts(), job.getMaxAttempts(), next);
            events.retryScheduled(job);
        } else {
            job.markDead(error);
            log.warn("Job {} dead-lettered after {} attempts: {}", id, job.getAttempts(), error);
            cancelDependents(job, "Upstream job " + id + " dead-lettered");
            scheduleNextCronOccurrence(job, now);
            events.terminal(job);
        }
    }

    /** Called by the reaper for RUNNING jobs whose lease expired (worker died or hung). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reclaimExpired(UUID id) {
        Job job = jobs.findById(id).orElseThrow(() -> new JobNotFoundException(id));
        if (job.getStatus() != JobStatus.RUNNING || job.getLeaseUntil() == null
                || job.getLeaseUntil().isAfter(Instant.now())) {
            return; // already handled, or lease was renewed in the meantime
        }
        String error = "Lease expired while running on " + job.getLeaseOwner() + " (worker crashed or hung)";
        Instant now = Instant.now();
        executions.save(new JobExecution(id, job.getAttempts(), node.id(), job.getStartedAt(), now, "LEASE_EXPIRED", error));
        if (job.hasAttemptsLeft()) {
            job.scheduleRetry(now, error);
            events.retryScheduled(job);
        } else {
            job.markDead(error);
            cancelDependents(job, "Upstream job " + id + " dead-lettered");
            scheduleNextCronOccurrence(job, now);
            events.terminal(job);
        }
        events.leaseReclaimed(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Job cancel(UUID id, String reason) {
        Job job = jobs.findById(id).orElseThrow(() -> new JobNotFoundException(id));
        if (job.getStatus().isTerminal()) {
            throw new InvalidJobException("Job " + id + " is already " + job.getStatus());
        }
        job.markCancelled(reason);
        cancelDependents(job, "Upstream job " + id + " was cancelled");
        events.terminal(job);
        return job;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Job resurrect(UUID id) {
        Job job = jobs.findById(id).orElseThrow(() -> new JobNotFoundException(id));
        if (job.getStatus() != JobStatus.DEAD && job.getStatus() != JobStatus.CANCELLED) {
            throw new InvalidJobException("Only DEAD or CANCELLED jobs can be retried; job " + id
                    + " is " + job.getStatus());
        }
        job.resurrect();
        return job;
    }

    /** A dependent becomes PENDING once every job it depends on has SUCCEEDED. */
    private void releaseDependents(Job completed) {
        for (Job dependent : jobs.findDependents(completed.getId())) {
            if (dependent.getStatus() != JobStatus.WAITING) {
                continue;
            }
            long done = jobs.countByIdInAndStatus(dependent.getDependsOn(), JobStatus.SUCCEEDED);
            if (done == dependent.getDependsOn().size()) {
                dependent.release();
                log.debug("Released job {} (all {} dependencies succeeded)", dependent.getId(), done);
            }
        }
    }

    /** When an upstream job can never succeed, everything waiting on it is cancelled transitively. */
    private void cancelDependents(Job failed, String reason) {
        List<Job> dependents = jobs.findDependents(failed.getId());
        for (Job dependent : dependents) {
            if (dependent.getStatus() == JobStatus.WAITING || dependent.getStatus() == JobStatus.PENDING) {
                dependent.markCancelled(reason);
                events.terminal(dependent);
                cancelDependents(dependent, "Upstream job " + dependent.getId() + " was cancelled");
            }
        }
    }

    private void scheduleNextCronOccurrence(Job finished, Instant now) {
        if (finished.getCronExpression() == null) {
            return;
        }
        Instant next = JobService.nextCron(finished.getCronExpression(), now);
        Job occurrence = jobs.save(finished.nextOccurrence(next));
        log.info("Cron job {} ({}) rescheduled as {} at {}", finished.getId(), finished.getCronExpression(),
                occurrence.getId(), next);
    }
}
