package com.xjjk.agent.chat.api.dto;

import java.time.OffsetDateTime;

/** A single conversation entry visible to the authenticated owner. */
public record ConversationListItemResponse(
        String conversationId,
        String title,
        OffsetDateTime updatedAt
) {
}
