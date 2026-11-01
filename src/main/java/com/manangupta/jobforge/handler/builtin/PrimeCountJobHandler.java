package com.manangupta.jobforge.handler.builtin;

import com.manangupta.jobforge.handler.JobContext;
import com.manangupta.jobforge.handler.JobHandler;
import com.manangupta.jobforge.handler.JobResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/** CPU-bound work: counts primes below {@code n} (default 200,000) with a sieve. */
@Component
public class PrimeCountJobHandler implements JobHandler {

    @Override
    public String type() {
        return "primes";
    }

    @Override
    public String description() {
        return "Counts primes below payload.n (default 200000). Simulates CPU-bound work.";
    }

    @Override
    public JobResult execute(JobContext ctx) throws InterruptedException {
        int n = Math.max(2, ctx.getInt("n", 200_000));
        boolean[] composite = new boolean[n];
        int count = 0;
        for (int i = 2; i < n; i++) {
            if ((i & 0xFFFF) == 0 && Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("interrupted");
            }
            if (!composite[i]) {
                count++;
                for (long j = (long) i * i; j < n; j += i) {
                    composite[(int) j] = true;
                }
            }
        }
        return JobResult.of(Map.of("n", n, "primeCount", count));
    }
}
