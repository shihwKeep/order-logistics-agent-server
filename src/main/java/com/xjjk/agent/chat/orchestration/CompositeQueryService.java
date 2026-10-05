package com.xjjk.agent.chat.orchestration;

import com.xjjk.agent.customer.service.CustomerOrderQueryResult;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.knowledge.domain.KnowledgeRetrievalResult;
import com.xjjk.agent.order.domain.OrderSearchResult;
import com.xjjk.agent.tool.ToolUiResult;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** ChatTurnRunner 使用的复合查询应用服务。 */
@Service
public class CompositeQueryService {
    private static final String MISSING_RESULT_MESSAGE =
            "本轮未完成实时业务查询，请补充查询条件或稍后重试。";
    static final String BUSINESS_QUERY_FAILED_MESSAGE =
            "实时业务查询暂时失败，请核对订单号或稍后重试。";
    public static final String PARTIAL_RESULT_MESSAGE =
            "部分实时业务查询已完成；缺失分支未能完成，无法据此确认相关业务结论。";
    private static final String NO_KNOWLEDGE_MESSAGE =
            "知识库中暂未找到相关规定，我不能在没有可靠依据的情况下给出业务结论。";

    private final CompositeQueryWorkflow workflow;

    public CompositeQueryService(CompositeQueryWorkflow workflow) {
        this.workflow = workflow;
    }

    public CompositeQueryResult execute(
            CompositeQueryPlan plan,
            String message,
            AgentIdentity identity,
            String requestId) {
        return workflow.execute(plan, message, identity, requestId, requestId);
    }

    public record CompositeQueryResult(
            boolean success,
            String status,
            List<ToolUiResult> uiResults,
            Set<String> actualResultKinds,
            String verifiedAnswerContext,
            String safeMessage) {

        public static CompositeQueryResult from(
                CompositeQueryState state,
                List<ToolUiResult> results,
                List<KnowledgeRetrievalResult> knowledge) {
            List<ToolUiResult> uiResults = new ArrayList<>(results);
            knowledge.forEach(result -> uiResults.add(new ToolUiResult(
                    "search_knowledge", "knowledge-citations", 1,
                    result.queriedAt(), result)));
            if (uiResults.isEmpty() && !state.branchSnapshots().isEmpty()) {
                state.branchSnapshots().stream()
                        .filter(snapshot -> "SUCCESS".equals(snapshot.status()))
                        .forEach(snapshot -> uiResults.add(new ToolUiResult(
                                "composite-checkpoint", snapshot.resultKind(), 1,
                                java.time.OffsetDateTime.now(),
                                java.util.Map.of("summary", snapshot.safeSummary()))));
            }
            Set<String> actual = new LinkedHashSet<>();
            uiResults.forEach(result -> actual.add(result.kind()));
            state.branchSnapshots().stream()
                    .filter(snapshot -> "SUCCESS".equals(snapshot.status()))
                    .map(CompositeQueryBranchSnapshot::resultKind)
                    .forEach(actual::add);
            if (state.requiredResultKinds().contains("general-analysis")
                    && "SUCCESS".equals(state.finalStatus())) {
                actual.add("general-analysis");
            }
            if (!knowledge.isEmpty()) {
                actual.add("knowledge-citations");
            }
            boolean success = "SUCCESS".equals(state.finalStatus());
            String safeMessage;
            if ("NO_RELIABLE_KNOWLEDGE".equals(state.finalStatus())) {
                safeMessage = NO_KNOWLEDGE_MESSAGE;
            } else if ("PARTIAL_SUCCESS".equals(state.finalStatus())) {
                safeMessage = partialResultMessage(state, uiResults);
            } else if ("FAILED".equals(state.finalStatus())
                    && state.failures().stream()
                    .anyMatch(kind -> !"knowledge-citations".equals(kind))) {
                safeMessage = BUSINESS_QUERY_FAILED_MESSAGE;
            } else {
                safeMessage = MISSING_RESULT_MESSAGE;
            }
            return new CompositeQueryResult(
                    success,
                    state.finalStatus(),
                    List.copyOf(uiResults),
                    Set.copyOf(actual),
                    state.answerContext(),
                    success ? "" : safeMessage);
        }

        private static String partialResultMessage(
                CompositeQueryState state,
                List<ToolUiResult> results) {
            boolean logisticsRequested = state.requiredResultKinds()
                    .contains("logistics-timeline");
            boolean logisticsMissing = logisticsRequested && results.stream()
                    .noneMatch(result -> "logistics-timeline".equals(result.kind()));
            if (logisticsMissing && hasMissingCustomerOrder(results)) {
                return "未找到客户订单，因此未执行物流查询，无法判断物流状态或售后资格。";
            }
            if (logisticsMissing && hasMissingExplicitOrder(results)) {
                return "未找到该订单，因此未执行物流查询，无法判断物流状态。";
            }
            return PARTIAL_RESULT_MESSAGE;
        }

        private static boolean hasMissingCustomerOrder(List<ToolUiResult> results) {
            return results.stream()
                    .filter(result -> "order-list".equals(result.kind()))
                    .map(ToolUiResult::data)
                    .filter(CustomerOrderQueryResult.class::isInstance)
                    .map(CustomerOrderQueryResult.class::cast)
                    .anyMatch(result -> result.orders() == null
                            || result.orders().total() == 0);
        }

        private static boolean hasMissingExplicitOrder(List<ToolUiResult> results) {
            return results.stream()
                    .filter(result -> "order-list".equals(result.kind()))
                    .map(ToolUiResult::data)
                    .filter(OrderSearchResult.class::isInstance)
                    .map(OrderSearchResult.class::cast)
                    .anyMatch(result -> result.total() == 0);
        }

        public static CompositeQueryResult success(
                List<ToolUiResult> results, String context) {
            Set<String> kinds = new LinkedHashSet<>();
            results.forEach(result -> kinds.add(result.kind()));
            return new CompositeQueryResult(
                    true, "SUCCESS", List.copyOf(results), Set.copyOf(kinds), context, "");
        }

        public static CompositeQueryResult missingResult() {
            return new CompositeQueryResult(
                    false, "MISSING_RESULT", List.of(), Set.of(), "", MISSING_RESULT_MESSAGE);
        }

        public static CompositeQueryResult failed() {
            return new CompositeQueryResult(
                    false, "FAILED", List.of(), Set.of(), "", MISSING_RESULT_MESSAGE);
        }
    }
}
