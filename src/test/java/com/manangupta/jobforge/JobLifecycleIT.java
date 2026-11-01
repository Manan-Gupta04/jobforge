package com.manangupta.jobforge;

import com.manangupta.jobforge.domain.Job;
import com.manangupta.jobforge.domain.JobExecution;
import com.manangupta.jobforge.domain.JobStatus;
import com.manangupta.jobforge.repository.JobExecutionRepository;
import com.manangupta.jobforge.repository.JobRepository;
import com.manangupta.jobforge.service.JobDispatcher;
import com.manangupta.jobforge.service.JobService;
import com.manangupta.jobforge.service.JobSpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** End-to-end behaviour through the real dispatcher, worker pool, reaper and database. */
@SpringBootTest
@ActiveProfiles("test")
class JobLifecycleIT {

    @Autowired JobService service;
    @Autowired JobRepository jobs;
    @Autowired JobExecutionRepository executions;
    @Autowired JobDispatcher dispatcher;

    private Job awaitStatus(UUID id, JobStatus expected) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(expected));
        return jobs.findById(id).orElseThrow();
    }

    @Test
    void echoJobRunsToCompletionAndStoresResult() {
        Job submitted = service.submit(JobSpec.of("echo", Map.of("hello", "world"))).job();
        assertThat(submitted.getStatus()).isEqualTo(JobStatus.PENDING);

        Job done = awaitStatus(submitted.getId(), JobStatus.SUCCEEDED);

        assertThat(done.getResult()).contains("\"hello\":\"world\"").contains("\"echoedAttempt\":1");
        assertThat(done.getAttempts()).isEqualTo(1);
        assertThat(done.getLeaseOwner()).isNull();
        assertThat(done.getStartedAt()).isNotNull();
        assertThat(done.getFinishedAt()).isAfterOrEqualTo(done.getStartedAt());

        List<JobExecution> history = executions.findByJobIdOrderByAttemptAsc(done.getId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getOutcome()).isEqualTo("SUCCEEDED");
    }

    @Test
    void flakyJobIsRetriedWithBackoffUntilItSucceeds() {
        Job submitted = service.submit(JobSpec.of("flaky", Map.of("failTimes", 2)).withMaxAttempts(5)).job();

        Job done = awaitStatus(submitted.getId(), JobStatus.SUCCEEDED);

        assertThat(done.getAttempts()).isEqualTo(3);
        assertThat(done.getResult()).contains("\"succeededOnAttempt\":3");
        List<JobExecution> history = executions.findByJobIdOrderByAttemptAsc(done.getId());
        assertThat(history).extracting(JobExecution::getOutcome).containsExactly("FAILED", "FAILED", "SUCCEEDED");
        // backoff: attempt 2 must start after attempt 1 finished, not instantly
        assertThat(history.get(1).getStartedAt()).isAfter(history.get(0).getFinishedAt());
    }

    @Test
    void permanentlyFailingJobIsDeadLettered() {
        Job submitted = service.submit(JobSpec.of("fail", Map.of("message", "kaboom")).withMaxAttempts(2)).job();

        Job dead = awaitStatus(submitted.getId(), JobStatus.DEAD);

        assertThat(dead.getAttempts()).isEqualTo(2);
        assertThat(dead.getLastError()).contains("kaboom");
        assertThat(executions.findByJobIdOrderByAttemptAsc(dead.getId())).hasSize(2);
    }

    @Test
    void deadJobCanBeResurrectedByOperator() {
        Job submitted = service.submit(JobSpec.of("flaky", Map.of("failTimes", 1)).withMaxAttempts(1)).job();
        awaitStatus(submitted.getId(), JobStatus.DEAD);

        Job revived = service.retry(submitted.getId());
        assertThat(revived.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(revived.getAttempts()).isZero();

        // On the fresh budget the handler fails attempt 1 again (failTimes=1) and dies: proves the budget reset.
        Job again = awaitStatus(submitted.getId(), JobStatus.DEAD);
        assertThat(again.getAttempts()).isEqualTo(1);
        assertThat(executions.findByJobIdOrderByAttemptAsc(again.getId())).hasSize(2);
    }

    @Test
    void slowJobTimesOutAndIsFailed() {
        Job submitted = service.submit(JobSpec.of("sleep", Map.of("ms", 10_000))
                .withTimeoutMs(300).withMaxAttempts(1)).job();

        Job dead = awaitStatus(submitted.getId(), JobStatus.DEAD);

        assertThat(dead.getLastError()).contains("Timed out after 300 ms");
    }

    @Test
    void dependentJobWaitsForUpstreamAndRunsAfterIt() {
        Job a = service.submit(JobSpec.of("sleep", Map.of("ms", 400))).job();
        Job b = service.submit(JobSpec.of("echo", Map.of("step", "b")).withDependsOn(Set.of(a.getId()))).job();
        Job c = service.submit(JobSpec.of("echo", Map.of("step", "c")).withDependsOn(Set.of(a.getId(), b.getId()))).job();

        assertThat(b.getStatus()).isEqualTo(JobStatus.WAITING);
        assertThat(c.getStatus()).isEqualTo(JobStatus.WAITING);

        Job aDone = awaitStatus(a.getId(), JobStatus.SUCCEEDED);
        Job bDone = awaitStatus(b.getId(), JobStatus.SUCCEEDED);
        Job cDone = awaitStatus(c.getId(), JobStatus.SUCCEEDED);

        assertThat(bDone.getStartedAt()).isAfterOrEqualTo(aDone.getFinishedAt());
        assertThat(cDone.getStartedAt()).isAfterOrEqualTo(bDone.getFinishedAt());
    }

    @Test
    void deadUpstreamCancelsTheWholeDownstreamChain() {
        Job a = service.submit(JobSpec.of("fail", Map.of()).withMaxAttempts(1)).job();
        Job b = service.submit(JobSpec.of("echo", Map.of()).withDependsOn(Set.of(a.getId()))).job();
        Job c = service.submit(JobSpec.of("echo", Map.of()).withDependsOn(Set.of(b.getId()))).job();

        awaitStatus(a.getId(), JobStatus.DEAD);
        Job bOut = awaitStatus(b.getId(), JobStatus.CANCELLED);
        Job cOut = awaitStatus(c.getId(), JobStatus.CANCELLED);

        assertThat(bOut.getLastError()).contains(a.getId().toString());
        assertThat(cOut.getLastError()).contains(b.getId().toString());
    }

    @Test
    void scheduledJobDoesNotRunBeforeItsTime() throws InterruptedException {
        Instant later = Instant.now().plusSeconds(2);
        Job submitted = service.submit(JobSpec.of("echo", Map.of()).withRunAt(later)).job();

        Thread.sleep(700);
        assertThat(jobs.findById(submitted.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.PENDING);

        Job done = awaitStatus(submitted.getId(), JobStatus.SUCCEEDED);
        assertThat(done.getStartedAt()).isAfterOrEqualTo(later.minusMillis(100));
    }

    @Test
    void higherPriorityJobsRunFirst() {
        // Build a backlog larger than the pool while the dispatcher is paused, so the first claim
        // batch has to choose: priority 9 must be picked before priority 1.
        dispatcher.setPaused(true);
        List<UUID> blockers;
        Job low;
        Job high;
        try {
            blockers = service.submitBulk(JobSpec.of("sleep", Map.of("ms", 600)), 8);
            low = service.submit(JobSpec.of("echo", Map.of("p", "low")).withPriority(1)).job();
            high = service.submit(JobSpec.of("echo", Map.of("p", "high")).withPriority(9)).job();
        } finally {
            dispatcher.setPaused(false);
        }

        Job highDone = awaitStatus(high.getId(), JobStatus.SUCCEEDED);
        Job lowDone = awaitStatus(low.getId(), JobStatus.SUCCEEDED);
        blockers.forEach(id -> awaitStatus(id, JobStatus.SUCCEEDED));

        assertThat(highDone.getStartedAt()).isBefore(lowDone.getStartedAt());
    }

    @Test
    void cronJobSchedulesItsNextOccurrenceAfterRunning() {
        // First run now; the cron (midnight, 1 January) puts the next occurrence far in the future.
        String cron = "0 0 0 1 1 *";
        Job first = service.submit(JobSpec.of("echo", Map.of("cron", true))
                .withCron(cron).withRunAt(Instant.now())).job();

        awaitStatus(first.getId(), JobStatus.SUCCEEDED);

        List<Job> occurrences = jobs.findAll().stream()
                .filter(j -> cron.equals(j.getCronExpression())).toList();
        assertThat(occurrences).hasSize(2);
        Job next = occurrences.stream().filter(j -> !j.getId().equals(first.getId())).findFirst().orElseThrow();
        assertThat(next.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(next.getRunAt()).isAfter(Instant.now().plus(Duration.ofDays(1)));
        assertThat(next.getPayload()).isEqualTo(first.getPayload());
    }

    @Test
    void cancellingAPendingJobPreventsExecution() throws InterruptedException {
        Job submitted = service.submit(JobSpec.of("echo", Map.of()).withRunAt(Instant.now().plusSeconds(3))).job();

        Job cancelled = service.cancel(submitted.getId(), "changed my mind");
        assertThat(cancelled.getStatus()).isEqualTo(JobStatus.CANCELLED);

        Thread.sleep(500);
        assertThat(executions.findByJobIdOrderByAttemptAsc(submitted.getId())).isEmpty();
    }
}
