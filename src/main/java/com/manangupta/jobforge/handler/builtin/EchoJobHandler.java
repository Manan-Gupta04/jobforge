package com.manangupta.jobforge.handler.builtin;

import com.manangupta.jobforge.handler.JobContext;
import com.manangupta.jobforge.handler.JobHandler;
import com.manangupta.jobforge.handler.JobResult;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** Returns its payload. Handy for smoke tests and throughput benchmarks. */
@Component
public class EchoJobHandler implements JobHandler {

    @Override
    public String type() {
        return "echo";
    }

    @Override
    public String description() {
        return "Returns the payload unchanged. Useful for smoke tests and benchmarks.";
    }

    @Override
    public JobResult execute(JobContext ctx) {
        Map<String, Object> out = new HashMap<>(ctx.payload());
        out.put("echoedAttempt", ctx.attempt());
        return JobResult.of(out);
    }
}
