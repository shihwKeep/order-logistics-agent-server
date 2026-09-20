package com.xjjk.agent.chat.service.summary;

import com.xjjk.agent.chat.config.ChatSummaryProperties;
import com.xjjk.agent.chat.domain.summary.ChatSummaryContent;
import com.xjjk.agent.chat.domain.summary.ChatSummaryEntity;
import com.xjjk.agent.chat.domain.summary.ChatSummaryFact;
import com.xjjk.agent.chat.domain.summary.ChatSummaryItem;
import com.xjjk.agent.chat.service.memory.QwenTextTokenEstimator;
import com.xjjk.agent.prompt.AgentPromptCatalogProperties;
import com.xjjk.agent.prompt.StrictPromptTemplateRenderer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map;

/**
 * 将结构化摘要渲染为低权限、受 Token 上限约束的历史上下文。
 *
 * 渲染顺序由代码固定，绝不把模型生成内容拼进系统提示词。
 */
@Component
@RequiredArgsConstructor
public class ChatSummaryContextRenderer {

    private final ChatSummaryProperties properties;
    private final QwenTextTokenEstimator tokenEstimator;
    private final AgentPromptCatalogProperties promptCatalog;
    private final StrictPromptTemplateRenderer promptRenderer;

    /**
     * 渲染摘要；超过预算时按完整条目删除低优先级内容，禁止截断字符串或 JSON。
     */
    public String render(ChatSummaryContent content) {
        Objects.requireNonNull(content, "待渲染摘要不能为空");
        List<ChatSummaryFact> facts = new ArrayList<>(
                content.conversationFacts()
        );
        List<ChatSummaryItem> decisions = new ArrayList<>(
                content.decisions()
        );
        List<ChatSummaryEntity> entities = new ArrayList<>(
                content.importantEntities()
        );

        while (true) {
            String rendered = render(
                    content,
                    facts,
                    decisions,
                    entities
            );
            if (tokenEstimator.estimate(rendered)
                    <= properties.contextMaxTokens()) {
                return rendered;
            }
            if (!facts.isEmpty()) {
                facts.remove(facts.size() - 1);
            } else if (!decisions.isEmpty()) {
                decisions.remove(decisions.size() - 1);
            } else if (!entities.isEmpty()) {
                entities.remove(entities.size() - 1);
            } else {
                // 当前状态和待解决问题不能静默丢弃，预算不足属于配置或摘要质量问题。
                throw new IllegalStateException("摘要核心内容超过上下文 Token 预算");
            }
        }
    }

    private String render(
            ChatSummaryContent content,
            List<ChatSummaryFact> facts,
            List<ChatSummaryItem> decisions,
            List<ChatSummaryEntity> entities
    ) {
        StringBuilder output = new StringBuilder(512);
        AgentPromptCatalogProperties.SummaryContext prompts =
                promptCatalog.context().summary();
        output.append(prompts.openMarker()).append('\n')
                .append(promptRenderer.render(
                        "agent.ai.prompt.catalog.context.summary.header-template",
                        prompts.headerTemplate(),
                        Map.of("topic", escape(content.topic()),
                                "currentState", escape(content.currentState()))))
                .append('\n');
        appendFacts(output, prompts.factHeading(), facts);
        appendItems(output, prompts.decisionHeading(), decisions);
        appendItems(output, prompts.openQuestionHeading(), content.openQuestions());
        appendEntities(output, prompts.entityHeading(), entities);
        output.append(prompts.closeMarker());
        return output.toString();
    }

    private static void appendFacts(
            StringBuilder output,
            String title,
            List<ChatSummaryFact> facts
    ) {
        output.append(title).append('\n');
        for (ChatSummaryFact fact : facts) {
            output.append("- [")
                    .append(fact.sourceType())
                    .append('#').append(fact.sourceSequence())
                    .append("] ").append(escape(fact.content())).append('\n');
        }
    }

    private static void appendItems(
            StringBuilder output,
            String title,
            List<ChatSummaryItem> items
    ) {
        output.append(title).append('\n');
        for (ChatSummaryItem item : items) {
            output.append("- [MESSAGE#")
                    .append(item.sourceSequence())
                    .append("] ").append(escape(item.content())).append('\n');
        }
    }

    private static void appendEntities(
            StringBuilder output,
            String title,
            List<ChatSummaryEntity> entities
    ) {
        output.append(title).append('\n');
        for (ChatSummaryEntity entity : entities) {
            output.append("- ").append(escape(entity.entityType()))
                    .append('=').append(escape(entity.displayValue()))
                    .append(" [").append(entity.sourceType())
                    .append('#').append(entity.sourceSequence())
                    .append("]\n");
        }
    }

    /**
     * 把模型生成字段限制在单个文本值内，避免换行或保留标记伪造新的摘要结构。
     */
    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            switch (codePoint) {
                case '\\' -> escaped.append("\\\\");
                case '[' -> escaped.append("\\[");
                case ']' -> escaped.append("\\]");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (Character.isISOControl(codePoint)) {
                        escaped.append(String.format("\\u%04X", codePoint));
                    } else {
                        escaped.appendCodePoint(codePoint);
                    }
                }
            }
        });
        return escaped.toString();
    }
}
