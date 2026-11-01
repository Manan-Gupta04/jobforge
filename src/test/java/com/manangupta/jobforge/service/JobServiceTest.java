package com.manangupta.jobforge.service;

import com.manangupta.jobforge.config.SchedulerProperties;
import com.manangupta.jobforge.domain.Job;
import com.manangupta.jobforge.handler.JobHandlerRegistry;
import com.manangupta.jobforge.repository.JobExecutionRepository;
import com.manangupta.jobforge.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    @Mock JobRepository jobs;
    @Mock JobExecutionRepository executions;
    @Mock JobHandlerRegistry registry;
    @Mock JobStateService state;

    JobService service;

    @BeforeEach
    void setUp() {
        service = new JobService(jobs, executions, registry,
                new JsonCodec(new com.fasterxml.jackson.databind.ObjectMapper()),
                new SchedulerProperties(), state);
    }

    @Test
    void rejectsUnknownType() {
        when(registry.supports("nope")).thenReturn(false);
        when(registry.types()).thenReturn(Set.of("echo"));

        assertThatThrownBy(() -> service.submit(JobSpec.of("nope", Map.of())))
                .isInstanceOf(InvalidJobException.class)
                .hasMessageContaining("Unknown job type")
                .hasMessageContaining("echo");
        verifyNoInteractions(jobs);
    }

    @Test
    void rejectsInvalidCron() {
        when(registry.supports("echo")).thenReturn(true);

        assertThatThrownBy(() -> service.submit(JobSpec.of("echo", Map.of()).withCron("not a cron")))
                .isInstanceOf(InvalidJobException.class)
                .hasMessageContaining("Invalid cron");
    }

    @Test
    void cronJobGetsItsFirstRunAtFromTheExpression() {
        when(registry.supports("echo")).thenReturn(true);
        when(jobs.saveAndFlush(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        SubmitResult r = service.submit(JobSpec.of("echo", Map.of()).withCron("0 0 0 1 1 *"));

        assertThat(r.created()).isTrue();
        assertThat(r.job().getRunAt()).isAfter(Instant.now());
        assertThat(r.job().getCronExpression()).isEqualTo("0 0 0 1 1 *");
    }

    @Test
    void rejectsUnknownDependency() {
        when(registry.supports("echo")).thenReturn(true);
        UUID missing = UUID.randomUUID();
        when(jobs.findAllById(Set.of(missing))).thenReturn(java.util.List.of());

        assertThatThrownBy(() -> service.submit(JobSpec.of("echo", Map.of()).withDependsOn(Set.of(missing))))
                .isInstanceOf(InvalidJobException.class)
                .hasMessageContaining("Unknown dependency");
    }

    @Test
    void idempotentSubmitReturnsExistingJob() {
        when(registry.supports("echo")).thenReturn(true);
        Job existing = Job.create("echo", "{}", 5, Instant.now(), null, 3, 1000, Set.of(), "key-1", null);
        when(jobs.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        SubmitResult r = service.submit(JobSpec.of("echo", Map.of()).withIdempotencyKey("key-1"));

        assertThat(r.created()).isFalse();
        assertThat(r.job()).isSameAs(existing);
        verify(jobs, never()).saveAndFlush(any());
    }

    @Test
    void idempotentSubmitSurvivesLosingTheInsertRace() {
        when(registry.supports("echo")).thenReturn(true);
        Job winner = Job.create("echo", "{}", 5, Instant.now(), null, 3, 1000, Set.of(), "key-2", null);
        when(jobs.findByIdempotencyKey("key-2"))
                .thenReturn(Optional.empty())       // first check: nothing there yet
                .thenReturn(Optional.of(winner));   // after the unique violation: the other writer won
        when(jobs.saveAndFlush(any(Job.class))).thenThrow(new DataIntegrityViolationException("uq_jobs_idempotency"));

        SubmitResult r = service.submit(JobSpec.of("echo", Map.of()).withIdempotencyKey("key-2"));

        assertThat(r.created()).isFalse();
        assertThat(r.job()).isSameAs(winner);
    }

    @Test
    void appliesDefaultsFromProperties() {
        when(registry.supports("echo")).thenReturn(true);
        when(jobs.saveAndFlush(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        Job job = service.submit(JobSpec.of("echo", Map.of("k", "v"))).job();

        assertThat(job.getPriority()).isEqualTo(5);
        assertThat(job.getMaxAttempts()).isEqualTo(3);
        assertThat(job.getTimeoutMs()).isEqualTo(60_000);
        assertThat(job.getPayload()).isEqualTo("{\"k\":\"v\"}");
    }
}
