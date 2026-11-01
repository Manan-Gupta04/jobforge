package com.manangupta.jobforge.workflow;

import com.manangupta.jobforge.config.SchedulerProperties;
import com.manangupta.jobforge.domain.Job;
import com.manangupta.jobforge.domain.JobStatus;
import com.manangupta.jobforge.handler.JobHandlerRegistry;
import com.manangupta.jobforge.repository.JobRepository;
import com.manangupta.jobforge.service.InvalidJobException;
import com.manangupta.jobforge.service.JobNotFoundException;
import com.manangupta.jobforge.service.JsonCodec;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Turns a {@link WorkflowSpec} into a set of dependent jobs in one transaction, and reports on a
 * workflow's progress. Validates that step ids are unique, dependencies exist, and the graph is
 * acyclic before anything is written.
 */
@Service
public class WorkflowService {

    private static final int MAX_STEPS = 200;

    private final JobRepository jobs;
    private final JobHandlerRegistry registry;
    private final JsonCodec json;
    private final SchedulerProperties props;

    public WorkflowService(JobRepository jobs, JobHandlerRegistry registry, JsonCodec json, SchedulerProperties props) {
        this.jobs = jobs;
        this.registry = registry;
        this.json = json;
        this.props = props;
    }

    @Transactional
    public WorkflowView submit(WorkflowSpec spec) {
        List<WorkflowSpec.Step> steps = validate(spec);
        UUID workflowId = UUID.randomUUID();
        String name = spec.name() == null || spec.name().isBlank() ? "workflow" : spec.name().trim();

        // Create in topological order so every dependency already has an id.
        Map<String, Job> created = new LinkedHashMap<>();
        int order = 0;
        for (WorkflowSpec.Step step : topologicalOrder(steps)) {
            Set<UUID> deps = new HashSet<>();
            for (String dep : nz(step.dependsOn())) {
                deps.add(created.get(dep).getId());
            }
            Job job = Job.create(
                    step.type(),
                    json.write(step.payload() == null ? Map.of() : step.payload()),
                    step.priority() == null ? 5 : step.priority(),
                    Instant.now(),
                    null,
                    step.maxAttempts() == null ? props.getDefaultMaxAttempts() : step.maxAttempts(),
                    step.timeoutMs() == null ? props.getDefaultTimeoutMs() : step.timeoutMs(),
                    deps,
                    null,
                    spec.callbackUrl());
            job.attachToWorkflow(workflowId, name, step.id(), order++);
            created.put(step.id(), job);
        }
        jobs.saveAll(created.values());
        return view(workflowId, new ArrayList<>(created.values()));
    }

    public WorkflowView get(UUID workflowId) {
        List<Job> steps = jobs.findByWorkflowIdOrderByStepOrderAsc(workflowId);
        if (steps.isEmpty()) {
            throw new JobNotFoundException(workflowId);
        }
        return view(workflowId, steps);
    }

    public List<WorkflowView> recent(int limit) {
        List<WorkflowView> out = new ArrayList<>();
        for (Object[] row : jobs.findRecentWorkflows(PageRequest.of(0, Math.min(limit, 50)))) {
            UUID id = (UUID) row[0];
            out.add(view(id, jobs.findByWorkflowIdOrderByStepOrderAsc(id)));
        }
        return out;
    }

    // --- validation -------------------------------------------------------------------------

