package com.xjjk.agent.chat.service.memory;

import com.xjjk.agent.chat.domain.memory.ChatHistoryCursor;

/**
 * 数据库稳定历史推进事件。
 *
 * 事件只携带预热所需的身份和游标元信息，不包含问答正文。
 *
 * @param tenantId 所属租户 ID
 * @param userId 所属用户 ID
 * @param conversationId 所属会话 ID
 * @param memoryVersion 新的稳定历史版本
 * @param memoryUntilSequence 新的稳定消息边界
 */
public record ChatHistoryChangedEvent(
        long tenantId,
        long userId,
        String conversationId,
        long memoryVersion,
        long memoryUntilSequence
) {

    public ChatHistoryChangedEvent {
        new ChatHistoryCursor(
                tenantId,
                userId,
                conversationId,
                memoryVersion,
                memoryUntilSequence,
                Math.addExact(memoryUntilSequence, 1L)
        );
    }
}
