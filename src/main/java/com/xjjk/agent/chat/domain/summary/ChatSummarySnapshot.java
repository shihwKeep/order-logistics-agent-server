package com.xjjk.agent.chat.domain.summary;

import com.xjjk.agent.chat.domain.memory.ChatHistoryCursor;
import org.springframework.util.StringUtils;

/**
 * 当前请求可使用的已提交会话摘要快照。
 *
 * @param tenantId 所属租户
 * @param userId 所属坐席用户
 * @param conversationId 所属会话
 * @param summaryVersion 摘要版本；空快照为 0
 * @param coveredUntilSequence 摘要连续覆盖到的消息序号；空快照为 0
 * @param sourceMemoryVersion 生成摘要时捕获的稳定历史版本；空快照为 0
 * @param stableMemoryUntilSequence 当前请求已验证的稳定历史边界
 * @param content 已校验摘要正文；空快照为 null
 * @param promptVersion 摘要提示词版本；空快照为空
 * @param modelName 摘要模型名称；空快照为空
 */
public record ChatSummarySnapshot(
        long tenantId,
        long userId,
        String conversationId,
        long summaryVersion,
        long coveredUntilSequence,
        long sourceMemoryVersion,
        long stableMemoryUntilSequence,
        ChatSummaryContent content,
        String promptVersion,
        String modelName
) {

    public ChatSummarySnapshot {
        if (tenantId <= 0 || userId <= 0
                || !StringUtils.hasText(conversationId)) {
            throw new IllegalArgumentException("摘要归属信息不合法");
        }
        if (stableMemoryUntilSequence < 0
                || summaryVersion < 0
                || coveredUntilSequence < 0
                || sourceMemoryVersion < 0
                || coveredUntilSequence > stableMemoryUntilSequence) {
            throw new IllegalArgumentException("摘要版本或覆盖边界不合法");
        }

        boolean empty = content == null;
        if (empty && (summaryVersion != 0
                || coveredUntilSequence != 0
                || sourceMemoryVersion != 0
                || StringUtils.hasText(promptVersion)
                || StringUtils.hasText(modelName))) {
            throw new IllegalArgumentException("空摘要快照不能携带摘要元数据");
        }
        if (!empty && (summaryVersion < 1
                || sourceMemoryVersion < 1
                || !StringUtils.hasText(promptVersion)
                || !StringUtils.hasText(modelName))) {
            throw new IllegalArgumentException("有效摘要快照元数据不完整");
        }
    }

    /** 根据已校验的会话游标构造无摘要快照。 */
    public static ChatSummarySnapshot empty(ChatHistoryCursor cursor) {
        return new ChatSummarySnapshot(
                cursor.tenantId(),
                cursor.userId(),
                cursor.conversationId(),
                0,
                0,
                0,
                cursor.memoryUntilSequence(),
                null,
                null,
                null
        );
    }

    /** 是否包含可供模型使用的已提交摘要。 */
    public boolean present() {
        return content != null;
    }

    /** 日志只输出元数据，避免通过 record 默认实现泄露摘要正文。 */
    @Override
    public String toString() {
        return "ChatSummarySnapshot[tenantId=" + tenantId
                + ", userId=" + userId
                + ", conversationId=" + conversationId
                + ", summaryVersion=" + summaryVersion
                + ", coveredUntilSequence=" + coveredUntilSequence
                + ", sourceMemoryVersion=" + sourceMemoryVersion
                + ", stableMemoryUntilSequence=" + stableMemoryUntilSequence
                + ", content=<redacted>]";
    }
}
