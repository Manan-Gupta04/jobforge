package com.manangupta.jobforge.handler.builtin;

import com.manangupta.jobforge.handler.JobContext;
import com.manangupta.jobforge.handler.JobHandler;
import com.manangupta.jobforge.handler.JobResult;
import org.springframework.stereotype.Component;

/** Always throws. Demonstrates the dead-letter queue. */
@Component
public class AlwaysFailJobHandler implements JobHandler {

    @Override
    public String type() {
        return "fail";
    }

    @Override
    public String description() {
        return "Always fails. Demonstrates dead-lettering after max attempts.";
    }

    @Override
    public JobResult execute(JobContext ctx) {
        throw new RuntimeException(ctx.getString("message", "Simulated permanent failure"));
    }
}
