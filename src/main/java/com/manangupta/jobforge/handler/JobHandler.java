package com.manangupta.jobforge.handler;

/**
 * Strategy interface for job types. Register an implementation as a Spring bean and any job
 * submitted with a matching {@link #type()} is routed to it.
 *
 * <p>Handlers must be idempotent: JobForge guarantees at-least-once execution, so a job whose
 * worker crashed after side effects but before acknowledging may run again.
 */
public interface JobHandler {

    /** Unique, stable job type name, e.g. {@code "send-email"}. */
    String type();

    /** Short human-readable description for the dashboard and API docs. */
    default String description() {
        return "";
    }

    /**
     * Executes the job. Throw any exception to signal failure (the job is retried with backoff);
     * honour thread interruption, which is how timeouts are enforced.
     */
    JobResult execute(JobContext ctx) throws Exception;
}
