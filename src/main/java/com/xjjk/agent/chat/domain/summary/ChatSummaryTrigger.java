package com.xjjk.agent.chat.domain.summary;

import java.util.Objects;

/**
 * 摘要必要性判断结果。
 *
 * @param shouldGenerate 是否应调用摘要模型
 * @param reason 单一、低基数触发原因
 */
public record ChatSummaryTrigger(
        boolean shouldGenerate,
        ChatSummaryTriggerReason reason
) {

    public ChatSummaryTrigger {
        Objects.requireNonNull(reason, "摘要触发原因不能为空");
        if (shouldGenerate == (reason == ChatSummaryTriggerReason.NONE)) {
            throw new IllegalArgumentException("摘要触发结果与原因不一致");
        }
    }

    /** 无需生成摘要。 */
    public static ChatSummaryTrigger none() {
        return new ChatSummaryTrigger(
                false,
                ChatSummaryTriggerReason.NONE
        );
    }

    /** 根据明确原因生成摘要。 */
    public static ChatSummaryTrigger generate(
            ChatSummaryTriggerReason reason
    ) {
        return new ChatSummaryTrigger(true, reason);
    }
}
