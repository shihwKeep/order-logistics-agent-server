package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.result.PendingMessageResult;
import com.xjjk.agent.chat.routing.BusinessQueryPlan;
import com.xjjk.agent.chat.stream.ChatSseSession;
import com.xjjk.agent.tool.ToolUiResult;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class FreshBusinessResultGateTest {

    private final FreshBusinessResultGate gate = new FreshBusinessResultGate();
    private final ChatSseSession session = mock(ChatSseSession.class);

    @Test
    void flushesMatchingResultsBeforeBufferedText() throws Exception {
        ChatTurnExecution execution = execution(
                BusinessQueryPlan.modelRequired(Set.of("after-sale-detail")));
        ToolUiResult ui = uiResult("after-sale-detail");
        execution.stageResult(pending(1, "after-sale-detail"), ui);
        execution.content.append("详情卡片已展示");

        gate.flush(execution, session);

        InOrder order = inOrder(session);
        order.verify(session).result(ui);
        order.verify(session).delta("详情卡片已展示");
        assertThat(execution.resultSnapshot()).hasSize(1);
        assertThat(execution.stagedResultSnapshot()).isEmpty();
    }

    @Test
    void replacesModelTextWhenNoFreshResultExists() throws Exception {
        ChatTurnExecution execution = execution(
                BusinessQueryPlan.modelRequired(Set.of("after-sale-detail")));
        execution.content.append("已查询，卡片已展示");

        gate.flush(execution, session);

        verify(session).delta(FreshBusinessResultGate.MISSING_RESULT_MESSAGE);
        verify(session, never()).result(any());
        assertThat(execution.content.toString())
                .isEqualTo(FreshBusinessResultGate.MISSING_RESULT_MESSAGE);
        assertThat(execution.resultSnapshot()).isEmpty();
    }

    @Test
    void rejectsTheWholeBatchWhenAnyResultKindIsUnexpected() throws Exception {
        ChatTurnExecution execution = execution(
                BusinessQueryPlan.modelRequired(Set.of("after-sale-detail")));
        execution.stageResult(pending(1, "product-list"), uiResult("product-list"));
        execution.content.append("已完成");

        gate.flush(execution, session);

        verify(session, never()).result(any());
        verify(session).delta(FreshBusinessResultGate.MISSING_RESULT_MESSAGE);
        assertThat(execution.resultSnapshot()).isEmpty();
        assertThat(execution.stagedResultSnapshot()).isEmpty();
    }

    private ChatTurnExecution execution(BusinessQueryPlan plan) {
        ChatTurnExecution execution = new ChatTurnExecution("fallback-request");
        execution.prepared(new ChatTurnContext(
                1L,
                10567L,
                "conversation-1",
                "request-1",
                "user-message-1",
                "assistant-message-1",
                "prompt-v1"));
        execution.queryPlan(plan);
        return execution;
    }

    private PendingMessageResult pending(int sequence, String kind) {
        OffsetDateTime queriedAt = OffsetDateTime.parse("2026-09-09T12:00:00+08:00");
        return new PendingMessageResult(
                sequence,
                "test_tool",
                kind,
                1,
                "{\"value\":\"safe\"}",
                16,
                queriedAt);
    }

    private ToolUiResult uiResult(String kind) {
        return new ToolUiResult(
                "test_tool",
                kind,
                1,
                OffsetDateTime.parse("2026-09-09T12:00:00+08:00"),
                Map.of("value", "safe"));
    }
}
