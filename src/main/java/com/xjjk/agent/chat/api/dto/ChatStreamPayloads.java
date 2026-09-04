package com.xjjk.agent.chat.api.dto;

public final class ChatStreamPayloads {

    private ChatStreamPayloads() {
    }

    public record Session(
            String conversationId,
            String requestId
    ) {
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
