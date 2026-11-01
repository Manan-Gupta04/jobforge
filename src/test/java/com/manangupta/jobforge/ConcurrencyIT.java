package com.manangupta.jobforge;

import com.manangupta.jobforge.domain.JobStatus;
import com.manangupta.jobforge.handler.JobContext;
import com.manangupta.jobforge.handler.JobHandler;
import com.manangupta.jobforge.handler.JobResult;
import com.manangupta.jobforge.repository.JobExecutionRepository;
import com.manangupta.jobforge.repository.JobRepository;
import com.manangupta.jobforge.service.JobClaimService;
import com.manangupta.jobforge.service.JobDispatcher;
import com.manangupta.jobforge.service.JobService;
import com.manangupta.jobforge.service.JobSpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Proves the exactly-one-winner claim semantics under contention and the no-duplicate guarantee. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:jobforge-concurrency;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Import(ConcurrencyIT.CountingHandlerConfig.class)
class ConcurrencyIT {

    @Autowired JobService service;
    @Autowired JobClaimService claims;
    @Autowired JobDispatcher dispatcher;
    @Autowired JobRepository jobs;
    @Autowired JobExecutionRepository executions;
    @Autowired CountingHandler counting;

    @TestConfiguration
    static class CountingHandlerConfig {
        @Bean
        CountingHandler countingHandler() {
            return new CountingHandler();
        }
    }

    /** Records how many times each job id was executed. */
    static class CountingHandler implements JobHandler {
        final ConcurrentHashMap<UUID, AtomicInteger> executions = new ConcurrentHashMap<>();

        @Override
        public String type() {
            return "count";
        }

        @Override
        public JobResult execute(JobContext ctx) {
            executions.computeIfAbsent(ctx.jobId(), k -> new AtomicInteger()).incrementAndGet();
            return JobResult.empty();
        }
    }

    @Test
    void concurrentClaimersNeverClaimTheSameJobTwice() throws Exception {
        dispatcher.setPaused(true); // keep the real dispatcher out of the race
        try {
            int jobCount = 400;
            List<UUID> ids = service.submitBulk(JobSpec.of("count", Map.of()), jobCount);

            int claimers = 12;
            ExecutorService pool = Executors.newFixedThreadPool(claimers);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<UUID>>> results = new ArrayList<>();
            for (int i = 0; i < claimers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    List<UUID> mine = new ArrayList<>();
                    List<UUID> got;
                    do {
                        got = claims.claimBatch(10);
                        mine.addAll(got);
                    } while (!got.isEmpty());
                    return mine;
                }));
            }
            start.countDown();

            List<UUID> all = new ArrayList<>();
            for (Future<List<UUID>> f : results) {
                all.addAll(f.get(30, TimeUnit.SECONDS));
            }
            pool.shutdownNow();

            Set<UUID> claimedOnce = new HashSet<>(all);
            assertThat(all).as("every claim must be unique").hasSize(claimedOnce.size());
            assertThat(claimedOnce).containsExactlyInAnyOrderElementsOf(ids);
            for (UUID id : ids) {
                assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
            }
        } finally {
            // Hand the artificially claimed jobs back to the cluster and let it finish them.
            dispatcher.setPaused(false);
        }
    }

    @Test
    void burstOfJobsIsExecutedExactlyOnceEach() {
        int jobCount = 500;
        List<UUID> ids = service.submitBulk(JobSpec.of("count", Map.of()), jobCount);

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(jobs.countByStatus(JobStatus.SUCCEEDED)).isGreaterThanOrEqualTo(jobCount));

        for (UUID id : ids) {
            assertThat(counting.executions.getOrDefault(id, new AtomicInteger()).get())
                    .as("job %s executions", id).isEqualTo(1);
            assertThat(executions.findByJobIdOrderByAttemptAsc(id)).hasSize(1);
        }
    }
}
