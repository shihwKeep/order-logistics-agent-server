package com.xjjk.agent.chat.api.dto;

/**
 * 会话基础信息响应。
 *
 * 不直接返回数据库实体，避免暴露数据库主键、
 * 请求占用状态等内部字段。
 *
 * @param conversationId 对外会话 ID
 * @param title 会话标题
 */
public record ConversationResponse(
        String conversationId,
        String title
) {
}