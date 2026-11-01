package com.manangupta.jobforge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class ExecutorConfig {

    /** Bounded pool that runs job handlers; one slot per configured worker. */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService workerPool(SchedulerProperties props) {
        return Executors.newFixedThreadPool(props.getWorkers(), named("jobforge-worker-"));
    }

    /** Pool that runs the handler bodies so a worker can enforce a timeout and interrupt. */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService handlerPool() {
        return Executors.newCachedThreadPool(named("jobforge-handler-"));
    }

    /** Scheduler for lease heartbeats while a job is running. */
    @Bean
    public ThreadPoolTaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(4);
        s.setThreadNamePrefix("jobforge-heartbeat-");
        s.setRemoveOnCancelPolicy(true);
        s.initialize();
        return s;
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
