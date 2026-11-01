package com.manangupta.jobforge.service;

import com.manangupta.jobforge.domain.Job;
import com.manangupta.jobforge.domain.JobStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedDeque;

/** Micrometer counters plus a 60-second sliding window used for the dashboard's throughput figure. */
@Component
public class MetricsListener implements JobEvents.JobListener {

    private static final long WINDOW_MS = 60_000;

    private final MeterRegistry meters;
    private final ConcurrentLinkedDeque<Long> completions = new ConcurrentLinkedDeque<>();

    public MetricsListener(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public void onTerminal(Job job) {
        counter("jobforge.jobs.terminal", "type", job.getType(), "status", job.getStatus().name()).increment();
        if (job.getStatus() == JobStatus.SUCCEEDED) {
            long now = System.currentTimeMillis();
            completions.addLast(now);
            prune(now);
        }
    }

    @Override
    public void onRetryScheduled(Job job) {
        counter("jobforge.jobs.retries", "type", job.getType()).increment();
    }

    @Override
    public void onLeaseReclaimed(Job job) {
        counter("jobforge.leases.reclaimed", "type", job.getType()).increment();
    }

    /** Jobs that succeeded in the last 60 seconds, as a per-second rate. */
    public double throughputPerSecond() {
        long now = System.currentTimeMillis();
        prune(now);
        return completions.size() / (WINDOW_MS / 1000.0);
    }

    public int succeededLastMinute() {
        prune(System.currentTimeMillis());
        return completions.size();
    }

    private void prune(long now) {
        Long head;
        while ((head = completions.peekFirst()) != null && now - head > WINDOW_MS) {
            completions.pollFirst();
        }
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(meters);
    }

    public Instant windowStart() {
        return Instant.ofEpochMilli(System.currentTimeMillis() - WINDOW_MS);
    }
}
