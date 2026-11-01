package com.manangupta.jobforge.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/** Discovers every {@link JobHandler} bean and routes job types to them. */
@Component
public class JobHandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(JobHandlerRegistry.class);

    private final Map<String, JobHandler> handlers;

    public JobHandlerRegistry(List<JobHandler> discovered) {
        Map<String, JobHandler> map = new TreeMap<>();
        for (JobHandler h : discovered) {
            String type = h.type();
            if (type == null || type.isBlank()) {
                throw new IllegalStateException(h.getClass().getName() + " declares a blank job type");
            }
            JobHandler previous = map.put(type, h);
            if (previous != null) {
                throw new IllegalStateException("Duplicate job handler for type '" + type + "': "
                        + previous.getClass().getName() + " and " + h.getClass().getName());
            }
        }
        this.handlers = Collections.unmodifiableMap(map);
        log.info("Registered {} job handler(s): {}", handlers.size(), handlers.keySet());
    }

    public Optional<JobHandler> find(String type) {
        return Optional.ofNullable(handlers.get(type));
    }

    public boolean supports(String type) {
        return handlers.containsKey(type);
    }

    public Set<String> types() {
        return handlers.keySet();
    }

    public Collection<JobHandler> all() {
        return handlers.values();
    }
}
