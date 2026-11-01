package com.manangupta.jobforge.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Heartbeat row for every node sharing the database; lets the dashboard show the cluster. */
@Entity
@Table(name = "cluster_nodes")
public class ClusterNode {

    @Id
    @Column(name = "node_id", length = 120)
    private String nodeId;

    @Column(nullable = false)
    private int workers;

    @Column(name = "in_flight", nullable = false)
    private int inFlight;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;

    protected ClusterNode() {
    }

    public ClusterNode(String nodeId, int workers, int inFlight, Instant startedAt, Instant lastSeen) {
        this.nodeId = nodeId;
        this.workers = workers;
        this.inFlight = inFlight;
        this.startedAt = startedAt;
        this.lastSeen = lastSeen;
    }

    public void beat(int inFlight, Instant now) {
        this.inFlight = inFlight;
        this.lastSeen = now;
    }

    public String getNodeId() { return nodeId; }
    public int getWorkers() { return workers; }
    public int getInFlight() { return inFlight; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getLastSeen() { return lastSeen; }
}
