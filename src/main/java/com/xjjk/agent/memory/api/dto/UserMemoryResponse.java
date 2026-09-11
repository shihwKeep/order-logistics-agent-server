package com.xjjk.agent.memory.api.dto;

import java.time.OffsetDateTime;

public record UserMemoryResponse(
        String memoryId,
        String category,
        String content,
        String retentionType,
        long version,
        OffsetDateTime updatedAt
) {
}
