package com.xjjk.agent.chat.observation;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.concurrent.TimeUnit;

public final class ChatCallMetricsCollector {

    private final String requestId;
    private final String conversationId;
    private final String promptVersion;
    private final long startedAtNanos;

    private String responseModel;
    private Long inputTokens;
    private Long outputTokens;
    private Long totalTokens;
    private Long firstDeltaLatencyMs;

    public ChatCallMetricsCollector(
            String requestId,
            String conversationId,
            String promptVersion
    ) {
        this.requestId = requestId;
        this.conversationId = conversationId;
        this.promptVersion = promptVersion;
        this.startedAtNanos = System.nanoTime();
    }

    public void accept(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return;
        }

        var metadata = response.getMetadata();

        String model = metadata.getModel();
        if (model != null && !model.isBlank()) {
            responseModel = model;
        }

        var usage = metadata.getUsage();
        if (usage == null) {
            return;
        }

        // 当前适配百炼的 OpenAI 兼容响应。
        // 读取原始用量，避免框架把缺失字段转换成 0。
        if (usage.getNativeUsage() instanceof OpenAiApi.Usage nativeUsage) {
            if (nativeUsage.promptTokens() == null
                    && nativeUsage.completionTokens() == null
                    && nativeUsage.totalTokens() == null) {
                return;
            }

            // 保存最新用量快照，不累加每个片段的累计值。
            inputTokens = toLong(nativeUsage.promptTokens());
            outputTokens = toLong(nativeUsage.completionTokens());
            totalTokens = toLong(nativeUsage.totalTokens());
        }
    }

    public void markFirstDeltaSent() {
        if (firstDeltaLatencyMs == null) {
            firstDeltaLatencyMs = elapsedMillis();
        }
    }

    public ChatCallMetrics snapshot(
            String status,
            String finishReason
    ) {
        return new ChatCallMetrics(
                requestId,
                conversationId,
                promptVersion,
                responseModel,
                inputTokens,
                outputTokens,
                totalTokens,
                firstDeltaLatencyMs,
                elapsedMillis(),
                status,
                finishReason
        );
    }

    private long elapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedAtNanos
        );
    }

    private static Long toLong(Integer value) {
        return value == null ? null : value.longValue();
    }
}