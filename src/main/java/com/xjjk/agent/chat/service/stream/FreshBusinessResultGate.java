package com.xjjk.agent.chat.service.stream;

import com.xjjk.agent.chat.routing.BusinessQueryMode;
import com.xjjk.agent.chat.stream.ChatEventPublisher;
import com.xjjk.agent.knowledge.domain.KnowledgeRetrievalResult;
import com.xjjk.agent.tool.observation.ToolCallMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 校验模型实时查询是否产生了本轮匹配的结构化结果，并原子刷新输出。 */
@Slf4j
@Component
public class FreshBusinessResultGate {

    private ToolCallMetrics toolCallMetrics;

    @Autowired
    void setToolCallMetrics(ToolCallMetrics toolCallMetrics) {
        this.toolCallMetrics = toolCallMetrics;
    }

    static final String MISSING_RESULT_MESSAGE =
            "本轮未完成实时业务查询，请补充查询条件或稍后重试。";
    static final String NO_KNOWLEDGE_MESSAGE =
            "知识库中暂未找到相关规定，我不能在没有可靠依据的情况下给出业务结论。";

    /**
     * 在模型流正常结束后校验并一次性发布本轮暂存结果。
     *
     * <p>MODEL_REQUIRED 路径中，模型正文和卡片先缓存在请求对象中。只有至少产生
     * 一个结果，且所有实际结果类型都在计划白名单内，才允许发送给前端并进入收尾落库。</p>
     */
    void flush(ChatTurnExecution execution, ChatEventPublisher session) throws IOException {
        if (execution.queryPlan.mode() != BusinessQueryMode.MODEL_REQUIRED) {
            return;
        }
        List<StagedToolResult> staged = execution.stagedResultSnapshot();
        Set<String> actualKinds = staged.stream()
                .map(value -> value.uiResult().kind())
                .collect(Collectors.toUnmodifiableSet());
        boolean accepted = !staged.isEmpty()
                && execution.queryPlan.acceptedResultKinds().containsAll(actualKinds);
        boolean knowledgeRequired = execution.queryPlan.acceptedResultKinds()
                .contains("knowledge-citations");
        boolean grounded = !knowledgeRequired || (!staged.isEmpty()
                && staged.stream().allMatch(this::isAnswerableKnowledge));
        if (!accepted || !grounded) {
            // 丢弃模型可能已经生成的“查询成功”话术，替换为固定安全提示。
            recordGateRejected(execution, staged);
            execution.discardStagedResults();
            String safeMessage = knowledgeRequired
                    ? NO_KNOWLEDGE_MESSAGE : MISSING_RESULT_MESSAGE;
            execution.replaceContent(safeMessage);
            execution.resolveBufferedOutput();
            session.delta(safeMessage);
            execution.metrics.markFirstDeltaSent();
            logOutcome(
                    execution,
                    actualKinds,
                    !grounded ? "NO_RELIABLE_KNOWLEDGE"
                            : staged.isEmpty() ? "MISSING_RESULT" : "UNEXPECTED_RESULT");
            return;
        }

        // 先提升为可持久化结果，再按产生顺序发布；发送失败时沿用现有 OUTPUT_ERROR 收尾。
        execution.promoteStagedResults();
        // 结构化结果已通过本轮类型与知识证据检查，此后即使 SSE 发送失败也可安全落库。
        execution.resolveBufferedOutput();
        for (StagedToolResult value : staged) {
            session.result(value.uiResult());
            recordResult(value.uiResult().kind(), "PUBLISHED");
        }
        String text = execution.content.toString();
        if (!text.isEmpty()) {
            session.delta(text);
            execution.metrics.markFirstDeltaSent();
        }
        logOutcome(execution, actualKinds, "FRESH_RESULT_ACCEPTED");
    }

    /**
     * 统一收尾前清理尚未通过门禁的缓冲输出。
     *
     * <p>模型超长截断、流异常、取消等路径不会执行 {@link #flush}。如果此时直接
     * 持久化，模型已经生成的“查询成功”正文会在历史消息中重新出现。因此只要
     * 缓冲区已有正文或暂存结果，就必须丢弃暂存卡片并换成固定安全提示。</p>
     */
    void sanitizeForPersistence(ChatTurnExecution execution) {
        if (!execution.buffersModelOutput()
                || execution.isBufferedOutputResolved()
                || (execution.content.isEmpty()
                && execution.stagedResultSnapshot().isEmpty())) {
            return;
        }
        boolean knowledgeRequired = execution.queryPlan.acceptedResultKinds()
                .contains("knowledge-citations");
        List<StagedToolResult> staged = execution.stagedResultSnapshot();
        recordGateRejected(execution, staged);
        execution.discardStagedResults();
        execution.replaceContent(knowledgeRequired
                ? NO_KNOWLEDGE_MESSAGE : MISSING_RESULT_MESSAGE);
        execution.resolveBufferedOutput();
        logOutcome(execution, Set.of(), "UNVERIFIED_OUTPUT_DISCARDED");
    }

    private void recordGateRejected(
            ChatTurnExecution execution,
            List<StagedToolResult> staged) {
        if (staged.isEmpty()) {
            execution.queryPlan.acceptedResultKinds()
                    .forEach(kind -> recordResult(kind, "GATE_REJECTED"));
            return;
        }
        staged.forEach(value -> recordResult(
                value.uiResult().kind(), "GATE_REJECTED"));
    }

    private void recordResult(String kind, String outcome) {
        if (toolCallMetrics != null) {
            toolCallMetrics.result(kind, outcome);
        }
    }

    /** 只有 Knowledge Service 明确判定可回答且携带真实证据，才允许知识结论通过。 */
    private boolean isAnswerableKnowledge(StagedToolResult staged) {
        return "knowledge-citations".equals(staged.uiResult().kind())
                && staged.uiResult().data() instanceof KnowledgeRetrievalResult result
                && result.answerable()
                && !result.evidences().isEmpty();
    }

    /** 只记录类型集合和结果，不记录工具正文或业务敏感字段。 */
    private void logOutcome(
            ChatTurnExecution execution,
            Set<String> actualKinds,
            String outcome) {
        log.info(
                "chat_business_query_gate requestId={}, mode={}, expectedKinds={}, actualKinds={}, outcome={}",
                execution.requestId,
                execution.queryPlan.mode(),
                execution.queryPlan.acceptedResultKinds(),
                actualKinds,
                outcome);
    }
}
