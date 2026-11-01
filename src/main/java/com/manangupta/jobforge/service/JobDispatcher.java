package com.manangupta.jobforge.service;

import com.manangupta.jobforge.config.SchedulerProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The dispatch loop. A single thread repeatedly claims as many due jobs as there are free worker
 * slots and hands them to the pool. It is event-driven rather than purely polling: whenever a
 * worker finishes, the loop wakes immediately and claims again, so throughput is bounded by the
 * database rather than by the poll interval. When nothing is due it sleeps for
 * {@code pollIntervalMs} (or until woken).
 *
 * <p>Back-pressure is implicit: a saturated node claims nothing, leaving jobs for other nodes.
 */
@Service
public class JobDispatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobDispatcher.class);

    private final JobClaimService claims;
    private final JobExecutor executor;
    private final ExecutorService workerPool;
    private final SchedulerProperties props;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Semaphore wake = new Semaphore(0);
    private volatile boolean paused = false;
    private volatile boolean running = false;
    private Thread loop;

    public JobDispatcher(JobClaimService claims, JobExecutor executor,
                         @Qualifier("workerPool") ExecutorService workerPool,
                         SchedulerProperties props, MeterRegistry meters) {
        this.claims = claims;
        this.executor = executor;
        this.workerPool = workerPool;
        this.props = props;
        Gauge.builder("jobforge.workers.in_flight", inFlight, AtomicInteger::get)
                .description("Jobs currently executing on this node").register(meters);
        Gauge.builder("jobforge.workers.total", props, SchedulerProperties::getWorkers)
                .description("Worker slots on this node").register(meters);
    }

    // --- lifecycle -----------------------------------------------------------------------

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        loop = new Thread(this::runLoop, "jobforge-dispatcher");
        loop.setDaemon(true);
        loop.start();
        log.info("Dispatcher started with {} worker(s), poll interval {} ms", props.getWorkers(), props.getPollIntervalMs());
    }

    @Override
    public void stop() {
        running = false;
        if (loop != null) {
            loop.interrupt();
        }
        workerPool.shutdown();
        try {
            if (!workerPool.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Worker pool did not drain in 10 s; interrupting {} job(s)", inFlight.get());
                workerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Dispatcher stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100; // start late (after the DB is up), stop early (before the DB goes away)
    }

    // --- the loop ------------------------------------------------------------------------

    private void runLoop() {
        while (running) {
            try {
                int dispatched = paused ? 0 : dispatchOnce();
                if (dispatched == 0) {
                    // Nothing due or no free slot: wait for a worker to finish or the poll interval.
                    wake.tryAcquire(props.getPollIntervalMs(), TimeUnit.MILLISECONDS);
                    wake.drainPermits();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("Dispatch cycle failed: {}", e.toString());
                sleepQuietly(props.getPollIntervalMs());
            }
        }
    }

    /** One claim-and-dispatch cycle. Returns how many jobs were handed to workers. */
    int dispatchOnce() {
        int free = props.getWorkers() - inFlight.get();
        if (free <= 0) {
            return 0;
        }
        List<UUID> claimed = claims.claimBatch(free);
        for (UUID id : claimed) {
            inFlight.incrementAndGet();
            try {
                workerPool.execute(() -> run(id));
            } catch (RuntimeException rejected) {
                inFlight.decrementAndGet();
                throw rejected;
            }
        }
        if (!claimed.isEmpty()) {
            log.debug("Claimed {} job(s); {} in flight", claimed.size(), inFlight.get());
        }
        return claimed.size();
    }

    private void run(UUID id) {
        try {
            executor.execute(id);
        } catch (Throwable t) {
            log.error("Worker crashed on job {}", id, t);
        } finally {
            inFlight.decrementAndGet();
            wake.release(); // a slot is free: claim again right away
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- observability / control ---------------------------------------------------------

    public int inFlight() {
        return inFlight.get();
    }

    public int workers() {
        return props.getWorkers();
    }

    public boolean isPaused() {
        return paused;
    }

    /** Stops claiming new jobs (running ones finish); useful for draining a node before shutdown. */
    public void setPaused(boolean paused) {
        this.paused = paused;
        wake.release();
        log.info("Dispatcher {}", paused ? "paused" : "resumed");
    }
}
