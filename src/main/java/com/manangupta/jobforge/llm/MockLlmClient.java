package com.manangupta.jobforge.llm;

import java.util.Locale;

/**
 * Deterministic stand-in used when no API key is configured (tests, CI, public demos). It mimics
 * latency and produces a plausible-looking answer derived from the prompt so pipelines can be
 * exercised end to end at zero cost.
 */
public class MockLlmClient implements LlmClient {

    private final long simulatedLatencyMs;

    public MockLlmClient(long simulatedLatencyMs) {
        this.simulatedLatencyMs = simulatedLatencyMs;
    }

    @Override
    public String provider() {
        return "mock";
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (simulatedLatencyMs > 0) {
            try {
                Thread.sleep(simulatedLatencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
        }
        String prompt = request.prompt() == null ? "" : request.prompt().trim();
        String lower = prompt.toLowerCase(Locale.ROOT);
        String text;
        if (lower.contains("keyword")) {
            text = "scheduling, reliability, retries, leases, workflows";
        } else if (lower.startsWith("classify") || lower.contains("exactly one category")) {
            text = pick(prompt, "technology", "business", "science", "general");
        } else if (lower.startsWith("summar") || lower.contains("summarize")) {
            text = "[mock summary] " + excerpt(body(prompt), 160);
        } else {
            text = "[mock response] " + excerpt(prompt, 120);
        }
        long in = Math.max(1, prompt.length() / 4);
        long out = Math.max(1, text.length() / 4);
        return new LlmResponse(text, "mock-model", "end_turn", in, out);
    }

    /** The part of the prompt after the instruction line, i.e. the content being worked on. */
    private static String body(String prompt) {
        int nl = prompt.indexOf('\n');
        return nl > 0 && nl < prompt.length() - 1 ? prompt.substring(nl + 1).trim() : prompt;
    }

    private static String pick(String seed, String... options) {
        return options[Math.floorMod(seed.hashCode(), options.length)];
    }

    private static String excerpt(String s, int max) {
        String oneLine = s.replaceAll("\\s+", " ");
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}
