package com.xjjk.agent.chat.domain.memory;

import org.springframework.util.StringUtils;

/**
 * 经 MySQL 验证的稳定历史游标。
 *
 * @param tenantId 所属租户 ID
 * @param userId 所属用户 ID
 * @param conversationId 所属会话 ID
 * @param memoryVersion 稳定历史版本
 * @param memoryUntilSequence 稳定历史已覆盖的消息序号
 * @param beforeSequence 历史查询排他上界
 */
public record ChatHistoryCursor(
        long tenantId,
        long userId,
        String conversationId,
        long memoryVersion,
        long memoryUntilSequence,
        long beforeSequence
) {

    public ChatHistoryCursor {
        // 该对象会进入缓存 Key，因此在领域边界统一拒绝空身份、负版本和负消息边界。
        if (tenantId <= 0
                || userId <= 0
                || !StringUtils.hasText(conversationId)
                || memoryVersion < 0
                || memoryUntilSequence < 0) {
            throw new IllegalArgumentException("稳定历史游标不合法");
        }

        long expectedBeforeSequence = nextSequence(memoryUntilSequence);

        // 历史查询采用排他上界：稳定历史截至 N 时，查询边界必须严格等于 N + 1。
        if (beforeSequence != expectedBeforeSequence) {
            throw new IllegalArgumentException("稳定历史游标边界不连续");
        }
    }

    private static long nextSequence(long sequence) {
        try {
            return Math.addExact(sequence, 1L);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("稳定历史消息边界溢出", exception);
        }
    }
}
