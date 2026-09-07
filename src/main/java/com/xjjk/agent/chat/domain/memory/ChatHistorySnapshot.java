package com.xjjk.agent.chat.domain.memory;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 在同一个数据库一致性视图中读取的历史快照。
 *
 * 仅用于内部上下文构建，不直接作为前端响应。
 * 当前承载普通文本历史，尚未包含摘要。
 *
 * @param tenantId 所属租户 ID，来自后端认证身份
 * @param userId 所属用户 ID，来自后端认证身份
 * @param conversationId 所属会话 ID
 * @param memoryVersion 已结束历史窗口版本
 * @param memoryUntilSequence 已结束历史的消息序号边界
 * @param beforeSequence 历史消息的排他上界，即本轮用户消息序号
 * @param turns 本次读取范围内的完整成功轮次，按消息序号升序排列
 * @param hasEarlierMessages 本次读取范围之前是否还有消息；
 *                           不代表那些消息一定能组成有效轮次
 * @param readBudgetTruncated 是否因正文读取预算不足而未加载部分有效历史
 */
public record ChatHistorySnapshot(
        long tenantId,
        long userId,
        String conversationId,
        long memoryVersion,
        long memoryUntilSequence,
        long beforeSequence,
        List<ChatHistoryTurn> turns,
        boolean hasEarlierMessages,
        boolean readBudgetTruncated
) {

    public ChatHistorySnapshot {
        if (!StringUtils.hasText(conversationId)) {
            throw new IllegalArgumentException("历史快照会话 ID 不能为空");
        }

        if (memoryVersion < 0
                || memoryUntilSequence < 0
                || beforeSequence < 1) {
            throw new IllegalArgumentException("历史快照版本或消息边界不合法");
        }

        long expectedBeforeSequence =
                Math.addExact(memoryUntilSequence, 1L);

        if (beforeSequence != expectedBeforeSequence) {
            throw new IllegalArgumentException(
                    "当前用户消息序号与稳定历史边界不连续"
            );
        }

        Objects.requireNonNull(turns, "历史轮次集合不能为空");

        // 防御性复制，避免调用方修改原集合后改变快照内容。
        // 同时拒绝集合中出现 null 元素。
        turns = List.copyOf(turns);

        long previousAssistantSequence = 0L;

        for (ChatHistoryTurn turn : turns) {
            if (turn.userSequence() <= previousAssistantSequence) {
                throw new IllegalArgumentException("历史轮次必须升序且不能重叠");
            }

            if (turn.assistantSequence() >= beforeSequence) {
                throw new IllegalArgumentException("历史轮次超出本次读取边界");
            }

            previousAssistantSequence = turn.assistantSequence();
        }
    }

    /**
     * 只输出快照元信息，不输出历史正文。
     */
    @Override
    public String toString() {
        return "ChatHistorySnapshot[tenantId=" + tenantId
                + ", userId=" + userId
                + ", conversationId=" + conversationId
                + ", memoryVersion=" + memoryVersion
                + ", memoryUntilSequence=" + memoryUntilSequence
                + ", beforeSequence=" + beforeSequence
                + ", turnCount=" + turns.size()
                + ", hasEarlierMessages=" + hasEarlierMessages
                + ", readBudgetTruncated=" + readBudgetTruncated
                + "]";
    }
}
