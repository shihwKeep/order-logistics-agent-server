package com.xjjk.agent.chat.domain.summary;

import org.springframework.util.StringUtils;

/**
 * 摘要中的会话事实。
 *
 * @param content 经过脱敏和长度校验的事实正文
 * @param sourceType 事实来源消息角色
 * @param sourceSequence 来源消息序号
 */
public record ChatSummaryFact(
        String content,
        ChatSummarySourceType sourceType,
        long sourceSequence
) {

    public ChatSummaryFact {
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("摘要事实正文不能为空");
        }
        if (sourceType == null) {
            throw new IllegalArgumentException("摘要事实来源不能为空");
        }
        if (sourceSequence < 1) {
            throw new IllegalArgumentException("摘要事实来源序号必须大于零");
        }
    }

    /** 仅输出来源元数据，禁止事实正文进入日志。 */
    @Override
    public String toString() {
        return "ChatSummaryFact[sourceType=" + sourceType
                + ", sourceSequence=" + sourceSequence
                + ", content=<redacted>]";
    }
}
