package com.xjjk.agent.chat.domain.summary;

import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 经过应用校验、可安全持久化的结构化会话摘要。
 *
 * 摘要只描述当前会话的历史，不代表业务系统已经核实其中事实。
 * 所有集合都会在构造时复制，防止提交后被调用方继续修改。
 *
 * @param schemaVersion 摘要 JSON 结构版本
 * @param topic 当前会话主题
 * @param currentState 当前处理状态
 * @param conversationFacts 会话中需要保留的事实
 * @param decisions 已形成的决定
 * @param openQuestions 尚未解决的问题
 * @param importantEntities 后续对话需要识别的重要实体
 */
public record ChatSummaryContent(
        int schemaVersion,
        String topic,
        String currentState,
        List<ChatSummaryFact> conversationFacts,
        List<ChatSummaryItem> decisions,
        List<ChatSummaryItem> openQuestions,
        List<ChatSummaryEntity> importantEntities
) {

    public ChatSummaryContent {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("摘要结构版本必须大于零");
        }
        if (!StringUtils.hasText(topic)
                || !StringUtils.hasText(currentState)) {
            throw new IllegalArgumentException("摘要主题和当前状态不能为空");
        }
        conversationFacts = List.copyOf(conversationFacts);
        decisions = List.copyOf(decisions);
        openQuestions = List.copyOf(openQuestions);
        importantEntities = List.copyOf(importantEntities);
    }

    /** 防止摘要正文被调试日志或异常对象字符串意外输出。 */
    @Override
    public String toString() {
        return "ChatSummaryContent["
                + "schemaVersion=" + schemaVersion
                + ", factCount=" + conversationFacts.size()
                + ", decisionCount=" + decisions.size()
                + ", openQuestionCount=" + openQuestions.size()
                + ", entityCount=" + importantEntities.size()
                + ", content=<redacted>]";
    }
}
