package com.manangupta.jobforge.service;

import com.manangupta.jobforge.domain.Job;
import com.manangupta.jobforge.handler.JobContext;
import com.manangupta.jobforge.handler.JobHandler;
import com.manangupta.jobforge.handler.JobHandlerRegistry;
import com.manangupta.jobforge.handler.JobResult;
import com.manangupta.jobforge.repository.JobRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Runs one claimed job on a worker thread: looks up the handler, keeps the lease alive with a
 * background heartbeat, enforces the job's timeout by interrupting the handler, and hands the
 * outcome to {@link JobStateService}.
 */
@Service
public class JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);
    private static final int MAX_ERROR_LENGTH = 4000;

    private final JobRepository jobs;
    private final JobHandlerRegistry registry;
    private final JobClaimService claims;
    private final JobStateService state;
    private final JsonCodec json;
    private final ExecutorService handlerPool;
    private final ThreadPoolTaskScheduler heartbeats;
    private final MeterRegistry meters;

    public JobExecutor(JobRepository jobs, JobHandlerRegistry registry, JobClaimService claims,
                       JobStateService state, JsonCodec json,
                       @Qualifier("handlerPool") ExecutorService handlerPool,
                       ThreadPoolTaskScheduler heartbeatScheduler, MeterRegistry meters) {
        this.jobs = jobs;
        this.registry = registry;
        this.claims = claims;
        this.state = state;
        this.json = json;
        this.handlerPool = handlerPool;
        this.heartbeats = heartbeatScheduler;
        this.meters = meters;
    }

    public void execute(UUID jobId) {
        Optional<Job> loaded = jobs.findById(jobId);
        if (loaded.isEmpty()) {
            log.error("Claimed job {} vanished before execution", jobId);
            return;
        }
        Job job = loaded.get();
        Instant startedAt = Instant.now();
        Timer.Sample sample = Timer.start(meters);

        Optional<JobHandler> handler = registry.find(job.getType());
        if (handler.isEmpty()) {
            finishFailed(job, startedAt, sample, "No handler registered for type '" + job.getType() + "'");
            return;
        }

        Map<String, Object> payload = json.read(job.getPayload());
        Context ctx = new Context(job, payload);

        // Keep the lease alive while the handler runs; stop the handler if the lease is lost.
        long leaseMs = claims.leaseDuration().toMillis();
        Duration beat = Duration.ofMillis(Math.max(200, leaseMs / 3));
        Future<JobResult> work = handlerPool.submit(() -> handler.get().execute(ctx));
        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(() -> {
            if (work.isDone()) {
                return; // finished between beats; the outcome path cancels this task
            }
            if (!claims.extendLease(job.getId())) {
                log.warn("Lost lease on job {}; interrupting handler", job.getId());
                work.cancel(true);
            }
        }, Instant.now().plus(beat), beat);

        try {
            JobResult result = work.get(job.getTimeoutMs(), TimeUnit.MILLISECONDS);
            heartbeat.cancel(false);
            String out = json.write(result == null ? Map.of() : result.output());
            state.succeed(job.getId(), out, startedAt);
            sample.stop(timer(job.getType(), "succeeded"));
        } catch (TimeoutException e) {
            heartbeat.cancel(false);
            work.cancel(true);
            finishFailed(job, startedAt, sample, "Timed out after " + job.getTimeoutMs() + " ms");
        } catch (CancellationException e) {
            heartbeat.cancel(false);
            finishFailed(job, startedAt, sample, "Execution cancelled (lease lost or job cancelled)");
        } catch (ExecutionException e) {
            heartbeat.cancel(false);
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.debug("Job {} handler threw", job.getId(), cause);
            finishFailed(job, startedAt, sample, describe(cause));
        } catch (InterruptedException e) {
            heartbeat.cancel(false);
            work.cancel(true);
            Thread.currentThread().interrupt();
            finishFailed(job, startedAt, sample, "Worker interrupted (node shutting down)");
        }
    }

    private void finishFailed(Job job, Instant startedAt, Timer.Sample sample, String error) {
        try {
            state.fail(job.getId(), truncate(error), startedAt);
        } finally {
            sample.stop(timer(job.getType(), "failed"));
        }
    }

    private Timer timer(String type, String outcome) {
        return Timer.builder("jobforge.job.duration")
                .description("Wall-clock time of job executions")
                .tag("type", type)
                .tag("outcome", outcome)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters);
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg);
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() <= MAX_ERROR_LENGTH ? s : s.substring(0, MAX_ERROR_LENGTH) + "…";
    }

    /** Per-execution view handed to the handler. */
    private final class Context implements JobContext {
        private final Job job;
        private final Map<String, Object> payload;

        Context(Job job, Map<String, Object> payload) {
            this.job = job;
            this.payload = payload;
        }

        @Override
        public UUID jobId() {
            return job.getId();
        }

        @Override
        public int attempt() {
            return job.getAttempts();
        }

        @Override
        public int maxAttempts() {
            return job.getMaxAttempts();
        }

        @Override
        public Map<String, Object> payload() {
            return payload;
        }

        @Override
        public void heartbeat() {
            claims.extendLease(job.getId());
        }
    }
}
