package com.xjjk.agent.chat.service.summary;

import com.xjjk.agent.chat.config.ChatSummaryProperties;
import com.xjjk.agent.chat.domain.summary.ChatSummaryCandidateBatch;
import com.xjjk.agent.chat.domain.summary.ChatSummaryTrigger;
import com.xjjk.agent.chat.domain.summary.ChatSummaryTriggerReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 长期摘要触发策略。
 *
 * 该组件只根据已加载批次和持久化任务标记作决定，不访问数据库、不调用模型。
 */
@Component
@RequiredArgsConstructor
public class ChatSummaryTriggerPolicy {

    private final ChatSummaryProperties properties;

    /** 按固定优先级返回唯一触发原因。 */
    public ChatSummaryTrigger evaluate(
            ChatSummaryCandidateBatch batch,
            boolean forceGeneration,
            boolean backlogContinuation
    ) {
        Objects.requireNonNull(batch, "摘要候选批次不能为空");

        // 没有完整连续候选时，即使存在强制标记也不能调用模型生成空摘要。
        if (batch.turns().isEmpty()) {
            return ChatSummaryTrigger.none();
        }
        if (forceGeneration) {
            return ChatSummaryTrigger.generate(
                    ChatSummaryTriggerReason.RAW_CONTEXT_PRESSURE
            );
        }
        if (backlogContinuation) {
            return ChatSummaryTrigger.generate(
                    ChatSummaryTriggerReason.BACKLOG_CONTINUATION
            );
        }
        if (batch.limitReached()) {
            return ChatSummaryTrigger.generate(
                    ChatSummaryTriggerReason.SCAN_LIMIT
            );
        }
        if (batch.estimatedTokens() >= properties.triggerTokens()) {
            return ChatSummaryTrigger.generate(
                    ChatSummaryTriggerReason.TOKEN_THRESHOLD
            );
        }
        if (batch.turns().size() >= properties.triggerTurns()) {
            return ChatSummaryTrigger.generate(
                    ChatSummaryTriggerReason.TURN_THRESHOLD
            );
        }
        return ChatSummaryTrigger.none();
    }
}
