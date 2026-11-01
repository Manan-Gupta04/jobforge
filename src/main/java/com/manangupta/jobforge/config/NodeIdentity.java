package com.manangupta.jobforge.config;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.UUID;

/** Identifies this JVM in a cluster; used as the lease owner on claimed jobs. */
@Component
public class NodeIdentity {

    private final String nodeId;

    public NodeIdentity(SchedulerProperties props) {
        if (props.getNodeId() != null && !props.getNodeId().isBlank()) {
            this.nodeId = props.getNodeId();
        } else {
            String host;
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = "node";
            }
            this.nodeId = host + "-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    public String id() {
        return nodeId;
    }
}
