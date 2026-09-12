package com.xjjk.agent.memory.service;

import java.util.List;

/**
 * 显式记忆语义分类的低成本前置门控。命中只代表值得分类，不能授权写入。
 */
public class ExplicitMemoryCandidateGate {

    private static final List<String> DIRECT_MEMORY_MARKERS = List.of("记住", "记下", "别忘");
    private static final List<String> FUTURE_MARKERS = List.of("以后", "今后", "之后", "从今往后", "后面");
    private static final List<String> PERSONALIZATION_MARKERS = List.of(
            "叫我", "称呼", "回复", "回答", "交流", "使用", "默认");
    private static final List<String> PERSONAL_SUBJECT_MARKERS = List.of(
            "我", "我的", "本人", "偏好", "习惯");

    private final int maxMessageCodePoints;

    public ExplicitMemoryCandidateGate(int maxMessageCodePoints) {
        if (maxMessageCodePoints < 1) {
            throw new IllegalArgumentException("maxMessageCodePoints must be positive");
        }
        this.maxMessageCodePoints = maxMessageCodePoints;
    }

    public boolean mightContainExplicitMemory(String input) {
        if (input == null) {
            return false;
        }
        String message = ExplicitMemoryCommandDetector.normalizeWhitespace(input);
        if (message.isBlank()
                || message.codePointCount(0, message.length()) > maxMessageCodePoints) {
            return false;
        }
        if (containsAny(message, DIRECT_MEMORY_MARKERS)) {
            return true;
        }
        if (message.contains("保存") && containsAny(message, PERSONAL_SUBJECT_MARKERS)) {
            return true;
        }
        return containsAny(message, FUTURE_MARKERS)
                && containsAny(message, PERSONALIZATION_MARKERS);
    }

    private static boolean containsAny(String message, List<String> markers) {
        return markers.stream().anyMatch(message::contains);
    }
}
