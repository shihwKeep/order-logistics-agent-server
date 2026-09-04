package com.xjjk.agent.chat.api.dto;

import java.time.Instant;

public record ChatStreamEvent<T>(
        String type,
        long sequence,
        Instant timestamp,
        T payload
) {

    public static <T> ChatStreamEvent<T> of(
            String type,
            long sequence,
            T payload
    ) {
        return new ChatStreamEvent<>(
                type,
                sequence,
                Instant.now(),
                payload
        );
    }
}
