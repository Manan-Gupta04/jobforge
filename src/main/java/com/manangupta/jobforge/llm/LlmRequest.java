package com.manangupta.jobforge.llm;

public record LlmRequest(String model, String system, String prompt, long maxTokens, String effort) {
}
