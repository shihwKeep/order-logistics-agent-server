package com.xjjk.agent.chat.domain;

/**
 * 一轮问答开始后生成的内部上下文。
 *
 * 用于关联流式事件、消息落库和请求收尾。
 * 不包含 Token，也不直接作为接口响应。
 *
 * @param tenantId 所属租户 ID
 * @param userId 所属用户 ID
 * @param conversationId 会话 ID
 * @param requestId 本轮请求 ID
 * @param userMessageId 用户消息 ID
 * @param assistantMessageId 助手消息 ID
 * @param promptVersion 本轮使用的提示词版本
 */
public record ChatTurnContext(
        long tenantId,
        long userId,
        String conversationId,
        String requestId,
        String userMessageId,
        String assistantMessageId,
        String promptVersion
) {
}