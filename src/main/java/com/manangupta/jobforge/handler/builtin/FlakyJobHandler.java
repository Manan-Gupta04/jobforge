package com.manangupta.jobforge.handler.builtin;

import com.manangupta.jobforge.handler.JobContext;
import com.manangupta.jobforge.handler.JobHandler;
import com.manangupta.jobforge.handler.JobResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Fails the first {@code failTimes} attempts (default 2) and then succeeds.
 * Demonstrates retries with exponential backoff.
 */
@Component
public class FlakyJobHandler implements JobHandler {

    @Override
    public String type() {
        return "flaky";
    }

    @Override
    public String description() {
        return "Fails the first payload.failTimes attempts (default 2), then succeeds. Demonstrates retry/backoff.";
    }

    @Override
    public JobResult execute(JobContext ctx) {
        int failTimes = ctx.getInt("failTimes", 2);
        if (ctx.attempt() <= failTimes) {
            throw new IllegalStateException("Simulated transient failure on attempt " + ctx.attempt()
                    + " of " + ctx.maxAttempts());
        }
        return JobResult.of(Map.of("succeededOnAttempt", ctx.attempt()));
    }
}
