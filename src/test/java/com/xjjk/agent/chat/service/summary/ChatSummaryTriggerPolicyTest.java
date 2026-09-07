package com.xjjk.agent.chat.service.summary;

import com.xjjk.agent.chat.config.ChatSummaryProperties;
import com.xjjk.agent.chat.domain.MessageStatus;
import com.xjjk.agent.chat.domain.summary.ChatSummaryCandidateBatch;
import com.xjjk.agent.chat.domain.summary.ChatSummaryCandidateTurn;
import com.xjjk.agent.chat.domain.summary.ChatSummaryTriggerReason;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatSummaryTriggerPolicyTest {

    private final ChatSummaryTriggerPolicy policy =
            new ChatSummaryTriggerPolicy(properties());

    @Test
    void shouldApplyTriggerPrecedence() {
        assertDecision(batch(1, 20, false), true, true,
                true, ChatSummaryTriggerReason.RAW_CONTEXT_PRESSURE);
        assertDecision(batch(1, 20, false), false, true,
                true, ChatSummaryTriggerReason.BACKLOG_CONTINUATION);
        assertDecision(batch(1, 20, true), false, false,
                true, ChatSummaryTriggerReason.SCAN_LIMIT);
        assertDecision(batch(1, 100, false), false, false,
                true, ChatSummaryTriggerReason.TOKEN_THRESHOLD);
        assertDecision(batch(2, 40, false), false, false,
                true, ChatSummaryTriggerReason.TURN_THRESHOLD);
        assertDecision(batch(1, 20, false), false, false,
                false, ChatSummaryTriggerReason.NONE);
        assertDecision(batch(0, 0, false), true, true,
                false, ChatSummaryTriggerReason.NONE);
    }

    private void assertDecision(
            ChatSummaryCandidateBatch batch,
            boolean force,
            boolean backlog,
            boolean expectedGenerate,
            ChatSummaryTriggerReason expectedReason
    ) {
        var trigger = policy.evaluate(batch, force, backlog);
        assertThat(trigger.shouldGenerate()).isEqualTo(expectedGenerate);
        assertThat(trigger.reason()).isEqualTo(expectedReason);
    }

    private static ChatSummaryCandidateBatch batch(
            int turnCount,
            long tokens,
            boolean limitReached
    ) {
        List<ChatSummaryCandidateTurn> turns = turnCount == 0
                ? List.of()
                : java.util.stream.IntStream.range(0, turnCount)
                .mapToObj(index -> new ChatSummaryCandidateTurn(
                        "r" + index,
                        index * 2L + 1,
                        index * 2L + 2,
                        "问题",
                        "回答",
                        MessageStatus.SUCCESS,
                        12,
                        Math.max(1, tokens / turnCount)
                ))
                .toList();
        long until = turns.isEmpty()
                ? 0
                : turns.get(turns.size() - 1).assistantSequence();
        return new ChatSummaryCandidateBatch(
                1, 10567, "conversation-1",
                0, 10, 20,
                turns,
                turns.isEmpty() ? 0 : 1,
                until,
                turns.isEmpty() ? 0 : 12L * turnCount,
                turns.isEmpty() ? 0 : tokens,
                limitReached, false, false, limitReached
        );
    }

    private static ChatSummaryProperties properties() {
        return new ChatSummaryProperties(
                true, false, false, 1,
                100, 2, 1, 100,
                10, 1_000, 500,
                20, 30, 20,
                "summary-v1", "qwen-plus", 0.1,
                Duration.ofSeconds(10),
                new ChatSummaryProperties.Worker(
                        1, 1, 10, 2,
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(30), "agent-1"
                ),
                new ChatSummaryProperties.Retry(
                        3, Duration.ofSeconds(1),
                        Duration.ofSeconds(10), Duration.ZERO
                )
        );
    }
}
