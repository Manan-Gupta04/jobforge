package com.manangupta.jobforge.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;

import java.util.Locale;

/**
 * Calls the Claude Messages API through the official Anthropic Java SDK.
 *
 * <p>The SDK already retries 429/5xx twice with backoff; anything that still fails is thrown and
 * becomes a JobForge retry with exponential backoff, which is the behaviour LLM pipelines need
 * under rate limits.
 */
public class AnthropicLlmClient implements LlmClient {

    private final AnthropicClient client;

    public AnthropicLlmClient(AnthropicClient client) {
        this.client = client;
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(request.model())
                .maxTokens(request.maxTokens())
                .addUserMessage(request.prompt());
        if (request.system() != null && !request.system().isBlank()) {
            params.system(request.system());
        }
        if (request.effort() != null && !request.effort().isBlank()) {
            params.outputConfig(OutputConfig.builder().effort(effort(request.effort())).build());
        }

        Message message = client.messages().create(params.build());

        String stopReason = message.stopReason().map(Object::toString).orElse("unknown");
        if ("refusal".equalsIgnoreCase(stopReason)) {
            // Safety classifiers declined; not transient, so fail loudly instead of retrying.
            throw new LlmRefusalException("Model declined the request (stop_reason=refusal)");
        }
        StringBuilder text = new StringBuilder();
        message.content().forEach(block -> block.text().ifPresent(t -> text.append(t.text())));
        return new LlmResponse(text.toString(), message.model().toString(), stopReason,
                message.usage().inputTokens(), message.usage().outputTokens());
    }

    private static OutputConfig.Effort effort(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "low" -> OutputConfig.Effort.LOW;
            case "medium" -> OutputConfig.Effort.MEDIUM;
            case "high" -> OutputConfig.Effort.HIGH;
            case "xhigh" -> OutputConfig.Effort.XHIGH;
            case "max" -> OutputConfig.Effort.MAX;
            default -> throw new IllegalArgumentException("Unknown effort '" + value
                    + "' (expected low, medium, high, xhigh or max)");
        };
    }

    /** A refusal is permanent for the given prompt, so retrying would only burn attempts. */
    public static class LlmRefusalException extends RuntimeException {
        public LlmRefusalException(String message) {
            super(message);
        }
    }
}
