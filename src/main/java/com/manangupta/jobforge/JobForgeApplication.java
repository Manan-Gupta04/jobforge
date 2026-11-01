package com.manangupta.jobforge;

import com.manangupta.jobforge.config.SchedulerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * JobForge: a distributed, fault-tolerant job scheduler and workflow engine.
 *
 * <p>Any number of JobForge nodes can share one database. Each node runs a worker pool that
 * atomically claims due jobs with a compare-and-swap update, executes them under a renewable
 * lease, retries failures with exponential backoff, dead-letters jobs that exhaust their
 * attempts, and releases downstream jobs in a dependency graph as upstream jobs succeed.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(SchedulerProperties.class)
public class JobForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobForgeApplication.class, args);
    }
}
