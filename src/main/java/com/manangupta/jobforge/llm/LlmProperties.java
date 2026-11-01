package com.manangupta.jobforge.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** LLM settings. The provider is chosen automatically: Anthropic when a key is present, otherwise mock. */
@ConfigurationProperties(prefix = "jobforge.llm")
public class LlmProperties {

    /** {@code auto} (default), {@code anthropic} or {@code mock}. */
    private String provider = "auto";

    /** Default model when a job does not specify one. */
    private String model = "claude-opus-5-5";

    /** Default max output tokens when a job does not specify one. */
    private long maxTokens = 1024;

    /** Hard cap on max output tokens any job may request; protects a public demo from abuse. */
    private long maxTokensCap = 4096;

    /** Hard cap on prompt length in characters. */
    private int maxPromptChars = 100_000;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public long getMaxTokens() { return maxTokens; }
    public void setMaxTokens(long maxTokens) { this.maxTokens = maxTokens; }
    public long getMaxTokensCap() { return maxTokensCap; }
    public void setMaxTokensCap(long maxTokensCap) { this.maxTokensCap = maxTokensCap; }
    public int getMaxPromptChars() { return maxPromptChars; }
    public void setMaxPromptChars(int maxPromptChars) { this.maxPromptChars = maxPromptChars; }
}
