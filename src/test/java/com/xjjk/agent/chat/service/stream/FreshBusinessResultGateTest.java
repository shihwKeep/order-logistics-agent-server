package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.domain.ChatTurnContext;
import com.xjjk.agent.chat.result.PendingMessageResult;
import com.xjjk.agent.chat.routing.BusinessQueryPlan;
import com.xjjk.agent.chat.orchestration.CompositeQueryIntent;
import com.xjjk.agent.chat.orchestration.CompositeQueryPlan;
import com.xjjk.agent.chat.stream.ChatSseSession;
import com.xjjk.agent.knowledge.domain.KnowledgeRetrievalResult;
import com.xjjk.agent.tool.ToolUiResult;
import com.xjjk.agent.tool.observation.ToolCallMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.List;

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

    @Test
    void replacesModelConclusionWhenKnowledgeServiceCannotAnswer() throws Exception {
        ChatTurnExecution execution = execution(
                BusinessQueryPlan.modelRequired(Set.of("knowledge-citations")));
        KnowledgeRetrievalResult noEvidence = new KnowledgeRetrievalResult(
                false, List.of(), "hybrid-v1", "NONE", "LOW_RELEVANCE",
                OffsetDateTime.parse("2026-09-09T12:00:00+08:00"));
        ToolUiResult ui = new ToolUiResult(
                "search_knowledge", "knowledge-citations", 1,
                noEvidence.queriedAt(), noEvidence);
        execution.stageResult(pending(1, "knowledge-citations"), ui);
        execution.content.append("根据经验可以退款");

        gate.flush(execution, session);

        verify(session, never()).result(any());
        verify(session).delta(FreshBusinessResultGate.NO_KNOWLEDGE_MESSAGE);
        assertThat(execution.content.toString())
                .isEqualTo(FreshBusinessResultGate.NO_KNOWLEDGE_MESSAGE);
        assertThat(execution.resultSnapshot()).isEmpty();
    }

    @Test
    void rejectsKnowledgeCitationsWhenModelExplicitlyDeclinesToAnswer() throws Exception {
        ChatTurnExecution execution = execution(
                BusinessQueryPlan.modelRequired(Set.of("knowledge-citations")));
        KnowledgeRetrievalResult answerable = new KnowledgeRetrievalResult(
                true, List.of(new KnowledgeRetrievalResult.Evidence(
                        2L, 3L, 4L, "chunk-1", "物流制度", "第七章",
                        "本文仅为测试数据，不包含具体赔偿金额。", "{}", 0.91D,
                        Set.of("KEYWORD"))),
                "hybrid-v1", "NONE", "OK",
                OffsetDateTime.parse("2026-09-09T12:00:00+08:00"));
        ToolUiResult ui = new ToolUiResult(
                "search_knowledge", "knowledge-citations", 1,
                answerable.queriedAt(), answerable);
        execution.stageResult(pending(1, "knowledge-citations"), ui);
        execution.content.append("当前无法可靠依据确认具体数值，无法提供有效答复。");

        gate.flush(execution, session);

        verify(session, never()).result(any());
        verify(session).delta(FreshBusinessResultGate.NO_KNOWLEDGE_MESSAGE);
        assertThat(execution.content.toString())
                .isEqualTo(FreshBusinessResultGate.NO_KNOWLEDGE_MESSAGE);
        assertThat(execution.resultSnapshot()).isEmpty();
    }

    @Test
    void rejectsKnowledgeCitationsWhenModelSaysAmountHasNoReliableBasis() throws Exception {
        ChatTurnExecution execution = execution(
                BusinessQueryPlan.modelRequired(Set.of("knowledge-citations")));
        KnowledgeRetrievalResult answerable = new KnowledgeRetrievalResult(
                true, List.of(new KnowledgeRetrievalResult.Evidence(
                        2L, 3L, 4L, "chunk-1", "物流制度", "第七章",
                        "本文仅为测试数据，不包含具体赔偿金额。", "{}", 0.91D,
                        Set.of("KEYWORD"))),
                "hybrid-v1", "NONE", "OK",
                OffsetDateTime.parse("2026-09-09T12:00:00+08:00"));
        ToolUiResult ui = new ToolUiResult(
                "search_knowledge", "knowledge-citations", 1,
                answerable.queriedAt(), answerable);
        execution.stageResult(pending(1, "knowledge-citations"), ui);
        execution.content.append("知识库中**无真实有效的金额依据**，当前无法确认具体数值。");

        gate.flush(execution, session);

        verify(session, never()).result(any());
        verify(session).delta(FreshBusinessResultGate.NO_KNOWLEDGE_MESSAGE);
        assertThat(execution.resultSnapshot()).isEmpty();
    }

    @Test
    void sanitizesUnverifiedBusinessOutputBeforePersistence() {
        ChatTurnExecution execution = execution(
                BusinessQueryPlan.modelRequired(Set.of("order-list")));
        execution.content.append("订单已经发货");
        execution.stageResult(pending(1, "order-list"), uiResult("order-list"));

        gate.sanitizeForPersistence(execution);

        assertThat(execution.content.toString())
                .isEqualTo(FreshBusinessResultGate.MISSING_RESULT_MESSAGE);
        assertThat(execution.resultSnapshot()).isEmpty();
        assertThat(execution.stagedResultSnapshot()).isEmpty();
    }

    @Test
    void sanitizesUnverifiedKnowledgeOutputWithKnowledgeSafeMessage() {
        ChatTurnExecution execution = execution(
                BusinessQueryPlan.modelRequired(Set.of("knowledge-citations")));
        execution.content.append("凭经验可以退货");

        gate.sanitizeForPersistence(execution);

        assertThat(execution.content.toString())
                .isEqualTo(FreshBusinessResultGate.NO_KNOWLEDGE_MESSAGE);
    }

    @Test
    void doesNotOverwriteOutputThatAlreadyPassedTheGate() throws Exception {
        ChatTurnExecution execution = execution(
                BusinessQueryPlan.modelRequired(Set.of("after-sale-detail")));
        ToolUiResult ui = uiResult("after-sale-detail");
        execution.stageResult(pending(1, "after-sale-detail"), ui);
        execution.content.append("详情卡片已展示");

        gate.flush(execution, session);
        gate.sanitizeForPersistence(execution);

        assertThat(execution.content.toString()).isEqualTo("详情卡片已展示");
        assertThat(execution.resultSnapshot()).hasSize(1);
    }

    @Test
    void recordsPublishedAndRejectedStructuredResults() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        FreshBusinessResultGate observedGate = new FreshBusinessResultGate();
        observedGate.setToolCallMetrics(new ToolCallMetrics(
                meters, ObservationRegistry.create()));

        ChatTurnExecution accepted = execution(
                BusinessQueryPlan.modelRequired(Set.of("after-sale-detail")));
        accepted.stageResult(
                pending(1, "after-sale-detail"), uiResult("after-sale-detail"));
        accepted.content.append("详情卡片已展示");
        observedGate.flush(accepted, mock(ChatSseSession.class));

        ChatTurnExecution rejected = execution(
                BusinessQueryPlan.modelRequired(Set.of("after-sale-detail")));
        rejected.stageResult(pending(1, "product-list"), uiResult("product-list"));
        rejected.content.append("不应该通过");
        observedGate.flush(rejected, mock(ChatSseSession.class));

        assertThat(meters.counter(
                "agent.tool.result",
                "kind", "after-sale-detail",
                "outcome", "PUBLISHED").count()).isEqualTo(1D);
        assertThat(meters.counter(
                "agent.tool.result",
                "kind", "product-list",
                "outcome", "GATE_REJECTED").count()).isEqualTo(1D);
    }

    @Test
    void acceptsCompositeBusinessAndKnowledgeResultsTogether() throws Exception {
        BusinessQueryPlan plan = BusinessQueryPlan.composite(CompositeQueryPlan.of(List.of(
                CompositeQueryIntent.logistics("XJ202609290001"),
                CompositeQueryIntent.knowledge("物流规则"))));
        ChatTurnExecution execution = execution(plan);
        execution.stageResult(pending(1, "logistics-timeline"), uiResult("logistics-timeline"));
        KnowledgeRetrievalResult evidence = new KnowledgeRetrievalResult(
                true, List.of(new KnowledgeRetrievalResult.Evidence(
                        2L, 3L, 4L, "chunk-1", "物流制度", "第七章",
                        "干线停滞超过阈值生成预警。", "{}", 0.91D,
                        Set.of("KEYWORD"))),
                "hybrid-v1", "NONE", "OK",
                OffsetDateTime.parse("2026-09-09T12:00:00+08:00"));
        execution.stageResult(
                pending(2, "knowledge-citations"),
                new ToolUiResult("search_knowledge", "knowledge-citations", 1,
                        evidence.queriedAt(), evidence));
        execution.content.append("已根据订单事实和企业规则完成分析");

        gate.flush(execution, session);

        verify(session, org.mockito.Mockito.times(2)).result(any(ToolUiResult.class));
        assertThat(execution.resultSnapshot()).hasSize(2);
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
