package com.manangupta.jobforge.service;

import com.manangupta.jobforge.domain.Job;
import com.manangupta.jobforge.domain.JobStatus;
import com.manangupta.jobforge.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Fault tolerance for crashed workers. Any job still RUNNING after its lease expired was being
 * run by a node that died or hung, so it is handed back to the queue (or dead-lettered if it has
 * no attempts left). Every node runs a reaper; the per-job transaction makes that safe.
 */
@Service
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final JobRepository jobs;
    private final JobStateService state;

    public LeaseReaper(JobRepository jobs, JobStateService state) {
        this.jobs = jobs;
        this.state = state;
    }

    @Scheduled(fixedDelayString = "${jobforge.reaper-interval-ms:5000}", initialDelay = 5000)
    public void reap() {
        List<Job> expired = jobs.findByStatusAndLeaseUntilBefore(JobStatus.RUNNING, Instant.now());
        for (Job job : expired) {
            try {
                state.reclaimExpired(job.getId());
                log.warn("Reclaimed job {} whose lease on {} expired", job.getId(), job.getLeaseOwner());
            } catch (Exception e) {
                log.error("Failed to reclaim job {}: {}", job.getId(), e.toString());
            }
        }
    }
}
