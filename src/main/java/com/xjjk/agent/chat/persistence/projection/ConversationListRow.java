package com.xjjk.agent.chat.persistence.projection;

import java.time.LocalDateTime;

/** Internal database projection used by conversation keyset pagination. */
public record ConversationListRow(
        long id,
        String conversationId,
        String title,
        LocalDateTime updatedAt
) {
}
