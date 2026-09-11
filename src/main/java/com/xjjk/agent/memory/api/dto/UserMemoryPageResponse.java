package com.xjjk.agent.memory.api.dto;

import java.util.List;

public record UserMemoryPageResponse(
        List<UserMemoryResponse> items,
        String nextCursor,
        boolean hasMore
) {
}
