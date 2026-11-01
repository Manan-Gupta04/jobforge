package com.manangupta.jobforge.service;

import com.manangupta.jobforge.config.SchedulerProperties;
import com.manangupta.jobforge.domain.Job;
import com.manangupta.jobforge.domain.JobExecution;
import com.manangupta.jobforge.domain.JobStatus;
import com.manangupta.jobforge.handler.JobHandlerRegistry;
import com.manangupta.jobforge.repository.JobExecutionRepository;
import com.manangupta.jobforge.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;

/** Client-facing operations: submit, inspect, cancel and resurrect jobs. */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobs;
    private final JobExecutionRepository executions;
    private final JobHandlerRegistry registry;
    private final JsonCodec json;
    private final SchedulerProperties props;
    private final JobStateService state;

    public JobService(JobRepository jobs, JobExecutionRepository executions, JobHandlerRegistry registry,
                      JsonCodec json, SchedulerProperties props, JobStateService state) {
        this.jobs = jobs;
        this.executions = executions;
        this.registry = registry;
        this.json = json;
        this.props = props;
        this.state = state;
    }

    /**
     * Creates a job. If {@code idempotencyKey} is set and a job with that key already exists, the
     * existing job is returned instead of creating a duplicate, even under concurrent submits
     * (the unique constraint is the arbiter).
     */
    public SubmitResult submit(JobSpec spec) {
        validate(spec);
        if (spec.idempotencyKey() != null) {
            Optional<Job> existing = jobs.findByIdempotencyKey(spec.idempotencyKey());
            if (existing.isPresent()) {
                return new SubmitResult(existing.get(), false);
            }
        }
        try {
            return new SubmitResult(persist(spec), true);
        } catch (DataIntegrityViolationException race) {
            if (spec.idempotencyKey() == null) {
                throw race;
            }
            Job winner = jobs.findByIdempotencyKey(spec.idempotencyKey())
                    .orElseThrow(() -> race);
            log.debug("Idempotent submit raced; returning existing job {}", winner.getId());
            return new SubmitResult(winner, false);
        }
    }

    /** Bulk submission for benchmarks and batch producers; one transaction, one flush. */
    @Transactional
    public List<UUID> submitBulk(JobSpec template, int count) {
        validate(template);
        List<Job> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            batch.add(toEntity(template.withIdempotencyKey(null)));
        }
        return jobs.saveAll(batch).stream().map(Job::getId).toList();
    }

    private Job persist(JobSpec spec) {
        return jobs.saveAndFlush(toEntity(spec));
    }

    private Job toEntity(JobSpec spec) {
        Set<UUID> deps = spec.dependsOn() == null ? Set.of() : spec.dependsOn();
        Instant runAt = spec.runAt();
        if (spec.cron() != null && runAt == null) {
            runAt = nextCron(spec.cron(), Instant.now());
        }
        return Job.create(
                spec.type(),
                json.write(spec.payload() == null ? Map.of() : spec.payload()),
                spec.priority() == null ? 5 : spec.priority(),
                runAt,
                spec.cron(),
                spec.maxAttempts() == null ? props.getDefaultMaxAttempts() : spec.maxAttempts(),
                spec.timeoutMs() == null ? props.getDefaultTimeoutMs() : spec.timeoutMs(),
                deps,
                spec.idempotencyKey(),
                spec.callbackUrl());
    }

    private void validate(JobSpec spec) {
        if (spec.type() == null || !registry.supports(spec.type())) {
            throw new InvalidJobException("Unknown job type '" + spec.type() + "'. Registered types: "
                    + registry.types());
        }
        if (spec.cron() != null) {
            if (!CronExpression.isValidExpression(spec.cron())) {
                throw new InvalidJobException("Invalid cron expression '" + spec.cron()
                        + "' (expected 6 fields: sec min hour day month weekday)");
            }
            if (spec.dependsOn() != null && !spec.dependsOn().isEmpty()) {
                throw new InvalidJobException("Cron jobs cannot declare dependencies");
            }
        }
        if (spec.dependsOn() != null && !spec.dependsOn().isEmpty()) {
            List<Job> deps = jobs.findAllById(spec.dependsOn());
            if (deps.size() != spec.dependsOn().size()) {
                Set<UUID> found = new HashSet<>();
                deps.forEach(d -> found.add(d.getId()));
                Set<UUID> missing = new HashSet<>(spec.dependsOn());
                missing.removeAll(found);
                throw new InvalidJobException("Unknown dependency job id(s): " + missing);
            }
            for (Job d : deps) {
                if (d.getStatus() == JobStatus.DEAD || d.getStatus() == JobStatus.CANCELLED) {
                    throw new InvalidJobException("Dependency " + d.getId() + " is " + d.getStatus()
                            + "; the new job could never run");
                }
            }
        }
    }

    static Instant nextCron(String cron, Instant from) {
        ZonedDateTime next = CronExpression.parse(cron).next(from.atZone(java.time.ZoneOffset.UTC));
        if (next == null) {
            throw new InvalidJobException("Cron expression '" + cron + "' has no future occurrence");
        }
        return next.toInstant();
    }

    public Job get(UUID id) {
        return jobs.findById(id).orElseThrow(() -> new JobNotFoundException(id));
    }

    public Page<Job> list(JobStatus status, String type, int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "createdAt"));
        if (status != null && type != null) {
            return jobs.findByStatusAndType(status, type, pageable);
        }
        if (status != null) {
            return jobs.findByStatus(status, pageable);
        }
        if (type != null) {
            return jobs.findByType(type, pageable);
        }
        return jobs.findAll(pageable);
    }

    public List<JobExecution> attempts(UUID id) {
        get(id);
        return executions.findByJobIdOrderByAttemptAsc(id);
    }

    /** Cancels a job that has not finished. A running job is interrupted on its next heartbeat. */
    public Job cancel(UUID id, String reason) {
        return state.cancel(id, reason == null ? "Cancelled by operator" : reason);
    }

    /** Moves a dead-lettered job back to the queue with a fresh attempt budget. */
    public Job retry(UUID id) {
        return state.resurrect(id);
    }
}
