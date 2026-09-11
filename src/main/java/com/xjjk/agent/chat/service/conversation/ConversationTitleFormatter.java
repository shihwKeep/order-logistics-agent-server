package com.xjjk.agent.chat.service.conversation;

/**
 * 会话标题格式化规则。
 */
public final class ConversationTitleFormatter {

    public static final String DEFAULT_TITLE = "新会话";
    public static final int MAX_CODE_POINTS = 15;

    private ConversationTitleFormatter() {
    }

    public static String fromQuestion(String question) {
        if (question == null) {
            return DEFAULT_TITLE;
        }
        String normalized = question.strip().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            return DEFAULT_TITLE;
        }
        int count = normalized.codePointCount(0, normalized.length());
        if (count <= MAX_CODE_POINTS) {
            return normalized;
        }
        int end = normalized.offsetByCodePoints(0, MAX_CODE_POINTS);
        return normalized.substring(0, end) + "...";
    }
}
