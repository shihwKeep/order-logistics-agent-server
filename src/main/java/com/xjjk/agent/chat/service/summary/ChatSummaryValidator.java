package com.xjjk.agent.chat.service.summary;

import com.xjjk.agent.chat.domain.summary.ChatSummaryContent;
import com.xjjk.agent.chat.domain.summary.ChatSummaryEntity;
import com.xjjk.agent.chat.domain.summary.ChatSummaryFact;
import com.xjjk.agent.chat.domain.summary.ChatSummaryItem;
import com.xjjk.agent.chat.domain.summary.ChatSummarySourceType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 模型摘要输出的结构、长度和来源校验器。
 *
 * 模型只能整理 Java 提供的来源消息，不能自行扩大来源范围或提升事实可信级别。
 */
@Component
public class ChatSummaryValidator {

    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_TOPIC_LENGTH = 128;
    private static final int MAX_STATE_LENGTH = 1_000;
    private static final int MAX_ITEM_LENGTH = 500;
    private static final int MAX_FACTS = 30;
    private static final int MAX_DECISIONS = 20;
    private static final int MAX_QUESTIONS = 20;
    private static final int MAX_ENTITIES = 30;

    /**
     * 校验摘要中的每个来源序号均属于本次模型输入，并且来源角色一致。
     */
    public ChatSummaryContent validate(
            ChatSummaryContent content,
            Map<Long, ChatSummarySourceType> allowedSources
    ) {
        return validate(content, allowedSources, allowedSources.keySet());
    }

    /**
     * 旧摘要中的决定和待解决问题只保存来源序号，因此与带角色来源分开校验。
     */
    public ChatSummaryContent validate(
            ChatSummaryContent content,
            Map<Long, ChatSummarySourceType> allowedSources,
            Set<Long> allowedSequences
    ) {
        Objects.requireNonNull(content, "摘要内容不能为空");
        Objects.requireNonNull(allowedSources, "摘要来源映射不能为空");
        Objects.requireNonNull(allowedSequences, "摘要来源序号不能为空");
        if (content.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("摘要结构版本不受支持");
        }
        requireLength(content.topic(), MAX_TOPIC_LENGTH, "摘要主题");
        requireLength(content.currentState(), MAX_STATE_LENGTH, "摘要当前状态");
        requireCount(content.conversationFacts(), MAX_FACTS, "摘要事实");
        requireCount(content.decisions(), MAX_DECISIONS, "摘要决定");
        requireCount(content.openQuestions(), MAX_QUESTIONS, "摘要待解决问题");
        requireCount(content.importantEntities(), MAX_ENTITIES, "摘要实体");

        for (ChatSummaryFact fact : content.conversationFacts()) {
            requireLength(fact.content(), MAX_ITEM_LENGTH, "摘要事实");
            requireSource(
                    allowedSources,
                    fact.sourceSequence(),
                    fact.sourceType()
            );
        }
        validateItems(content.decisions(), allowedSequences, "摘要决定");
        validateItems(content.openQuestions(), allowedSequences, "摘要待解决问题");
        for (ChatSummaryEntity entity : content.importantEntities()) {
            requireLength(entity.entityType(), MAX_TOPIC_LENGTH, "摘要实体类型");
            requireLength(entity.displayValue(), MAX_ITEM_LENGTH, "摘要实体值");
            requireSource(
                    allowedSources,
                    entity.sourceSequence(),
                    entity.sourceType()
            );
        }
        return content;
    }

    private static void validateItems(
            List<ChatSummaryItem> items,
            Set<Long> allowedSequences,
            String fieldName
    ) {
        for (ChatSummaryItem item : items) {
            requireLength(item.content(), MAX_ITEM_LENGTH, fieldName);
            if (!allowedSequences.contains(item.sourceSequence())) {
                throw new IllegalArgumentException(fieldName + "来源序号不在本次输入范围");
            }
        }
    }

    private static void requireSource(
            Map<Long, ChatSummarySourceType> allowedSources,
            long sequence,
            ChatSummarySourceType sourceType
    ) {
        ChatSummarySourceType expected = allowedSources.get(sequence);
        if (expected == null || expected != sourceType) {
            throw new IllegalArgumentException("摘要来源序号或角色不可信");
        }
    }

    private static void requireLength(
            String value,
            int maxLength,
            String fieldName
    ) {
        int length = value.codePointCount(0, value.length());
        if (length > maxLength) {
            throw new IllegalArgumentException(fieldName + "超过长度限制");
        }
    }

    private static void requireCount(
            List<?> values,
            int maxCount,
            String fieldName
    ) {
        if (values.size() > maxCount) {
            throw new IllegalArgumentException(fieldName + "超过数量限制");
        }
    }
}
