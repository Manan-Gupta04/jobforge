package com.manangupta.jobforge.service;

import com.manangupta.jobforge.domain.Job;
import org.springframework.stereotype.Component;

import java.util.List;

/** Fan-out point for job lifecycle events: metrics, webhooks, and anything else that listens. */
@Component
public class JobEvents {

    private final List<JobListener> listeners;

    public JobEvents(List<JobListener> listeners) {
        this.listeners = listeners;
    }

    public void terminal(Job job) {
        listeners.forEach(l -> l.onTerminal(job));
    }

    public void retryScheduled(Job job) {
        listeners.forEach(l -> l.onRetryScheduled(job));
    }

    public void leaseReclaimed(Job job) {
        listeners.forEach(l -> l.onLeaseReclaimed(job));
    }

    public interface JobListener {
        default void onTerminal(Job job) {
        }

        default void onRetryScheduled(Job job) {
        }

        default void onLeaseReclaimed(Job job) {
        }
    }
}
