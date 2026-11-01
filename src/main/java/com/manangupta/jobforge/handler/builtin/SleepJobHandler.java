package com.manangupta.jobforge.handler.builtin;

import com.manangupta.jobforge.handler.JobContext;
import com.manangupta.jobforge.handler.JobHandler;
import com.manangupta.jobforge.handler.JobResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Simulates I/O-bound work by sleeping for {@code ms} milliseconds (default 500). */
@Component
public class SleepJobHandler implements JobHandler {

    @Override
    public String type() {
        return "sleep";
    }

    @Override
    public String description() {
        return "Sleeps for payload.ms milliseconds (default 500). Simulates I/O-bound work; use to see timeouts.";
    }

    @Override
    public JobResult execute(JobContext ctx) throws InterruptedException {
        long ms = ctx.getLong("ms", 500);
        Thread.sleep(ms);
        return JobResult.of(Map.of("sleptMs", ms));
    }
}
