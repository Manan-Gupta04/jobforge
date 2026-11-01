package com.manangupta.jobforge.service;

import com.manangupta.jobforge.config.NodeIdentity;
import com.manangupta.jobforge.config.SchedulerProperties;
import com.manangupta.jobforge.domain.Job;
import com.manangupta.jobforge.domain.JobStatus;
import com.manangupta.jobforge.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Atomically assigns due jobs to this node.
 *
 * <p>Each candidate is claimed with a single conditional UPDATE keyed on the row version the
 * node read, committed in its own short transaction. Because the database serialises writes to
 * a row, exactly one of N racing nodes wins each job; losers see zero rows updated (or a lock
 * conflict) and simply move on to the next candidate. This works on any SQL database (H2,
 * PostgreSQL, MySQL) without advisory locks or {@code SKIP LOCKED}, and holds no lock while
 * the candidate list is scanned.
 */
@Service
public class JobClaimService {

    private static final Logger log = LoggerFactory.getLogger(JobClaimService.class);

    private final JobRepository jobs;
    private final NodeIdentity node;
    private final SchedulerProperties props;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    public JobClaimService(JobRepository jobs, NodeIdentity node, SchedulerProperties props,
                           PlatformTransactionManager txManager) {
        this.jobs = jobs;
        this.node = node;
        this.props = props;
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.writeTx = new TransactionTemplate(txManager);
        this.writeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Claims up to {@code max} jobs for this node and returns their ids. */
    public List<UUID> claimBatch(int max) {
        if (max <= 0) {
            return List.of();
        }
        Instant now = Instant.now();
        // Over-fetch so that losing a few races to other nodes still fills our slots.
        List<Job> candidates = readTx.execute(status ->
                jobs.findClaimable(JobStatus.PENDING, now, PageRequest.of(0, max * 3)));
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<UUID> claimed = new ArrayList<>(max);
        for (Job candidate : candidates) {
            if (claimed.size() >= max) {
                break;
            }
            if (tryClaim(candidate, now)) {
                claimed.add(candidate.getId());
            }
        }
        return claimed;
    }

    private boolean tryClaim(Job candidate, Instant now) {
        Instant leaseUntil = now.plus(leaseDuration());
        try {
            Integer won = writeTx.execute(status -> jobs.claim(candidate.getId(), candidate.getVersion(),
                    node.id(), leaseUntil, now, JobStatus.RUNNING, JobStatus.PENDING));
            return won != null && won == 1;
        } catch (RuntimeException raced) {
            // Lock timeout / serialisation failure: another node got there first.
            log.debug("Lost claim race for job {}: {}", candidate.getId(), raced.toString());
            return false;
        }
    }

    /** Heartbeat. Returns false if the lease was lost (reaped or cancelled), telling the worker to stop. */
    public boolean extendLease(UUID jobId) {
        Instant until = Instant.now().plus(leaseDuration());
        try {
            Integer n = writeTx.execute(status ->
                    jobs.extendLease(jobId, node.id(), until, JobStatus.RUNNING));
            return n != null && n == 1;
        } catch (RuntimeException e) {
            log.warn("Heartbeat for job {} failed: {}", jobId, e.toString());
            return true; // transient DB trouble is not proof the lease is gone; keep working
        }
    }

    public Duration leaseDuration() {
        return Duration.ofSeconds(props.getLeaseSeconds());
    }
}
