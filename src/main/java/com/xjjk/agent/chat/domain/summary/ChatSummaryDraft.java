package com.xjjk.agent.chat.domain.summary;

import org.springframework.util.StringUtils;

/**
 * 摘要模型生成并经应用校验后的候选结果。
 *
 * 版本号和覆盖游标不允许由模型决定，统一由提交事务根据任务快照写入。
 */
public record ChatSummaryDraft(
        ChatSummaryContent content,
        String modelName,
        String promptVersion,
        Long inputTokens,
        Long outputTokens,
        long generationDurationMs,
        int attemptCount
) {

    public ChatSummaryDraft {
        if (content == null
                || !StringUtils.hasText(modelName)
                || !StringUtils.hasText(promptVersion)
                || inputTokens != null && inputTokens < 0
                || outputTokens != null && outputTokens < 0
                || generationDurationMs < 0
                || attemptCount < 1 || attemptCount > 2) {
            throw new IllegalArgumentException("摘要草稿元数据不合法");
        }
    }

    /** 日志只输出模型调用元数据，不输出摘要正文。 */
    @Override
    public String toString() {
        return "ChatSummaryDraft[modelName=" + modelName
                + ", promptVersion=" + promptVersion
                + ", inputTokens=" + inputTokens
                + ", outputTokens=" + outputTokens
                + ", generationDurationMs=" + generationDurationMs
                + ", attemptCount=" + attemptCount
                + ", content=<redacted>]";
    }
}
