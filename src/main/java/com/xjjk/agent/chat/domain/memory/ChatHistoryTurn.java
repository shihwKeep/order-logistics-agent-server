package com.xjjk.agent.chat.domain.memory;

import org.springframework.util.StringUtils;

/**
 * 可用于模型上下文的一轮完整历史问答。
 *
 * 由历史加载服务确认用户、租户、会话归属，
 * 并验证同一请求下的用户消息与助手消息均为 SUCCESS 后构造。
 *
 * 当前仅表示普通文本问答，不表示包含工具调用的完整执行轮次。
 *
 * @param requestId 该轮问答的请求 ID
 * @param userSequence 用户消息在会话内的序号
 * @param assistantSequence 助手消息在会话内的序号
 * @param userContent 用户问题原文
 * @param assistantContent 助手回答原文
 */
public record ChatHistoryTurn(
        String requestId,
        long userSequence,
        long assistantSequence,
        String userContent,
        String assistantContent
) {

    /**
     * 校验本对象能够独立判断的结构约束。
     * 归属与数据库消息状态由加载服务校验。
     */
    public ChatHistoryTurn {
        if (!StringUtils.hasText(requestId)) {
            throw new IllegalArgumentException("历史轮次请求 ID 不能为空");
        }

        if (userSequence < 1 || assistantSequence <= userSequence) {
            throw new IllegalArgumentException("历史轮次消息序号不合法");
        }

        if (!StringUtils.hasText(userContent)
                || !StringUtils.hasText(assistantContent)) {
            throw new IllegalArgumentException("完整历史轮次的问答正文不能为空");
        }
    }

    /**
     * 避免对象被日志输出时泄露问答正文。
     */
    @Override
    public String toString() {
        return "ChatHistoryTurn[requestId=" + requestId
                + ", userSequence=" + userSequence
                + ", assistantSequence=" + assistantSequence
                + ", content=<redacted>]";
    }
}
