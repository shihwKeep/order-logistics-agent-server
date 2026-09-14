package com.xjjk.agent.chat.replay;

import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Objects;

/** 创建可恢复聊天任务所需的可信元数据。 */
public record ChatReplayMetadata(
        long tenantId,
        long userId,
        long orgId,
        String conversationId,
        String requestId,
        Instant createdAt,
        Instant expiresAt
) {

    public ChatReplayMetadata {
        if (tenantId <= 0 || userId <= 0 || orgId <= 0) {
            throw new IllegalArgumentException("聊天任务身份 ID 必须大于零");
        }
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(requestId)) {
            throw new IllegalArgumentException("会话和请求 ID 不能为空");
        }
        Objects.requireNonNull(createdAt, "创建时间不能为空");
        Objects.requireNonNull(expiresAt, "截止时间不能为空");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("聊天任务截止时间必须晚于创建时间");
        }
    }
}
