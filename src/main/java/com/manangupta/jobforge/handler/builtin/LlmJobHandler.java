package com.manangupta.jobforge.handler.builtin;

import com.manangupta.jobforge.handler.JobContext;
import com.manangupta.jobforge.handler.JobHandler;
import com.manangupta.jobforge.handler.JobResult;
import com.manangupta.jobforge.llm.LlmClient;
import com.manangupta.jobforge.llm.LlmProperties;
import com.manangupta.jobforge.llm.LlmRequest;
import com.manangupta.jobforge.llm.LlmResponse;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs one LLM completion. Payload: {@code prompt} (required), {@code system}, {@code model},
 * {@code maxTokens}, {@code effort}. Prompts can reference upstream step outputs with
 * {@code {{steps.<id>.output.text}}} when the job is part of a workflow.
 */
@Component
public class LlmJobHandler implements JobHandler {

    private final LlmClient llm;
    private final LlmProperties props;

    public LlmJobHandler(LlmClient llm, LlmProperties props) {
        this.llm = llm;
        this.props = props;
    }

    @Override
    public String type() {
        return "llm";
    }

    @Override
    public String description() {
        return "Runs an LLM completion (provider: " + llm.provider() + "). payload.prompt required; "
                + "optional system, model, maxTokens, effort. Retries on rate limits with backoff.";
    }

    @Override
    public JobResult execute(JobContext ctx) {
        String prompt = ctx.getString("prompt", null);
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("payload.prompt is required");
        }
        if (prompt.length() > props.getMaxPromptChars()) {
            throw new IllegalArgumentException("payload.prompt exceeds " + props.getMaxPromptChars() + " characters");
        }
        long maxTokens = Math.min(props.getMaxTokensCap(), ctx.getLong("maxTokens", props.getMaxTokens()));
        LlmRequest request = new LlmRequest(
                ctx.getString("model", props.getModel()),
                ctx.getString("system", null),
                prompt,
                maxTokens,
                ctx.getString("effort", null));

        long t0 = System.nanoTime();
        LlmResponse response = llm.complete(request);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", response.text());
        out.put("model", response.model());
        out.put("provider", llm.provider());
        out.put("stopReason", response.stopReason());
        out.put("inputTokens", response.inputTokens());
        out.put("outputTokens", response.outputTokens());
        out.put("latencyMs", (System.nanoTime() - t0) / 1_000_000);
        return JobResult.of(out);
    }
}
