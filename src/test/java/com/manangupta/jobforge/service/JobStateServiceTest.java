package com.manangupta.jobforge.service;

import com.manangupta.jobforge.config.NodeIdentity;
import com.manangupta.jobforge.config.SchedulerProperties;
import com.manangupta.jobforge.domain.Job;
import com.manangupta.jobforge.domain.JobExecution;
import com.manangupta.jobforge.domain.JobStatus;
import com.manangupta.jobforge.repository.JobExecutionRepository;
import com.manangupta.jobforge.repository.JobRepository;
import com.manangupta.jobforge.retry.BackoffPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Pure unit tests of the state machine with the repositories mocked out. */
@ExtendWith(MockitoExtension.class)
class JobStateServiceTest {

    @Mock JobRepository jobs;
    @Mock JobExecutionRepository executions;
    @Mock BackoffPolicy backoff;
    @Mock JobEvents events;

    JobStateService service;

    @BeforeEach
    void setUp() {
        SchedulerProperties props = new SchedulerProperties();
        props.setNodeId("test-node");
        service = new JobStateService(jobs, executions, backoff, new NodeIdentity(props), events);
    }

    private static Job runningJob(int attempts, int maxAttempts) {
        Job job = Job.create("echo", "{}", 5, Instant.now(), null, maxAttempts, 1000, Set.of(), null, null);
        // Simulate a claim: the executor only ever sees RUNNING jobs.
        setStatus(job, JobStatus.RUNNING, attempts);
        return job;
    }

    private static void setStatus(Job job, JobStatus status, int attempts) {
        try {
            var s = Job.class.getDeclaredField("status");
            s.setAccessible(true);
            s.set(job, status);
            var a = Job.class.getDeclaredField("attempts");
            a.setAccessible(true);
            a.setInt(job, attempts);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void successRecordsExecutionAndReleasesDependents() {
        Job job = runningJob(1, 3);
        Job dependent = Job.create("echo", "{}", 5, Instant.now(), null, 3, 1000, Set.of(job.getId()), null, null);
        assertThat(dependent.getStatus()).isEqualTo(JobStatus.WAITING);

        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobs.findDependents(job.getId())).thenReturn(List.of(dependent));
        when(jobs.countByIdInAndStatus(anyCollection(), eq(JobStatus.SUCCEEDED))).thenReturn(1L);

        service.succeed(job.getId(), "{\"ok\":true}", Instant.now().minusSeconds(1));

        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.getResult()).isEqualTo("{\"ok\":true}");
        assertThat(job.getLeaseOwner()).isNull();
        assertThat(dependent.getStatus()).isEqualTo(JobStatus.PENDING);

        ArgumentCaptor<JobExecution> exec = ArgumentCaptor.forClass(JobExecution.class);
        verify(executions).save(exec.capture());
        assertThat(exec.getValue().getOutcome()).isEqualTo("SUCCEEDED");
        assertThat(exec.getValue().getNodeId()).isEqualTo("test-node");
        verify(events).terminal(job);
    }

    @Test
    void dependentStaysWaitingUntilEveryDependencySucceeded() {
        Job job = runningJob(1, 3);
        Job dependent = Job.create("echo", "{}", 5, Instant.now(), null, 3, 1000,
                Set.of(job.getId(), UUID.randomUUID()), null, null);

        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobs.findDependents(job.getId())).thenReturn(List.of(dependent));
        when(jobs.countByIdInAndStatus(anyCollection(), eq(JobStatus.SUCCEEDED))).thenReturn(1L); // of 2

        service.succeed(job.getId(), "{}", Instant.now());

        assertThat(dependent.getStatus()).isEqualTo(JobStatus.WAITING);
    }

    @Test
    void failureWithAttemptsLeftSchedulesRetryWithBackoff() {
        Job job = runningJob(1, 3);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(backoff.delayFor(1)).thenReturn(Duration.ofMinutes(5));

        Instant before = Instant.now();
        service.fail(job.getId(), "boom", before);

        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getLastError()).isEqualTo("boom");
        assertThat(job.getRunAt()).isAfterOrEqualTo(before.plus(Duration.ofMinutes(5)).minusSeconds(1));
        verify(events).retryScheduled(job);
        verify(events, never()).terminal(any());
    }

    @Test
    void failureOnLastAttemptDeadLettersAndCancelsDependents() {
        Job job = runningJob(3, 3);
        Job dependent = Job.create("echo", "{}", 5, Instant.now(), null, 3, 1000, Set.of(job.getId()), null, null);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobs.findDependents(job.getId())).thenReturn(List.of(dependent));
        when(jobs.findDependents(dependent.getId())).thenReturn(List.of());

        service.fail(job.getId(), "boom", Instant.now());

        assertThat(job.getStatus()).isEqualTo(JobStatus.DEAD);
        assertThat(dependent.getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(dependent.getLastError()).contains("dead-lettered");
        verify(events).terminal(job);
        verify(events).terminal(dependent);
        verifyNoInteractions(backoff);
    }

    @Test
    void staleResultFromReapedWorkerIsIgnored() {
        Job job = runningJob(1, 3);
        setStatus(job, JobStatus.PENDING, 1); // reaper already handed it back
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));

        service.succeed(job.getId(), "{}", Instant.now());

        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        verifyNoInteractions(executions, events);
    }

    @Test
    void cronJobSpawnsNextOccurrenceOnCompletion() {
        Job job = Job.create("echo", "{}", 5, Instant.now(), "0 0 * * * *", 3, 1000, Set.of(), null, null);
        setStatus(job, JobStatus.RUNNING, 1);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobs.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        service.succeed(job.getId(), "{}", Instant.now());

        ArgumentCaptor<Job> next = ArgumentCaptor.forClass(Job.class);
        verify(jobs).save(next.capture());
        assertThat(next.getValue().getId()).isNotEqualTo(job.getId());
        assertThat(next.getValue().getCronExpression()).isEqualTo("0 0 * * * *");
        assertThat(next.getValue().getRunAt()).isAfter(Instant.now());
        assertThat(next.getValue().getStatus()).isEqualTo(JobStatus.PENDING);
    }

    @Test
    void cancelRefusesTerminalJobs() {
        Job job = runningJob(1, 3);
        setStatus(job, JobStatus.SUCCEEDED, 1);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.cancel(job.getId(), "nope"))
                .isInstanceOf(InvalidJobException.class);
    }

    @Test
    void resurrectResetsAttemptBudget() {
        Job job = runningJob(3, 3);
        setStatus(job, JobStatus.DEAD, 3);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));

        Job out = service.resurrect(job.getId());

        assertThat(out.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(out.getAttempts()).isZero();
        assertThat(out.hasAttemptsLeft()).isTrue();
    }

    @Test
    void reaperReturnsExpiredLeaseToQueue() {
        Job job = runningJob(1, 3);
        job.extendLease(Instant.now().minusSeconds(5));
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));

        service.reclaimExpired(job.getId());

        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getLastError()).contains("Lease expired");
        verify(events).leaseReclaimed(job);
    }

    @Test
    void reaperLeavesRenewedLeaseAlone() {
        Job job = runningJob(1, 3);
        job.extendLease(Instant.now().plusSeconds(30));
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));

        service.reclaimExpired(job.getId());

        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        verifyNoInteractions(events);
    }
}
