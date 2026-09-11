package com.xjjk.agent.chat.service.conversation;

import java.time.LocalDateTime;

/** Exclusive lower boundary for descending conversation pagination. */
public record ConversationPageCursor(
        LocalDateTime updatedAt,
        long id
) {
}
