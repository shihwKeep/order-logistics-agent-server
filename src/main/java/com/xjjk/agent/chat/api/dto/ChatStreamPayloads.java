package com.xjjk.agent.chat.api.dto;

import java.time.Instant;
import java.time.OffsetDateTime;

public final class ChatStreamPayloads {

    private ChatStreamPayloads() {
    }

    public record Session(
            String conversationId,
            String requestId,
            Instant expiresAt,
            boolean resumable
    ) {

        /** 旧直连模式不承诺断点恢复，暂不暴露任务截止时间。 */
        public Session(String conversationId, String requestId) {
            this(conversationId, requestId, null, false);
        }
    }

    public record Heartbeat() {
    }

    public record Status(
            String code,
            String text
    ) {
    }

    public record Delta(
            String text
    ) {
    }

    /**
     * 工具产生的结构化展示结果；kind 用于前端选择具体卡片组件。
     */
    public record Result(
            String kind,
            int schemaVersion,
            OffsetDateTime queriedAt,
            Object data
    ) {
    }

    public record Done(
            String messageId
    ) {
    }

    public record Error(
            String code,
            String message,
            String requestId
    ) {
    }
}
