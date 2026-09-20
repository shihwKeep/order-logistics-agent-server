package com.xjjk.agent.knowledge.tool;

import com.xjjk.agent.knowledge.domain.KnowledgeRetrievalResult;
import com.xjjk.agent.knowledge.service.KnowledgeQueryGateway;
import com.xjjk.agent.knowledge.service.KnowledgeServiceUnavailableException;
import com.xjjk.agent.knowledge.service.KnowledgeModelBudgetExceededException;
import com.xjjk.agent.tool.AgentToolRequestContext;
import com.xjjk.agent.tool.ToolUiResult;
import com.xjjk.agent.prompt.AgentPromptCatalogProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/** 面向模型的租户知识检索工具；引用元数据不经过模型生成。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeQueryTools {
    private static final int MAX_MODEL_TEXT = 7_000;

    private final KnowledgeQueryGateway gateway;
    private final KnowledgeToolAvailability availability;
    private final AgentPromptCatalogProperties promptCatalog;

    @Tool(name = "search_knowledge")
    public String searchKnowledge(
            @ToolParam String question,
            ToolContext toolContext) {
        AgentToolRequestContext context = requestContext(toolContext);
        if (!availability.isAvailable(context.identity())) {
            return "当前组织暂未开放知识库查询能力。";
        }
        String normalized = normalizeQuestion(question);
        if (normalized == null) {
            return "请提供不超过2000个字符且不含控制字符的知识问题。";
        }
        try {
            return context.callGuard().execute(
                    "search_knowledge", normalized,
                    () -> execute(normalized, context));
        } catch (KnowledgeModelBudgetExceededException exception) {
            log.warn("knowledge_tool_budget_exhausted requestId={}", context.requestId());
            return "知识检索模型本月额度已用尽，请联系管理员。";
        } catch (KnowledgeServiceUnavailableException exception) {
            log.warn("knowledge_tool_failed requestId={}, exceptionType={}",
                    context.requestId(), exception.getClass().getSimpleName());
            return "知识检索服务暂时不可用，请稍后重试。";
        }
    }

    private String execute(String question, AgentToolRequestContext context) {
        KnowledgeRetrievalResult result = gateway.retrieve(
                question, List.of(), context.identity(), context.requestId());
        // 即使不可回答，也发布结构化决策供回答门禁识别；门禁不会把空引用发给前端。
        context.outputPublisher().publish(new ToolUiResult(
                "search_knowledge", "knowledge-citations", 1,
                result.queriedAt(), result));
        if (!result.answerable()) {
            return "知识库中暂未找到可靠依据。不要依据常识补充业务结论。";
        }
        StringBuilder text = new StringBuilder(
                promptCatalog.knowledge().evidenceHeader());
        for (int index = 0; index < result.evidences().size(); index++) {
            KnowledgeRetrievalResult.Evidence evidence = result.evidences().get(index);
            text.append("\n[证据").append(index + 1).append("]《")
                    .append(evidence.documentTitle()).append("》");
            if (evidence.titlePath() != null && !evidence.titlePath().isBlank()) {
                text.append(" ").append(evidence.titlePath());
            }
            text.append("：").append(evidence.content());
            if (text.length() >= MAX_MODEL_TEXT) break;
        }
        return text.length() <= MAX_MODEL_TEXT
                ? text.toString() : text.substring(0, MAX_MODEL_TEXT - 1) + "…";
    }

    private String normalizeQuestion(String value) {
        if (value == null) return null;
        String normalized = value.strip();
        return normalized.isEmpty() || normalized.length() > 2_000
                || normalized.codePoints().anyMatch(codePoint ->
                Character.isISOControl(codePoint)
                        && codePoint != '\n' && codePoint != '\r' && codePoint != '\t')
                ? null : normalized;
    }

    private AgentToolRequestContext requestContext(ToolContext context) {
        if (context == null) throw new IllegalStateException("知识工具缺少请求上下文");
        Object value = context.getContext().get(AgentToolRequestContext.CONTEXT_KEY);
        if (!(value instanceof AgentToolRequestContext requestContext)) {
            throw new IllegalStateException("知识工具请求上下文不合法");
        }
        return requestContext;
    }
}
