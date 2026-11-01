package com.manangupta.jobforge.service;

import com.manangupta.jobforge.config.NodeIdentity;
import com.manangupta.jobforge.config.SchedulerProperties;
import com.manangupta.jobforge.domain.ClusterNode;
import com.manangupta.jobforge.repository.ClusterNodeRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Publishes this node's heartbeat and lists the nodes that have been seen recently. */
@Service
public class ClusterRegistry {

    public static final Duration LIVENESS_WINDOW = Duration.ofSeconds(15);

    private final ClusterNodeRepository nodes;
    private final NodeIdentity identity;
    private final SchedulerProperties props;
    private final JobDispatcher dispatcher;
    private final Instant startedAt = Instant.now();

    public ClusterRegistry(ClusterNodeRepository nodes, NodeIdentity identity, SchedulerProperties props,
                           JobDispatcher dispatcher) {
        this.nodes = nodes;
        this.identity = identity;
        this.props = props;
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 0)
    @Transactional
    public void heartbeat() {
        Instant now = Instant.now();
        ClusterNode me = nodes.findById(identity.id())
                .orElseGet(() -> new ClusterNode(identity.id(), props.getWorkers(), 0, startedAt, now));
        me.beat(dispatcher.inFlight(), now);
        nodes.save(me);
    }

    public List<ClusterNode> liveNodes() {
        return nodes.findByLastSeenAfterOrderByStartedAtAsc(Instant.now().minus(LIVENESS_WINDOW));
    }

    @PreDestroy
    void deregister() {
        try {
            nodes.deleteById(identity.id());
        } catch (Exception ignored) {
            // best effort; the liveness window will expire the row anyway
        }
    }
}
