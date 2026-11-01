package com.manangupta.jobforge.api;

import com.manangupta.jobforge.config.NodeIdentity;
import com.manangupta.jobforge.domain.ClusterNode;
import com.manangupta.jobforge.domain.JobStatus;
import com.manangupta.jobforge.handler.JobHandler;
import com.manangupta.jobforge.handler.JobHandlerRegistry;
import com.manangupta.jobforge.repository.JobRepository;
import com.manangupta.jobforge.service.ClusterRegistry;
import com.manangupta.jobforge.service.JobDispatcher;
import com.manangupta.jobforge.service.MetricsListener;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "System", description = "Cluster state, handlers and live statistics")
public class SystemController {

    private final JobRepository jobs;
    private final JobDispatcher dispatcher;
    private final ClusterRegistry cluster;
    private final MetricsListener metrics;
    private final JobHandlerRegistry handlers;
    private final NodeIdentity node;

    public SystemController(JobRepository jobs, JobDispatcher dispatcher, ClusterRegistry cluster,
                            MetricsListener metrics, JobHandlerRegistry handlers, NodeIdentity node) {
        this.jobs = jobs;
        this.dispatcher = dispatcher;
        this.cluster = cluster;
        this.metrics = metrics;
        this.handlers = handlers;
        this.node = node;
    }

    @GetMapping("/stats")
    @Operation(summary = "Snapshot of queue depth by status, this node's worker utilisation, throughput and live nodes")
    public Map<String, Object> stats() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (JobStatus s : JobStatus.values()) {
            byStatus.put(s.name(), 0L);
        }
        for (Object[] row : jobs.countGroupedByStatus()) {
            byStatus.put(((JobStatus) row[0]).name(), (Long) row[1]);
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (ClusterNode n : cluster.liveNodes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeId", n.getNodeId());
            m.put("workers", n.getWorkers());
            m.put("inFlight", n.getInFlight());
            m.put("startedAt", n.getStartedAt());
            m.put("lastSeen", n.getLastSeen());
            m.put("self", n.getNodeId().equals(node.id()));
            nodes.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timestamp", Instant.now());
        out.put("nodeId", node.id());
        out.put("byStatus", byStatus);
        out.put("workers", dispatcher.workers());
        out.put("inFlight", dispatcher.inFlight());
        out.put("paused", dispatcher.isPaused());
        out.put("succeededLastMinute", metrics.succeededLastMinute());
        out.put("throughputPerSecond", Math.round(metrics.throughputPerSecond() * 100.0) / 100.0);
        out.put("nodes", nodes);
        out.put("handlerTypes", handlers.types());
        return out;
    }

    @GetMapping("/handlers")
    @Operation(summary = "Job types this cluster can execute")
    public List<Map<String, String>> handlers() {
        List<Map<String, String>> out = new ArrayList<>();
        for (JobHandler h : handlers.all()) {
            out.add(Map.of("type", h.type(), "description", h.description()));
        }
        return out;
    }

    @PostMapping("/dispatcher/pause")
    @Operation(summary = "Stop this node from claiming new jobs (in-flight jobs finish)")
    public Map<String, Object> pause() {
        dispatcher.setPaused(true);
        return Map.of("nodeId", node.id(), "paused", true);
    }

    @PostMapping("/dispatcher/resume")
    @Operation(summary = "Resume claiming jobs on this node")
    public Map<String, Object> resume() {
        dispatcher.setPaused(false);
        return Map.of("nodeId", node.id(), "paused", false);
    }
}
