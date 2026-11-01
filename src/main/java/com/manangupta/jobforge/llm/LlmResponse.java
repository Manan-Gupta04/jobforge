package com.manangupta.jobforge.llm;

public record LlmResponse(String text, String model, String stopReason, long inputTokens, long outputTokens) {
}
