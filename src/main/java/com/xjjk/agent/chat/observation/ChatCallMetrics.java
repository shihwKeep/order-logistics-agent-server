package com.xjjk.agent.chat.observation;

public record ChatCallMetrics(
        String requestId,
        String conversationId,
        String promptVersion,
        String responseModel,
        Long inputTokens,
        Long outputTokens,
        Long totalTokens,
        Long firstDeltaLatencyMs,
        long durationMs,
        String status,
        String finishReason
) {
}
