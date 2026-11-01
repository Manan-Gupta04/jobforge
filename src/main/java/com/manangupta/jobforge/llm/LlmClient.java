package com.manangupta.jobforge.llm;

/** Minimal abstraction over an LLM provider so the engine can run with or without a real API key. */
public interface LlmClient {

    /** Provider name reported in job results and the dashboard, e.g. {@code anthropic} or {@code mock}. */
    String provider();

    /**
     * Sends one prompt. Transient provider failures (rate limits, 5xx, network) must surface as
     * exceptions so the scheduler can retry them with backoff.
     */
    LlmResponse complete(LlmRequest request);
}
