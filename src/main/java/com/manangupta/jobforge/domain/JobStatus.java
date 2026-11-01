package com.manangupta.jobforge.domain;

/**
 * Lifecycle of a job.
 *
 * <pre>
 *  WAITING ──(all deps succeed)──► PENDING ──(claimed)──► RUNNING ──► SUCCEEDED
 *     │                               ▲                      │
 *     │                               └──(retry w/ backoff)──┤
 *     │                                                      ▼
 *     └──(a dep dies)──► CANCELLED                          DEAD (attempts exhausted)
 * </pre>
 */
public enum JobStatus {
    /** Blocked on one or more upstream dependencies. */
    WAITING,
    /** Eligible to run once {@code runAt} has passed. */
    PENDING,
    /** Claimed by a worker that holds a live lease. */
    RUNNING,
    /** Finished successfully. Terminal. */
    SUCCEEDED,
    /** Exhausted all attempts; sits in the dead-letter queue until manually retried. Terminal. */
    DEAD,
    /** Cancelled by an operator or because an upstream dependency died. Terminal. */
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == DEAD || this == CANCELLED;
    }
}
