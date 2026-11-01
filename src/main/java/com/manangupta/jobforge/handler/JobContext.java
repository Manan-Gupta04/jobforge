package com.manangupta.jobforge.handler;

import java.util.Map;
import java.util.UUID;

/** Everything a handler can see about the job it is running. */
public interface JobContext {

    UUID jobId();

    /** 1-based attempt number; useful for handlers that need to know they are a retry. */
    int attempt();

    int maxAttempts();

    Map<String, Object> payload();

    /** Convenience accessor with a default for missing or null keys. */
    @SuppressWarnings("unchecked")
    default <T> T get(String key, T defaultValue) {
        Object v = payload().get(key);
        return v == null ? defaultValue : (T) v;
    }

    default long getLong(String key, long defaultValue) {
        Object v = payload().get(key);
        return v instanceof Number n ? n.longValue() : defaultValue;
    }

    default int getInt(String key, int defaultValue) {
        Object v = payload().get(key);
        return v instanceof Number n ? n.intValue() : defaultValue;
    }

    default String getString(String key, String defaultValue) {
        Object v = payload().get(key);
        return v == null ? defaultValue : v.toString();
    }

    /**
     * Long-running handlers may call this to extend the lease explicitly. JobForge also
     * heartbeats automatically in the background, so most handlers never need to.
     */
    void heartbeat();
}
