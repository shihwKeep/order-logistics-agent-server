package com.xjjk.agent.chat.domain.summary;

import org.springframework.util.StringUtils;

/**
 * 摘要中的决定或待解决问题。
 *
 * @param content 条目正文
 * @param sourceSequence 能够追溯到的原始消息序号
 */
public record ChatSummaryItem(
        String content,
        long sourceSequence
) {

    public ChatSummaryItem {
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("摘要条目正文不能为空");
        }
        if (sourceSequence < 1) {
            throw new IllegalArgumentException("摘要条目来源序号必须大于零");
        }
    }

    /** 仅输出来源序号，禁止条目正文进入日志。 */
    @Override
    public String toString() {
        return "ChatSummaryItem[sourceSequence=" + sourceSequence
                + ", content=<redacted>]";
    }
}