    private List<WorkflowSpec.Step> validate(WorkflowSpec spec) {
        if (spec == null || spec.steps() == null || spec.steps().isEmpty()) {
            throw new InvalidJobException("A workflow needs at least one step");
        }
        if (spec.steps().size() > MAX_STEPS) {
            throw new InvalidJobException("A workflow may have at most " + MAX_STEPS + " steps");
        }
        Set<String> ids = new HashSet<>();
        for (WorkflowSpec.Step s : spec.steps()) {
            if (s.id() == null || !s.id().matches("[A-Za-z0-9_-]{1,80}")) {
                throw new InvalidJobException("Step id '" + s.id() + "' must be 1-80 chars of letters, digits, '_' or '-'");
            }
            if (!ids.add(s.id())) {
                throw new InvalidJobException("Duplicate step id '" + s.id() + "'");
            }
            if (s.type() == null || !registry.supports(s.type())) {
                throw new InvalidJobException("Step '" + s.id() + "': unknown job type '" + s.type()
                        + "'. Registered types: " + registry.types());
            }
            if (s.priority() != null && (s.priority() < 0 || s.priority() > 10)) {
                throw new InvalidJobException("Step '" + s.id() + "': priority must be 0-10");
            }
            if (s.maxAttempts() != null && (s.maxAttempts() < 1 || s.maxAttempts() > 50)) {
                throw new InvalidJobException("Step '" + s.id() + "': maxAttempts must be 1-50");
            }
        }
        for (WorkflowSpec.Step s : spec.steps()) {
            for (String dep : nz(s.dependsOn())) {
                if (!ids.contains(dep)) {
                    throw new InvalidJobException("Step '" + s.id() + "' depends on unknown step '" + dep + "'");
                }
                if (dep.equals(s.id())) {
                    throw new InvalidJobException("Step '" + s.id() + "' cannot depend on itself");
                }
            }
        }
        return spec.steps();
    }

    /** Kahn's algorithm; throws on cycles. */
    static List<WorkflowSpec.Step> topologicalOrder(List<WorkflowSpec.Step> steps) {
        Map<String, WorkflowSpec.Step> byId = new LinkedHashMap<>();
        steps.forEach(s -> byId.put(s.id(), s));
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (WorkflowSpec.Step s : steps) {
            indegree.put(s.id(), nz(s.dependsOn()).size());
            for (String dep : nz(s.dependsOn())) {
                dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(s.id());
            }
        }
        Deque<String> ready = new ArrayDeque<>();
        indegree.forEach((id, d) -> {
            if (d == 0) {
                ready.add(id);
            }
        });
        List<WorkflowSpec.Step> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(byId.get(id));
            for (String next : dependents.getOrDefault(id, List.of())) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        if (order.size() != steps.size()) {
            Set<String> stuck = new TreeSet<>(indegree.keySet());
            order.forEach(s -> stuck.remove(s.id()));
            throw new InvalidJobException("Workflow has a dependency cycle involving steps " + stuck);
        }
        return order;
    }

    private static List<String> nz(List<String> l) {
        return l == null ? List.of() : l;
    }

    // --- view ----------------------------------------------------------------------------------

    private WorkflowView view(UUID id, List<Job> steps) {
        Map<JobStatus, Long> counts = new EnumMap<>(JobStatus.class);
        for (Job j : steps) {
            counts.merge(j.getStatus(), 1L, Long::sum);
        }
        String status;
        if (counts.getOrDefault(JobStatus.DEAD, 0L) > 0 || counts.getOrDefault(JobStatus.CANCELLED, 0L) > 0) {
            status = "FAILED";
        } else if (counts.getOrDefault(JobStatus.SUCCEEDED, 0L) == steps.size()) {
            status = "SUCCEEDED";
        } else if (counts.getOrDefault(JobStatus.RUNNING, 0L) > 0 || counts.getOrDefault(JobStatus.PENDING, 0L) > 0) {
            status = "RUNNING";
        } else {
            status = "WAITING";
        }
        Instant started = steps.stream().map(Job::getStartedAt).filter(Objects::nonNull).min(Instant::compareTo).orElse(null);
        Instant finished = status.equals("SUCCEEDED") || status.equals("FAILED")
                ? steps.stream().map(Job::getFinishedAt).filter(Objects::nonNull).max(Instant::compareTo).orElse(null)
                : null;
        List<WorkflowView.StepView> views = new ArrayList<>();
        for (Job j : steps) {
            views.add(new WorkflowView.StepView(j.getStepId(), j.getId(), j.getType(), j.getStatus(), j.getAttempts(),
                    j.getMaxAttempts(), j.getStartedAt(), j.getFinishedAt(), j.getLastError(), json.read(j.getResult())));
        }
        String name = steps.isEmpty() ? null : steps.get(0).getWorkflowName();
        Instant created = steps.stream().map(Job::getCreatedAt).min(Instant::compareTo).orElse(null);
        return new WorkflowView(id, name, status, steps.size(), counts.getOrDefault(JobStatus.SUCCEEDED, 0L).intValue(),
                created, started, finished, views);
    }
}
