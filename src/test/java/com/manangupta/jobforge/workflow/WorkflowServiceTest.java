package com.manangupta.jobforge.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manangupta.jobforge.config.SchedulerProperties;
import com.manangupta.jobforge.domain.Job;
import com.manangupta.jobforge.domain.JobStatus;
import com.manangupta.jobforge.handler.JobHandlerRegistry;
import com.manangupta.jobforge.repository.JobRepository;
import com.manangupta.jobforge.service.InvalidJobException;
import com.manangupta.jobforge.service.JsonCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

    @Mock JobRepository jobs;
    @Mock JobHandlerRegistry registry;
    WorkflowService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowService(jobs, registry, new JsonCodec(new ObjectMapper()), new SchedulerProperties());
    }

    private static WorkflowSpec.Step step(String id, String... deps) {
        return new WorkflowSpec.Step(id, "echo", Map.of(), List.of(deps), null, null, null);
    }

    @Test
    void createsJobsInTopologicalOrderWithResolvedDependencies() {
        when(registry.supports("echo")).thenReturn(true);
        when(jobs.saveAll(anyCollection())).thenAnswer(inv -> new ArrayList<>(inv.getArgument(0)));

        // Declared out of order on purpose: c depends on b depends on a.
        WorkflowView view = service.submit(new WorkflowSpec("wf", List.of(step("c", "b"), step("a"), step("b", "a")), null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Job>> saved = ArgumentCaptor.forClass(Collection.class);
        verify(jobs).saveAll(saved.capture());
        List<Job> created = new ArrayList<>(saved.getValue());
        assertThat(created).extracting(Job::getStepId).containsExactly("a", "b", "c");
        assertThat(created.get(0).getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(created.get(1).getStatus()).isEqualTo(JobStatus.WAITING);
        assertThat(created.get(1).getDependsOn()).containsExactly(created.get(0).getId());
        assertThat(created.get(2).getDependsOn()).containsExactly(created.get(1).getId());
        assertThat(created).allMatch(j -> j.getWorkflowId().equals(view.id()));
        assertThat(view.status()).isEqualTo("RUNNING");
        assertThat(view.totalSteps()).isEqualTo(3);
    }

    @Test
    void rejectsCycles() {
        when(registry.supports("echo")).thenReturn(true);
        assertThatThrownBy(() -> service.submit(new WorkflowSpec("wf",
                List.of(step("a", "c"), step("b", "a"), step("c", "b"), step("d")), null)))
                .isInstanceOf(InvalidJobException.class)
                .hasMessageContaining("cycle")
                .hasMessageContaining("a").hasMessageContaining("b").hasMessageContaining("c");
        verify(jobs, never()).saveAll(anyCollection());
    }

    @Test
    void rejectsUnknownDependenciesDuplicateIdsAndUnknownTypes() {
        when(registry.supports("echo")).thenReturn(true);
        assertThatThrownBy(() -> service.submit(new WorkflowSpec("wf", List.of(step("a", "ghost")), null)))
                .isInstanceOf(InvalidJobException.class).hasMessageContaining("unknown step 'ghost'");
        assertThatThrownBy(() -> service.submit(new WorkflowSpec("wf", List.of(step("a"), step("a")), null)))
                .isInstanceOf(InvalidJobException.class).hasMessageContaining("Duplicate step id");
        assertThatThrownBy(() -> service.submit(new WorkflowSpec("wf", List.of(step("a", "a")), null)))
                .isInstanceOf(InvalidJobException.class).hasMessageContaining("depend on itself");

        when(registry.supports("teleport")).thenReturn(false);
        when(registry.types()).thenReturn(Set.of("echo"));
        assertThatThrownBy(() -> service.submit(new WorkflowSpec("wf",
                List.of(new WorkflowSpec.Step("x", "teleport", Map.of(), List.of(), null, null, null)), null)))
                .isInstanceOf(InvalidJobException.class).hasMessageContaining("unknown job type 'teleport'");
    }

    @Test
    void rejectsEmptyWorkflowsAndBadStepIds() {
        assertThatThrownBy(() -> service.submit(new WorkflowSpec("wf", List.of(), null)))
                .isInstanceOf(InvalidJobException.class);
        assertThatThrownBy(() -> service.submit(new WorkflowSpec("wf", List.of(step("bad id!")), null)))
                .isInstanceOf(InvalidJobException.class).hasMessageContaining("Step id");
        verify(registry, never()).supports(anyString());
    }
}
