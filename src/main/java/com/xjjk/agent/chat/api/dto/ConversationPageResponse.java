package com.xjjk.agent.chat.api.dto;

import java.util.List;

/** Stable keyset page of conversations owned by the authenticated user. */
public record ConversationPageResponse(
        List<ConversationListItemResponse> items,
        String nextCursor,
        boolean hasMore
) {
}
