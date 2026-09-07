package com.xjjk.agent.chat.domain.summary;

import com.xjjk.agent.chat.domain.MessageStatus;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 可进入滚动摘要的一轮稳定问答。
 *
 * 非成功助手消息只保留终态，不携带可能残缺的助手正文。
 */
public record ChatSummaryCandidateTurn(
        String requestId,
        long userSequence,
        long assistantSequence,
        String userContent,
        String assistantContent,
        MessageStatus assistantStatus,
        long contentBytes,
        long estimatedTokens
) {

    public ChatSummaryCandidateTurn {
        if (!StringUtils.hasText(requestId)
                || userSequence < 1
                || assistantSequence != Math.addExact(userSequence, 1L)
                || !StringUtils.hasText(userContent)
                || contentBytes <= 0
                || estimatedTokens <= 0) {
            throw new IllegalArgumentException("摘要候选轮次不合法");
        }
        Objects.requireNonNull(assistantStatus, "助手终态不能为空");
        if (assistantStatus == MessageStatus.GENERATING) {
            throw new IllegalArgumentException("生成中的消息不能进入摘要候选");
        }
        if (assistantStatus == MessageStatus.SUCCESS
                && !StringUtils.hasText(assistantContent)) {
            throw new IllegalArgumentException("成功助手消息正文不能为空");
        }
        if (assistantStatus != MessageStatus.SUCCESS
                && assistantContent != null) {
            throw new IllegalArgumentException("失败助手消息不能携带残缺正文");
        }
    }

    /** 日志只输出序号、状态和预算，不输出问答正文。 */
    @Override
    public String toString() {
        return "ChatSummaryCandidateTurn[requestId=" + requestId
                + ", userSequence=" + userSequence
                + ", assistantSequence=" + assistantSequence
                + ", assistantStatus=" + assistantStatus
                + ", contentBytes=" + contentBytes
                + ", estimatedTokens=" + estimatedTokens
                + ", content=<redacted>]";
    }
}
