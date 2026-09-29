package com.xjjk.agent.chat.orchestration;

import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.knowledge.domain.KnowledgeRetrievalResult;
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
            Set<String> actual = new LinkedHashSet<>();
            uiResults.forEach(result -> actual.add(result.kind()));
            if (!knowledge.isEmpty()) {
                actual.add("knowledge-citations");
            }
            boolean success = "SUCCESS".equals(state.finalStatus());
            String safeMessage = "NO_RELIABLE_KNOWLEDGE".equals(state.finalStatus())
                    ? NO_KNOWLEDGE_MESSAGE : MISSING_RESULT_MESSAGE;
            return new CompositeQueryResult(
                    success,
                    state.finalStatus(),
                    List.copyOf(uiResults),
                    Set.copyOf(actual),
                    state.answerContext(),
                    success ? "" : safeMessage);
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
